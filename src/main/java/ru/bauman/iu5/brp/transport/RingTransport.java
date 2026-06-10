package ru.bauman.iu5.brp.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import ru.bauman.iu5.brp.crypto.CryptoService;
import ru.bauman.iu5.brp.crypto.DecryptedMessage;
import ru.bauman.iu5.brp.crypto.EncryptedMessage;
import ru.bauman.iu5.brp.crypto.KeyPairManager;
import ru.bauman.iu5.brp.protocol.ChatMessage;
import ru.bauman.iu5.brp.protocol.DeliveryStatus;
import ru.bauman.iu5.brp.protocol.MessageType;
import ru.bauman.iu5.brp.protocol.SignatureStatus;
import ru.bauman.iu5.brp.ring.NodeInfo;
import ru.bauman.iu5.brp.ring.RingState;

/**
 * Транспорт для кольцевой топологии с E2E шифрованием и цифровой подписью
 */
public class RingTransport implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(RingTransport.class.getName());

    private static final int DEFAULT_TCP_PORT = 9877;
    // Initial read-buffer size. Grows dynamically to accommodate large frames.
    private static final int INITIAL_BUFFER_SIZE = 65536;
    // Hard upper bound: reject frames larger than this (malformed/attack protection)
    private static final int MAX_FRAME_SIZE = 32 * 1024 * 1024; // 32 MB
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final long myNodeId;
    private final int tcpPort;
    private final RingState ringState;
    private final CryptoService cryptoService;
    private final KeyPairManager keyPairManager;

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private volatile boolean running;

    // Соединения с узлами
    private final Map<Long, SocketChannel> connections = new ConcurrentHashMap<>();
    private final Map<SocketChannel, ByteBuffer> readBuffers = new ConcurrentHashMap<>();
    private final Map<SocketChannel, Queue<ByteBuffer>> writeQueues = new ConcurrentHashMap<>();

    // Генератор ID сообщений
    private final AtomicLong messageIdGenerator = new AtomicLong(System.currentTimeMillis());

    // Callback для обработки полученных сообщений
    private MessageReceivedCallback messageCallback;

    public RingTransport(long myNodeId, RingState ringState, CryptoService cryptoService) {
        this(myNodeId, DEFAULT_TCP_PORT, ringState, cryptoService);
    }

    public RingTransport(long myNodeId, int tcpPort, RingState ringState, CryptoService cryptoService) {
        this.myNodeId = myNodeId;
        this.tcpPort = tcpPort;
        this.ringState = ringState;
        this.cryptoService = cryptoService;
        this.keyPairManager = new KeyPairManager();
    }

    /**
     * Установить callback для получения сообщений
     */
    public void setMessageReceivedCallback(MessageReceivedCallback callback) {
        this.messageCallback = callback;
    }

    /**
     * Запуск транспорта
     */
    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(tcpPort));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;

        // Event loop в отдельном потоке
        Thread eventLoopThread = new Thread(this::eventLoop, "RingTransport-EventLoop");
        eventLoopThread.setDaemon(true);
        eventLoopThread.start();

        logger.info("Ring transport started on TCP port " + tcpPort);
    }

    /**
     * Event loop для обработки NIO событий
     */
    private void eventLoop() {
        while (running) {
            try {
                selector.select(1000);

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isConnectable()) {
                            handleConnect(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error handling key", e);
                        key.cancel();
                        try {
                            key.channel().close();
                        } catch (IOException ex) {
                            // Ignore
                        }
                    }
                }

            } catch (Exception e) {
                if (running) {
                    logger.log(Level.SEVERE, "Error in event loop", e);
                }
            }
        }
    }

    /**
     * Обработка входящего соединения
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel channel = serverChannel.accept();

        if (channel != null) {
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ);

            readBuffers.put(channel, ByteBuffer.allocate(INITIAL_BUFFER_SIZE));
            writeQueues.put(channel, new ArrayDeque<>());

            logger.info("Accepted connection from " + channel.getRemoteAddress());
        }
    }

    /**
     * Обработка завершения подключения
     */
    private void handleConnect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();

        if (channel.finishConnect()) {
            logger.info("Connected to " + channel.getRemoteAddress());
            Queue<ByteBuffer> queue = writeQueues.get(channel);
            int interestOps = SelectionKey.OP_READ;
            if (queue != null && !queue.isEmpty()) {
                interestOps |= SelectionKey.OP_WRITE;
            }
            key.interestOps(interestOps);
        }
    }

    /**
     * Обработка чтения данных.
     * Буфер растёт динамически когда фрейм не помещается в текущий.
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = readBuffers.get(channel);

        if (buffer == null) {
            buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
            readBuffers.put(channel, buffer);
        }

        int bytesRead = channel.read(buffer);

        if (bytesRead == -1) {
            logger.info("Connection closed: " + channel.getRemoteAddress());
            cleanupChannel(channel);
            key.cancel();
            channel.close();
            return;
        }

        if (bytesRead > 0) {
            logger.fine("Read " + bytesRead + " bytes from " + channel.getRemoteAddress());
            buffer.flip();

            while (buffer.remaining() >= 4) {
                buffer.mark();
                int messageLength = buffer.getInt();

                if (messageLength < 0 || messageLength > MAX_FRAME_SIZE) {
                    logger.warning("Invalid message length: " + messageLength + " from " + channel.getRemoteAddress() + " — closing channel");
                    cleanupChannel(channel);
                    key.cancel();
                    channel.close();
                    return;
                }

                // If the frame doesn't fit in the current buffer, grow it
                if (messageLength > buffer.capacity() - 4) {
                    int needed = messageLength + 4;
                    logger.info("Growing read buffer from " + buffer.capacity() + " to " + needed + " bytes for frame from " + channel.getRemoteAddress());
                    ByteBuffer bigger = ByteBuffer.allocate(needed);
                    // reset to before the length prefix we already read
                    buffer.reset();
                    bigger.put(buffer);
                    buffer = bigger;
                    readBuffers.put(channel, buffer);
                    buffer.flip();
                    // re-read length prefix from new buffer
                    buffer.mark();
                    messageLength = buffer.getInt();
                }

                if (buffer.remaining() >= messageLength) {
                    byte[] messageData = new byte[messageLength];
                    buffer.get(messageData);
                    logger.fine("Assembled frame: " + messageLength + " bytes from " + channel.getRemoteAddress());

                    try {
                        ChatMessage message = ChatMessage.deserialize(messageData);
                        handleIncomingMessage(channel, message);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to deserialize message from " + channel.getRemoteAddress(), e);
                    }
                } else {
                    // Incomplete frame — wait for more data
                    buffer.reset();
                    break;
                }
            }

            buffer.compact();
        }
    }

    /**
     * Обработка записи данных
     */
    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Queue<ByteBuffer> queue = writeQueues.get(channel);

        if (queue == null || queue.isEmpty()) {
            key.interestOps(SelectionKey.OP_READ);
            return;
        }

        ByteBuffer buffer = queue.peek();
        if (buffer != null) {
            try {
                channel.write(buffer);
            } catch (IOException e) {
                cleanupChannel(channel);
                key.cancel();
                try { channel.close(); } catch (IOException ignored) {}
                throw e;
            }

            if (!buffer.hasRemaining()) {
                queue.poll();
            }
        }

        if (queue.isEmpty()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    /**
     * Отправка текстового сообщения с шифрованием.
     * Бросает TransportException если отправка невозможна.
     */
    public void sendTextMessage(long recipientId, String text) throws TransportException {
        PublicKey recipientEncryptionKey = ringState.getEncryptionPublicKey(recipientId);

        if (recipientEncryptionKey == null) {
            throw new TransportException("No encryption key for node " + recipientId + " — discovery may not have completed yet");
        }

        try {
            byte[] plainContent = text.getBytes(StandardCharsets.UTF_8);
            EncryptedMessage encrypted = cryptoService.encryptAndSign(plainContent, recipientEncryptionKey);

            byte[] mySigningKeyBytes = keyPairManager.publicKeyToBytes(
                    cryptoService.getMySigningPublicKey()
            );

            ChatMessage message = new ChatMessage.Builder()
                    .messageId(messageIdGenerator.incrementAndGet())
                    .senderId(myNodeId)
                    .recipientId(recipientId)
                    .type(MessageType.TEXT)
                    .encryptedContent(encrypted.encryptedContent())
                    .encryptedSessionKey(encrypted.encryptedSessionKey())
                    .signature(encrypted.signature())
                    .senderPublicSigningKey(mySigningKeyBytes)
                    .deliveryStatus(DeliveryStatus.SENT)
                    .build();

            sendMessage(message);

            logger.info(String.format("Sent encrypted text message to %d (msg_id=%d, sig=%s)",
                    recipientId, message.getMessageId(),
                    encrypted.signature() != null ? "yes" : "no"));

        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("Failed to send text message to " + recipientId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Отправка файла с шифрованием.
     * Бросает TransportException если отправка невозможна.
     */
    public void sendFile(long recipientId, String fileName, byte[] fileData) throws TransportException {
        PublicKey recipientEncryptionKey = ringState.getEncryptionPublicKey(recipientId);

        if (recipientEncryptionKey == null) {
            throw new TransportException("No encryption key for node " + recipientId + " — discovery may not have completed yet");
        }

        try {
            EncryptedMessage encrypted = cryptoService.encryptAndSign(fileData, recipientEncryptionKey);

            byte[] mySigningKeyBytes = keyPairManager.publicKeyToBytes(
                    cryptoService.getMySigningPublicKey()
            );

            ChatMessage message = new ChatMessage.Builder()
                    .messageId(messageIdGenerator.incrementAndGet())
                    .senderId(myNodeId)
                    .recipientId(recipientId)
                    .type(MessageType.FILE)
                    .fileName(fileName)
                    .encryptedContent(encrypted.encryptedContent())
                    .encryptedSessionKey(encrypted.encryptedSessionKey())
                    .signature(encrypted.signature())
                    .senderPublicSigningKey(mySigningKeyBytes)
                    .deliveryStatus(DeliveryStatus.SENT)
                    .build();

            sendMessage(message);

            logger.info(String.format("Sent encrypted file to %d: %s (%d bytes, msg_id=%d)",
                    recipientId, fileName, fileData.length, message.getMessageId()));

        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("Failed to send file '" + fileName + "' to " + recipientId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Внутренняя отправка сообщения в кольцо.
     * Пробует правого соседа, затем левого, затем любую известную ноду.
     */
    public void sendMessage(ChatMessage message) throws TransportException, IOException {
        Long rightNeighbor = ringState.getRightNeighbor();
        Long leftNeighbor = ringState.getLeftNeighbor();

        if (rightNeighbor == null && leftNeighbor == null) {
            throw new TransportException("No right neighbor — ring is not yet formed");
        }

        SocketChannel channel = null;
        java.util.Set<Long> tried = new java.util.LinkedHashSet<>();

        if (rightNeighbor != null) {
            channel = getOrCreateConnection(rightNeighbor);
            tried.add(rightNeighbor);
            if (channel == null) {
                logger.warning("Right neighbor " + rightNeighbor + " unreachable, trying left neighbor");
            }
        }

        if (channel == null && leftNeighbor != null && !tried.contains(leftNeighbor)) {
            channel = getOrCreateConnection(leftNeighbor);
            tried.add(leftNeighbor);
            if (channel == null) {
                logger.warning("Left neighbor " + leftNeighbor + " unreachable, trying other known nodes");
            }
        }

        if (channel == null) {
            for (ru.bauman.iu5.brp.ring.NodeInfo node : ringState.getAllNodes()) {
                long nodeId = node.nodeId();
                if (nodeId == myNodeId || tried.contains(nodeId)) continue;
                channel = getOrCreateConnection(nodeId);
                tried.add(nodeId);
                if (channel != null) {
                    logger.info("Fallback: routing via node " + nodeId);
                    break;
                }
            }
        }

        if (channel == null) {
            throw new TransportException("Cannot deliver message: tried nodes " + tried + " — all unreachable");
        }

        byte[] data = message.serialize();

        ByteBuffer buffer = ByteBuffer.allocate(4 + data.length);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        Queue<ByteBuffer> queue = writeQueues.get(channel);
        if (queue != null) {
            queue.offer(buffer);

            SelectionKey key = channel.keyFor(selector);
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                selector.wakeup();
            }
        } else {
            throw new TransportException("Write queue missing for channel to " + rightNeighbor);
        }
    }

    /**
     * Обработка входящего сообщения с расшифровкой и верификацией.
     * channel передаётся для регистрации входящего соединения по HELLO.
     */
    private void handleIncomingMessage(SocketChannel channel, ChatMessage message) {
        try {
            // HELLO — служебный фрейм идентификации. Регистрируем входящий канал в connections,
            // чтобы при отправке этому узлу переиспользовать уже открытое соединение (обходит
            // асимметричные firewall-правила, когда входящие TCP заблокированы у удалённой ноды).
            if (message.getType() == MessageType.HELLO) {
                long senderId = message.getSenderId();
                SocketChannel prev = connections.get(senderId);
                if (prev == null || !prev.isOpen() || !prev.isConnected()) {
                    connections.put(senderId, channel);
                    logger.info("Registered accepted connection from node " + senderId);
                }
                return;
            }

            if (message.getRecipientId() == myNodeId) {
                logger.info(String.format("Received message for me from %d (msg_id=%d, type=%s)",
                        message.getSenderId(), message.getMessageId(), message.getType()));

                PublicKey senderSigningKey = null;
                if (message.getSenderPublicSigningKey() != null) {
                    try {
                        senderSigningKey = keyPairManager.bytesToPublicKey(
                                message.getSenderPublicSigningKey()
                        );
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to parse sender signing key", e);
                    }
                }

                if (senderSigningKey == null) {
                    logger.warning("Message from " + message.getSenderId() + " has no valid signing key — dropping");
                    message.setSignatureStatus(SignatureStatus.MISSING);
                    return;
                }

                DecryptedMessage decrypted = cryptoService.decryptAndVerify(
                        message.getEncryptedContent(),
                        message.getEncryptedSessionKey(),
                        message.getSignature(),
                        senderSigningKey
                );

                if (decrypted.signatureValid()) {
                    message.setSignatureStatus(SignatureStatus.VERIFIED);
                    logger.info("✓ Signature VERIFIED for message from " + message.getSenderId());
                } else {
                    message.setSignatureStatus(SignatureStatus.INVALID);
                    logger.warning("✗ Signature INVALID for message from " + message.getSenderId());
                }

                message.setDeliveryStatus(DeliveryStatus.DELIVERED);

                if (message.getType() == MessageType.TEXT) {
                    String text = new String(decrypted.content(), StandardCharsets.UTF_8);
                    logger.info(String.format("┌─────────────────────────────────────"));
                    logger.info(String.format("│ From: node %d", message.getSenderId()));
                    logger.info(String.format("│ Text: %s", text));
                    logger.info(String.format("│ Signature: %s", message.getSignatureStatus()));
                    logger.info(String.format("└─────────────────────────────────────"));

                } else if (message.getType() == MessageType.FILE) {
                    logger.info(String.format("┌─────────────────────────────────────"));
                    logger.info(String.format("│ From: node %d", message.getSenderId()));
                    logger.info(String.format("│ File: %s (%d bytes)",
                            message.getFileName(), decrypted.content().length));
                    logger.info(String.format("│ Signature: %s", message.getSignatureStatus()));
                    logger.info(String.format("└─────────────────────────────────────"));
                }

                if (messageCallback != null) {
                    messageCallback.onMessageReceived(message, decrypted.content());
                }

            } else {
                // Not for me — forward around the ring
                if (message.getTtl() > 0) {
                    logger.fine(String.format("Forwarding message (msg_id=%d) to %d (ttl=%d)",
                            message.getMessageId(), message.getRecipientId(), message.getTtl()));
                    try {
                        sendMessage(message.withDecrementedTtl());
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to forward message " + message.getMessageId(), e);
                    }
                } else {
                    logger.warning(String.format("Message TTL expired (msg_id=%d), dropping",
                            message.getMessageId()));
                }
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to handle incoming message", e);
        }
    }

    /**
     * Получить или создать соединение с узлом.
     * Проверяет живость существующего канала перед возвратом.
     */
    private SocketChannel getOrCreateConnection(long nodeId) {
        SocketChannel existing = connections.get(nodeId);
        if (existing != null) {
            if (existing.isOpen() && existing.isConnected() && !existing.socket().isClosed()) {
                return existing;
            }
            // Stale channel — clean up and reconnect
            logger.info("Stale connection to node " + nodeId + " detected, reconnecting");
            cleanupChannel(existing);
            try {
                existing.close();
            } catch (IOException e) {
                // ignore
            }
        }

        NodeInfo node = ringState.getNode(nodeId);
        if (node == null) {
            logger.warning("No node info for " + nodeId);
            return null;
        }

        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(node.address(), node.port()));

            channel.register(selector, SelectionKey.OP_CONNECT);
            selector.wakeup();

            long startTime = System.currentTimeMillis();
            while (!channel.finishConnect()) {
                if (System.currentTimeMillis() - startTime > CONNECT_TIMEOUT_MS) {
                    logger.warning("Connection timeout to node " + nodeId
                            + " (" + node.address().getHostAddress() + ":" + node.port() + ")");
                    channel.close();
                    return null;
                }
                Thread.sleep(10);
            }

            readBuffers.put(channel, ByteBuffer.allocate(INITIAL_BUFFER_SIZE));
            writeQueues.put(channel, new ArrayDeque<>());

            SelectionKey key = channel.keyFor(selector);
            if (key != null && key.isValid()) {
                key.interestOps(SelectionKey.OP_READ);
            }

            connections.put(nodeId, channel);
            logger.info("Connected to node " + nodeId + " at " + node.address() + ":" + node.port());

            // Отправляем HELLO чтобы принимающая сторона могла зарегистрировать
            // этот канал и использовать его для обратной отправки нам.
            sendHello(channel);

            return channel;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to connect to node " + nodeId, e);
            return null;
        }
    }

    /**
     * Отправляет HELLO-фрейм по уже подключённому каналу (synchronous, блокирующий вызов только для маленького фрейма).
     */
    private void sendHello(SocketChannel channel) {
        try {
            ChatMessage hello = new ChatMessage.Builder()
                    .messageId(messageIdGenerator.incrementAndGet())
                    .senderId(myNodeId)
                    .recipientId(0) // broadcast/служебное, recipientId не используется
                    .type(MessageType.HELLO)
                    .encryptedContent(new byte[0])
                    .build();
            byte[] data = hello.serialize();
            ByteBuffer buf = ByteBuffer.allocate(4 + data.length);
            buf.putInt(data.length);
            buf.put(data);
            buf.flip();
            Queue<ByteBuffer> queue = writeQueues.get(channel);
            if (queue != null) {
                queue.offer(buf);
                SelectionKey key = channel.keyFor(selector);
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    selector.wakeup();
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send HELLO", e);
        }
    }

    /**
     * Очистка ресурсов канала
     */
    private void cleanupChannel(SocketChannel channel) {
        readBuffers.remove(channel);
        writeQueues.remove(channel);
        connections.entrySet().removeIf(entry -> entry.getValue() == channel);
    }

    /**
     * Закрытие транспорта
     */
    @Override
    public void close() {
        running = false;

        connections.values().forEach(ch -> {
            try {
                ch.close();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error closing connection", e);
            }
        });
        connections.clear();

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error closing transport", e);
        }

        readBuffers.clear();
        writeQueues.clear();

        logger.info("Ring transport stopped");
    }

    /**
     * Callback интерфейс для обработки полученных сообщений
     */
    @FunctionalInterface
    public interface MessageReceivedCallback {
        void onMessageReceived(ChatMessage message, byte[] decryptedContent);
    }
}
