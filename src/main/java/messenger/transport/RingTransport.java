package messenger.transport;

import messenger.nio.*;
import messenger.protocol.ChatMessage;
import messenger.queue.OutboundMessage;
import messenger.queue.OutboxStore;
import messenger.ring.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Кольцевой транспорт на NIO.
 * <p>
 * Поддерживает 2 соединения:
 * - Левое (входящее): принимает сообщения от левого соседа
 * - Правое (исходящее): отправляет сообщения правому соседу
 * <p>
 * Особенности:
 * - Все I/O операции неблокирующие через NioEventLoop
 * - Автоматическая дедупликация сообщений
 * - Persisted outbox для ненадёжных соединений
 * - Handshake protocol для обмена nodeId
 */
public final class RingTransport implements AutoCloseable {

    private static final int MAX_SEEN_MESSAGES = 10_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    private final RingState ringState;
    private final int tcpPort;
    private final OutboxStore outboxStore;
    private final Consumer<ChatMessage> onLocalDeliver;
    private final NioEventLoop eventLoop;

    private final AtomicLong sequenceCounter = new AtomicLong(0L);

    // Дедупликация сообщений (thread-safe для разных handlers)
    private final Map<String, Boolean> seenMessageIds = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_SEEN_MESSAGES;
                }
            }
    );

    private ServerSocketChannel serverChannel;
    private volatile LeftConnection leftConnection;
    private volatile RightConnection rightConnection;

    private volatile boolean running;
    private final AtomicBoolean connectingRight = new AtomicBoolean(false);

    // Executor для асинхронных задач (connection, outbox drain)
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "RingTransport-Worker");
        t.setDaemon(true);
        return t;
    });

    public RingTransport(
            RingState ringState,
            int tcpPort,
            OutboxStore outboxStore,
            Consumer<ChatMessage> onLocalDeliver,
            NioEventLoop eventLoop
    ) {
        this.ringState = ringState;
        this.tcpPort = tcpPort;
        this.outboxStore = outboxStore;
        this.onLocalDeliver = onLocalDeliver;
        this.eventLoop = eventLoop;
    }

    /**
     * Запуск транспорта.
     */
    public void start() throws IOException {
        running = true;

        // Открываем server socket для входящих соединений
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(tcpPort));
        serverChannel.configureBlocking(false);

        // Регистрируем в event loop
        eventLoop.register(serverChannel, SelectionKey.OP_ACCEPT, new ServerHandler());

        System.out.println("[RingTransport] Started on port " + tcpPort + " (NIO mode)");
    }

    /**
     * Обновление топологии кольца.
     * Вызывается при изменении соседей.
     */
    public void refreshTopology() {
        if (!running) {
            return;
        }

        Optional<NodeInfo> right = ringState.rightNeighbor();

        // Если нет правого соседа или мы одни в кольце
        if (right.isEmpty() || right.get().nodeId() == ringState.myId()) {
            closeRightConnection("no right neighbor or single node");
            return;
        }

        NodeInfo rightInfo = right.get();
        RightConnection current = rightConnection;

        // Если уже подключены к нужному узлу
        if (current != null && current.nodeId == rightInfo.nodeId() && current.isConnected()) {
            return;
        }

        // Запускаем подключение к новому соседу
        if (!connectingRight.compareAndSet(false, true)) {
            return; // Уже подключаемся
        }

        executor.execute(() -> {
            try {
                connectRight(rightInfo);
            } finally {
                connectingRight.set(false);
            }
        });
    }

    /**
     * Отправка сообщения в кольцо.
     *
     * @param targetId ID получателя или TARGET_BROADCAST
     * @param payload  Текст сообщения
     */
    public void sendToRing(long targetId, String payload) {
        long sequence = sequenceCounter.incrementAndGet();
        long senderId = ringState.myId();
        String messageId = ChatMessage.buildMessageId(senderId, sequence);

        ChatMessage message = new ChatMessage(messageId, sequence, senderId, targetId, payload);

        // Помечаем как уже виденное (чтобы не обработать свое сообщение повторно)
        markSeen(message.messageId());

        // Локальная доставка если адресовано нам или broadcast
        if (targetId == senderId || targetId == ChatMessage.TARGET_BROADCAST) {
            onLocalDeliver.accept(message);
        }

        // Отправка правому соседу
        RightConnection conn = rightConnection;
        if (conn == null || !conn.isConnected()) {
            enqueueForRight(message);
            return;
        }

        try {
            conn.send(message);
        } catch (Exception ex) {
            System.err.println("[RingTransport] Send failed: " + ex.getMessage());
            enqueueForRight(message);
            closeRightConnection("send failed");
        }
    }

    // ========================================================================
    // SERVER HANDLER (принимает входящие соединения)
    // ========================================================================

    private class ServerHandler implements ChannelHandler {
        @Override
        public void handleAccept(SelectionKey key) throws IOException {
            ServerSocketChannel server = (ServerSocketChannel) key.channel();
            SocketChannel client = server.accept();

            if (client != null) {
                System.out.println("[RingTransport] Accepted connection from " + client.getRemoteAddress());

                client.configureBlocking(false);
                client.socket().setKeepAlive(true);
                client.socket().setTcpNoDelay(true);

                // Закрываем предыдущее левое соединение
                if (leftConnection != null) {
                    System.out.println("[RingTransport] Replacing left connection");
                    leftConnection.close();
                }

                // Создаём новый handler
                LeftConnection conn = new LeftConnection(client);
                leftConnection = conn;

                // Регистрируем в event loop
                eventLoop.register(client, SelectionKey.OP_READ, conn);
            }
        }

        @Override
        public void handleConnect(SelectionKey key) {
        }

        @Override
        public void handleRead(SelectionKey key) {
        }

        @Override
        public void handleWrite(SelectionKey key) {
        }

        @Override
        public void handleError(SelectionKey key, Exception e) {
            System.err.println("[RingTransport] Server error: " + e.getMessage());
        }
    }

    // ========================================================================
    // LEFT CONNECTION (входящее соединение от левого соседа)
    // ========================================================================

    private class LeftConnection implements ChannelHandler {
        private final SocketChannel channel;
        private final ByteBuffer readBuffer;
        private final MessageParser parser;

        private long remoteId = -1L;
        private boolean handshakeDone = false;
        private long handshakeStartMs = System.currentTimeMillis();

        LeftConnection(SocketChannel channel) {
            this.channel = channel;
            this.readBuffer = ByteBuffer.allocate(16384); // 16 KB read buffer
            this.parser = new MessageParser();
        }

        @Override
        public void handleAccept(SelectionKey key) {
        }

        @Override
        public void handleConnect(SelectionKey key) {
        }

        @Override
        public void handleRead(SelectionKey key) throws IOException {
            if (!handshakeDone) {
                handleHandshake();
                return;
            }

            readBuffer.clear();
            int bytesRead = channel.read(readBuffer);

            if (bytesRead == -1) {
                // Соединение закрыто удалённой стороной
                System.out.println("[RingTransport] Left connection closed by peer");
                close();
                return;
            }

            if (bytesRead > 0) {
                readBuffer.flip();
                parser.feed(readBuffer);

                // Обрабатываем все доступные сообщения
                for (ChatMessage message : parser.drainMessages()) {
                    handleMessage(message);
                }
            }
        }

        private void handleHandshake() throws IOException {
            // Проверка таймаута
            if (System.currentTimeMillis() - handshakeStartMs > HANDSHAKE_TIMEOUT_MS) {
                System.err.println("[RingTransport] Left handshake timeout");
                close();
                return;
            }

            readBuffer.clear();
            readBuffer.limit(8); // Ожидаем 8 байт (long)

            int bytesRead = channel.read(readBuffer);
            if (bytesRead == -1) {
                close();
                return;
            }

            if (readBuffer.position() == 8) {
                readBuffer.flip();
                remoteId = readBuffer.getLong();
                handshakeDone = true;

                System.out.println("[RingTransport] Left handshake complete: remoteId=" + remoteId);
                readBuffer.clear(); // Готовы к приёму сообщений
            }
        }

        private void handleMessage(ChatMessage message) {
            // Дедупликация
            if (isSeen(message.messageId())) {
                return; // Уже обрабатывали это сообщение
            }

            markSeen(message.messageId());

            boolean forMe = message.targetId() == ringState.myId();
            boolean isBroadcast = message.isBroadcast();

            // Локальная доставка
            if (forMe || isBroadcast) {
                onLocalDeliver.accept(message);
            }

            // Пересылка дальше (если не для нас)
            if (!forMe) {
                forwardRight(message);
            }
        }

        @Override
        public void handleWrite(SelectionKey key) {
        }

        @Override
        public void handleError(SelectionKey key, Exception e) {
            System.err.println("[RingTransport] Left connection error: " + e.getMessage());
            close();
        }

        void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }

            if (leftConnection == this) {
                leftConnection = null;
            }
        }
    }

    // ========================================================================
    // RIGHT CONNECTION (исходящее соединение к правому соседу)
    // ========================================================================

    private class RightConnection implements ChannelHandler {
        private final SocketChannel channel;
        private final long nodeId;
        private final ByteBuffer handshakeBuffer;
        private final Queue<ByteBuffer> writeQueue;

        private volatile boolean connected = false;
        private boolean handshakeSent = false;

        RightConnection(SocketChannel channel, long nodeId) {
            this.channel = channel;
            this.nodeId = nodeId;
            this.writeQueue = new ConcurrentLinkedQueue<>();

            // Подготовка handshake (наш nodeId)
            this.handshakeBuffer = ByteBuffer.allocate(8);
            handshakeBuffer.putLong(ringState.myId());
            handshakeBuffer.flip();
        }

        boolean isConnected() {
            return connected;
        }

        @Override
        public void handleAccept(SelectionKey key) {
        }

        @Override
        public void handleConnect(SelectionKey key) throws IOException {
            if (channel.finishConnect()) {
                System.out.println("[RingTransport] Right connected: nodeId=" + nodeId);

                // Переключаемся на запись для отправки handshake
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }

        @Override
        public void handleRead(SelectionKey key) {
        }

        @Override
        public void handleWrite(SelectionKey key) throws IOException {
            // Сначала отправляем handshake
            if (!handshakeSent) {
                channel.write(handshakeBuffer);

                if (!handshakeBuffer.hasRemaining()) {
                    handshakeSent = true;
                    connected = true;
                    System.out.println("[RingTransport] Right handshake sent to " + nodeId);

                    // Запускаем drain outbox в фоне
                    executor.execute(() -> drainOutbox(nodeId));
                }
                return;
            }

            // Отправляем queued сообщения
            while (true) {
                ByteBuffer buffer = writeQueue.peek();
                if (buffer == null) {
                    // Нечего писать - убираем OP_WRITE
                    key.interestOps(0);
                    break;
                }

                channel.write(buffer);

                if (!buffer.hasRemaining()) {
                    writeQueue.poll(); // Сообщение отправлено полностью
                } else {
                    break; // Socket buffer заполнен, продолжим при следующем OP_WRITE
                }
            }
        }

        /**
         * Отправка сообщения правому соседу.
         * Thread-safe.
         */
        void send(ChatMessage message) {
            ByteBuffer buffer = message.toByteBuffer();
            writeQueue.offer(buffer);

            // Включаем OP_WRITE через event loop
            eventLoop.execute(() -> {
                SelectionKey key = channel.keyFor(eventLoop.getSelector());
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
            });
        }

        @Override
        public void handleError(SelectionKey key, Exception e) {
            System.err.println("[RingTransport] Right connection error: " + e.getMessage());
            close();
        }

        void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }

            connected = false;

            if (rightConnection == this) {
                rightConnection = null;
            }
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Подключение к правому соседу.
     * Вызывается в worker потоке.
     */
    private void connectRight(NodeInfo rightInfo) {
        String endpoint = rightInfo.ip().getHostAddress() + ":" + tcpPort;
        System.out.println("[RingTransport] Connecting to right neighbor: " + endpoint);

        try {
            // Закрываем старое соединение
            closeRightConnection("reconnecting to new neighbor");

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().setKeepAlive(true);
            channel.socket().setTcpNoDelay(true);

            // Начинаем подключение (non-blocking)
            channel.connect(new InetSocketAddress(rightInfo.ip(), tcpPort));

            RightConnection conn = new RightConnection(channel, rightInfo.nodeId());
            rightConnection = conn;

            // Регистрируем в event loop с OP_CONNECT
            eventLoop.register(channel, SelectionKey.OP_CONNECT, conn);

        } catch (IOException ex) {
            System.err.println("[RingTransport] Failed to initiate connection: " + ex.getMessage());
        }
    }

    /**
     * Пересылка сообщения правому соседу.
     */
    private void forwardRight(ChatMessage message) {
        RightConnection conn = rightConnection;
        if (conn == null || !conn.isConnected()) {
            enqueueForRight(message);
            return;
        }

        try {
            conn.send(message);
        } catch (Exception ex) {
            System.err.println("[RingTransport] Forward failed: " + ex.getMessage());
            enqueueForRight(message);
            closeRightConnection("forward failed");
        }
    }

    /**
     * Добавление сообщения в persisted outbox.
     */
    private void enqueueForRight(ChatMessage message) {
        Optional<NodeInfo> right = ringState.rightNeighbor();
        if (right.isEmpty()) {
            return;
        }

        long neighborId = right.get().nodeId();
        OutboundMessage outbound = new OutboundMessage(
                message.messageId(),
                message.sequenceNumber(),
                message.senderId(),
                message.targetId(),
                message.payload(),
                System.currentTimeMillis()
        );

        outboxStore.enqueue(neighborId, outbound);
    }

    /**
     * Отправка накопленных сообщений из outbox.
     * Вызывается в worker потоке после успешного подключения.
     */
    private void drainOutbox(long neighborId) {
        int pending = outboxStore.size(neighborId);
        if (pending == 0) {
            return;
        }

        System.out.println("[RingTransport] Draining outbox: " + pending + " pending messages");

        int drained = 0;
        while (running) {
            OutboundMessage message = outboxStore.peek(neighborId);
            if (message == null) {
                break; // Outbox пуст
            }

            RightConnection conn = rightConnection;
            if (conn == null || !conn.isConnected()) {
                System.out.println("[RingTransport] Drain paused (connection lost)");
                break;
            }

            try {
                conn.send(message.toChatMessage());
                outboxStore.poll(neighborId); // Удаляем из outbox
                drained++;
            } catch (Exception ex) {
                System.err.println("[RingTransport] Drain failed: " + ex.getMessage());
                closeRightConnection("drain failed");
                break;
            }
        }

        if (drained > 0) {
            System.out.println("[RingTransport] Drained " + drained + " messages from outbox");
        }
    }

    private boolean isSeen(String messageId) {
        return seenMessageIds.containsKey(messageId);
    }

    private void markSeen(String messageId) {
        seenMessageIds.put(messageId, Boolean.TRUE);
    }

    private void closeRightConnection(String reason) {
        RightConnection conn = rightConnection;
        if (conn != null) {
            System.out.println("[RingTransport] Closing right connection: " + reason);
            conn.close();
        }
    }

    @Override
    public void close() {
        running = false;

        System.out.println("[RingTransport] Shutting down...");

        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }

        if (leftConnection != null) {
            leftConnection.close();
        }

        closeRightConnection("shutdown");

        executor.shutdownNow();

        System.out.println("[RingTransport] Stopped");
    }
}
