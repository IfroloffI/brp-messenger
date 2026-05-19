package messenger.transport;

import messenger.crypto.CryptoService;
import messenger.nio.ChannelHandler;
import messenger.nio.NioEventLoop;
import messenger.protocol.ChatMessage;
import messenger.protocol.MessageType;
import messenger.queue.OutboxStore;
import messenger.ring.NodeInfo;
import messenger.ring.RingState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * TCP транспорт для кольцевой топологии на базе NIO.
 * <p>
 * Управляет соединениями с левым и правым соседями.
 * Пересылает транзитные сообщения по кольцу.
 */
public final class RingTransport {

    private static final int SERVER_PORT = 8080;
    private static final int RECONNECT_DELAY_SEC = 5;
    private static final int RETRY_DELAY_SEC = 10;

    private final long myNodeId;
    private final NioEventLoop eventLoop;
    private final RingState ringState;
    private final CryptoService cryptoService;
    private final OutboxStore outboxStore;
    private final Consumer<ChatMessage> messageHandler;

    private ServerSocketChannel serverChannel;
    private ConnectionHandler leftConnection;
    private ConnectionHandler rightConnection;

    private final Map<SocketChannel, ConnectionHandler> pendingConnections = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile boolean running = true;

    public RingTransport(long myNodeId,
                         NioEventLoop eventLoop,
                         RingState ringState,
                         CryptoService cryptoService,
                         OutboxStore outboxStore,
                         Consumer<ChatMessage> messageHandler) {
        this.myNodeId = myNodeId;
        this.eventLoop = eventLoop;
        this.ringState = ringState;
        this.cryptoService = cryptoService;
        this.outboxStore = outboxStore;
        this.messageHandler = messageHandler;
    }

    /**
     * Запуск транспорта.
     */
    public void start() throws IOException {
        startServer();
        startConnectionManager();
        startRetryWorker();
        System.out.println("[RingTransport] Started on port " + SERVER_PORT);
    }

    /**
     * Остановка транспорта.
     */
    public void stop() {
        running = false;

        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            System.err.println("[RingTransport] Error closing server: " + e.getMessage());
        }

        System.out.println("[RingTransport] Stopped");
    }

    /**
     * Отправка сообщения.
     */
    public void sendMessage(ChatMessage message) {
        eventLoop.execute(() -> {
            try {
                long messageId = outboxStore.addMessage(message);
                boolean sent = trySendMessage(message);

                if (sent) {
                    outboxStore.removeMessage(messageId);
                }
            } catch (SQLException e) {
                System.err.println("[RingTransport] Failed to save to outbox: " + e.getMessage());
            }
        });
    }

    /**
     * Попытка отправки сообщения правому соседу.
     */
    private boolean trySendMessage(ChatMessage message) {
        if (rightConnection == null || !rightConnection.isOpen()) {
            System.err.println("[RingTransport] No connection to right neighbor");
            return false;
        }

        ChatMessage toSend = message.isEncrypted() ? message : encryptMessage(message);
        return rightConnection.sendMessage(toSend);
    }

    /**
     * Обработка входящего сообщения.
     */
    private void handleIncomingMessage(ChatMessage message) {
        System.out.println("[RingTransport] Received: " + message);

        if (message.getDestinationId() == myNodeId || message.isBroadcast()) {
            ChatMessage decrypted = message.isEncrypted() ? decryptMessage(message) : message;
            messageHandler.accept(decrypted);

            if (message.isBroadcast()) {
                forwardMessage(message);
            }
        } else {
            forwardMessage(message);
        }
    }

    /**
     * Пересылка транзитного сообщения.
     */
    private void forwardMessage(ChatMessage message) {
        if (rightConnection != null && rightConnection.isOpen()) {
            System.out.println("[RingTransport] Forwarding message to right neighbor");
            rightConnection.sendMessage(message);
        } else {
            System.err.println("[RingTransport] Cannot forward: no right connection");
        }
    }

    /**
     * Шифрование сообщения.
     */
    private ChatMessage encryptMessage(ChatMessage message) {
        try {
            if (message.getType() == MessageType.TEXT) {
                ByteBuffer encrypted = cryptoService.encrypt(message.getContent());
                byte[] encryptedBytes = new byte[encrypted.remaining()];
                encrypted.get(encryptedBytes);

                String encryptedContent = java.util.Base64.getEncoder().encodeToString(encryptedBytes);

                return new ChatMessage.Builder()
                        .senderId(message.getSenderId())
                        .destinationId(message.getDestinationId())
                        .type(MessageType.TEXT)
                        .content(encryptedContent)
                        .encrypted(true)
                        .timestamp(message.getTimestamp())
                        .build();
            } else if (message.getType() == MessageType.FILE) {
                ByteBuffer encrypted = cryptoService.encrypt(message.getFileData());
                byte[] encryptedBytes = new byte[encrypted.remaining()];
                encrypted.get(encryptedBytes);

                return new ChatMessage.Builder()
                        .senderId(message.getSenderId())
                        .destinationId(message.getDestinationId())
                        .type(MessageType.FILE)
                        .fileName(message.getFileName())
                        .fileData(encryptedBytes)
                        .encrypted(true)
                        .timestamp(message.getTimestamp())
                        .build();
            }
        } catch (Exception e) {
            System.err.println("[RingTransport] Encryption failed: " + e.getMessage());
        }
        return message;
    }

    /**
     * Дешифрование сообщения.
     */
    private ChatMessage decryptMessage(ChatMessage message) {
        try {
            if (message.getType() == MessageType.TEXT) {
                byte[] encryptedBytes = java.util.Base64.getDecoder().decode(message.getContent());
                String decrypted = cryptoService.decryptToString(ByteBuffer.wrap(encryptedBytes));

                return new ChatMessage.Builder()
                        .senderId(message.getSenderId())
                        .destinationId(message.getDestinationId())
                        .type(MessageType.TEXT)
                        .content(decrypted)
                        .encrypted(false)
                        .timestamp(message.getTimestamp())
                        .build();
            } else if (message.getType() == MessageType.FILE) {
                byte[] decrypted = cryptoService.decryptToBytes(ByteBuffer.wrap(message.getFileData()));

                return new ChatMessage.Builder()
                        .senderId(message.getSenderId())
                        .destinationId(message.getDestinationId())
                        .type(MessageType.FILE)
                        .fileName(message.getFileName())
                        .fileData(decrypted)
                        .encrypted(false)
                        .timestamp(message.getTimestamp())
                        .build();
            }
        } catch (Exception e) {
            System.err.println("[RingTransport] Decryption failed: " + e.getMessage());
        }
        return message;
    }

    /**
     * Запуск сервера для входящих соединений.
     */
    private void startServer() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(SERVER_PORT));

        eventLoop.register(serverChannel, SelectionKey.OP_ACCEPT, new ChannelHandler() {
            @Override
            public void handleAccept(SelectionKey key) throws IOException {
                SocketChannel clientChannel = serverChannel.accept();
                if (clientChannel != null) {
                    clientChannel.configureBlocking(false);

                    String remoteAddr = clientChannel.getRemoteAddress().toString();
                    System.out.println("[RingTransport] Accepted connection from: " + remoteAddr);

                    ConnectionHandler handler = new ConnectionHandler(
                            clientChannel,
                            RingTransport.this::handleIncomingMessage,
                            remoteAddr
                    );

                    pendingConnections.put(clientChannel, handler);
                    eventLoop.register(clientChannel, SelectionKey.OP_READ, handler);
                }
            }

            @Override
            public void handleConnect(SelectionKey key) throws IOException {
            }

            @Override
            public void handleRead(SelectionKey key) throws IOException {
            }

            @Override
            public void handleWrite(SelectionKey key) throws IOException {
            }

            @Override
            public void handleError(SelectionKey key, Exception e) {
                System.err.println("[RingTransport] Server error: " + e.getMessage());
            }
        });
    }

    /**
     * Запуск менеджера соединений с соседями (через ScheduledExecutor).
     */
    private void startConnectionManager() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (!running) return;

            try {
                // Получаем правого соседа из RingState
                ringState.rightNeighbor().ifPresent(right -> {
                    if (rightConnection == null || !rightConnection.isOpen()) {
                        connectToNeighbor(right, true);
                    }
                });

            } catch (Exception e) {
                System.err.println("[RingTransport] Connection manager error: " + e.getMessage());
            }
        }, RECONNECT_DELAY_SEC, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
    }

    /**
     * Подключение к соседу.
     */
    private void connectToNeighbor(NodeInfo neighbor, boolean isRight) {
        eventLoop.execute(() -> {
            try {
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);

                InetSocketAddress address = new InetSocketAddress(
                        neighbor.ip().getHostAddress(),
                        neighbor.tcpPort()
                );

                channel.connect(address);

                String side = isRight ? "right" : "left";
                System.out.println("[RingTransport] Connecting to " + side + " neighbor: " + address);

                ConnectionHandler handler = new ConnectionHandler(
                        channel,
                        this::handleIncomingMessage,
                        side + "-" + neighbor.nodeId()
                );

                if (isRight) {
                    rightConnection = handler;
                } else {
                    leftConnection = handler;
                }

                eventLoop.register(channel, SelectionKey.OP_CONNECT, new ChannelHandler() {
                    @Override
                    public void handleAccept(SelectionKey key) throws IOException {
                    }

                    @Override
                    public void handleConnect(SelectionKey key) throws IOException {
                        if (channel.finishConnect()) {
                            System.out.println("[RingTransport] Connected to " + side + " neighbor");
                            eventLoop.register(channel, SelectionKey.OP_READ, handler);
                        }
                    }

                    @Override
                    public void handleRead(SelectionKey key) throws IOException {
                    }

                    @Override
                    public void handleWrite(SelectionKey key) throws IOException {
                    }

                    @Override
                    public void handleError(SelectionKey key, Exception e) {
                        System.err.println("[RingTransport] Connection error to " + side + ": " + e.getMessage());
                        if (isRight) {
                            rightConnection = null;
                        } else {
                            leftConnection = null;
                        }
                    }
                });

            } catch (IOException e) {
                System.err.println("[RingTransport] Connection failed: " + e.getMessage());
            }
        });
    }

    /**
     * Запуск worker для retry неотправленных сообщений (через ScheduledExecutor).
     */
    private void startRetryWorker() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (!running) return;

            try {
                var pending = outboxStore.getPendingMessages();

                for (var outbound : pending) {
                    boolean sent = trySendMessage(outbound.message());

                    if (sent) {
                        outboxStore.removeMessage(outbound.id());
                    } else {
                        outboxStore.incrementRetry(outbound.id());
                    }
                }

                outboxStore.cleanupOldMessages();

            } catch (SQLException e) {
                System.err.println("[RingTransport] Retry worker error: " + e.getMessage());
            }
        }, RETRY_DELAY_SEC, RETRY_DELAY_SEC, TimeUnit.SECONDS);
    }

    /**
     * Получение состояния соединений.
     */
    public String getConnectionStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Left: ").append(leftConnection != null && leftConnection.isOpen() ? "✓" : "✗").append("\n");
        sb.append("Right: ").append(rightConnection != null && rightConnection.isOpen() ? "✓" : "✗");
        return sb.toString();
    }
}