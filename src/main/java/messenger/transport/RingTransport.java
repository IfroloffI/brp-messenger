package messenger.transport;

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

import messenger.crypto.CryptoService;
import messenger.crypto.DecryptedMessage;
import messenger.crypto.EncryptedMessage;
import messenger.crypto.KeyPairManager;
import messenger.protocol.ChatMessage;
import messenger.protocol.DeliveryStatus;
import messenger.protocol.MessageType;
import messenger.protocol.SignatureStatus;
import messenger.ring.NodeInfo;
import messenger.ring.RingState;

/**
 * Транспорт для кольцевой топологии с E2E шифрованием и цифровой подписью
 */
public class RingTransport implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(RingTransport.class.getName());

    private static final int TCP_PORT = 9877;
    private static final int BUFFER_SIZE = 65536; // 64KB для больших сообщений
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final long myNodeId;
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
        this.myNodeId = myNodeId;
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
        serverChannel.bind(new InetSocketAddress(TCP_PORT));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;

        // Event loop в отдельном потоке
        Thread eventLoopThread = new Thread(this::eventLoop, "RingTransport-EventLoop");
        eventLoopThread.setDaemon(true);
        eventLoopThread.start();

        logger.info("Ring transport started on TCP port " + TCP_PORT);
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

            readBuffers.put(channel, ByteBuffer.allocate(BUFFER_SIZE));
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
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    /**
     * Обработка чтения данных
     */
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = readBuffers.get(channel);

        if (buffer == null) {
            buffer = ByteBuffer.allocate(BUFFER_SIZE);
            readBuffers.put(channel, buffer);
        }

        int bytesRead = channel.read(buffer);

        if (bytesRead == -1) {
            // Соединение закрыто
            logger.info("Connection closed: " + channel.getRemoteAddress());
            cleanupChannel(channel);
            key.cancel();
            channel.close();
            return;
        }

        if (bytesRead > 0) {
            buffer.flip();

            // Пытаемся прочитать сообщения из буфера
            while (buffer.remaining() >= 4) {
                buffer.mark();
                int messageLength = buffer.getInt();

                if (messageLength < 0 || messageLength > BUFFER_SIZE) {
                    logger.warning("Invalid message length: " + messageLength);
                    cleanupChannel(channel);
                    key.cancel();
                    channel.close();
                    return;
                }

                if (buffer.remaining() >= messageLength) {
                    // Полное сообщение получено
                    byte[] messageData = new byte[messageLength];
                    buffer.get(messageData);

                    try {
                        ChatMessage message = ChatMessage.deserialize(messageData);
                        handleIncomingMessage(message);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to deserialize message", e);
                    }
                } else {
                    // Неполное сообщение, возвращаемся назад
                    buffer.reset();
                    break;
                }
            }

            // Компактим буфер для следующего чтения
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
            channel.write(buffer);

            if (!buffer.hasRemaining()) {
                queue.poll();
            }
        }

        if (queue.isEmpty()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    /**
     * Отправка текстового сообщения с шифрованием
     */
    public void sendTextMessage(long recipientId, String text) {
        try {
            // Получаем публичный ключ шифрования получателя
            PublicKey recipientEncryptionKey = ringState.getEncryptionPublicKey(recipientId);

            if (recipientEncryptionKey == null) {
                logger.warning("Cannot send message to " + recipientId + ": no encryption key available");
                return;
            }

            // Шифруем контент и подписываем
            byte[] plainContent = text.getBytes(StandardCharsets.UTF_8);
            EncryptedMessage encrypted = cryptoService.encryptAndSign(plainContent, recipientEncryptionKey);

            // Получаем свой публичный ключ подписи для передачи
            byte[] mySigningKeyBytes = keyPairManager.publicKeyToBytes(
                    cryptoService.getMySigningPublicKey()
            );

            // Создаём сообщение
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

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send text message", e);
        }
    }

    /**
     * Отправка файла с шифрованием
     */
    public void sendFile(long recipientId, String fileName, byte[] fileData) {
        try {
            PublicKey recipientEncryptionKey = ringState.getEncryptionPublicKey(recipientId);

            if (recipientEncryptionKey == null) {
                logger.warning("Cannot send file to " + recipientId + ": no encryption key available");
                return;
            }

            // Шифруем содержимое файла
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

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send file", e);
        }
    }

    /**
     * Внутренняя отправка сообщения в кольцо
     */
    public void sendMessage(ChatMessage message) throws IOException {
        Long rightNeighbor = ringState.getRightNeighbor();

        if (rightNeighbor == null) {
            logger.warning("No right neighbor, cannot send message (ring not formed)");
            return;
        }

        SocketChannel channel = getOrCreateConnection(rightNeighbor);
        if (channel == null) {
            logger.warning("Cannot establish connection to right neighbor " + rightNeighbor);
            return;
        }

        // Сериализуем сообщение
        byte[] data = message.serialize();

        // Формируем буфер: [length:4][data:variable]
        ByteBuffer buffer = ByteBuffer.allocate(4 + data.length);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.flip();

        // Добавляем в очередь на отправку
        Queue<ByteBuffer> queue = writeQueues.get(channel);
        if (queue != null) {
            queue.offer(buffer);

            // Регистрируем интерес к записи
            SelectionKey key = channel.keyFor(selector);
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                selector.wakeup();
            }
        }
    }

    /**
     * Обработка входящего сообщения с расшифровкой и верификацией
     */
    private void handleIncomingMessage(ChatMessage message) {
        try {
            // Проверка: это сообщение для меня?
            if (message.getRecipientId() == myNodeId) {
                logger.info(String.format("Received message for me from %d (msg_id=%d, type=%s)",
                        message.getSenderId(), message.getMessageId(), message.getType()));

                // Восстанавливаем публичный ключ подписи отправителя
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
                    logger.warning("Message from " + message.getSenderId() + " has no valid signing key");
                    message.setSignatureStatus(SignatureStatus.MISSING);
                    return;
                }

                // Расшифровываем и верифицируем подпись
                DecryptedMessage decrypted = cryptoService.decryptAndVerify(
                        message.getEncryptedContent(),
                        message.getEncryptedSessionKey(),
                        message.getSignature(),
                        senderSigningKey
                );

                // Устанавливаем статус подписи
                if (decrypted.signatureValid()) {
                    message.setSignatureStatus(SignatureStatus.VERIFIED);
                    logger.info("✓ Signature VERIFIED for message from " + message.getSenderId());
                } else {
                    message.setSignatureStatus(SignatureStatus.INVALID);
                    logger.warning("✗ Signature INVALID for message from " + message.getSenderId());
                }

                message.setDeliveryStatus(DeliveryStatus.DELIVERED);

                // Обработка расшифрованного контента
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

                // Вызываем callback если установлен
                if (messageCallback != null) {
                    messageCallback.onMessageReceived(message, decrypted.content());
                }

            } else {
                // Не для меня - пересылаем дальше по кольцу (маршрутизация)
                if (message.getTtl() > 0) {
                    logger.fine(String.format("Forwarding message (msg_id=%d) to %d (ttl=%d)",
                            message.getMessageId(), message.getRecipientId(), message.getTtl()));
                    sendMessage(message.withDecrementedTtl());
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
     * Получить или создать соединение с узлом
     */
    private SocketChannel getOrCreateConnection(long nodeId) {
        SocketChannel existing = connections.get(nodeId);
        if (existing != null && existing.isConnected()) {
            return existing;
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

            // Регистрируем для завершения подключения
            channel.register(selector, SelectionKey.OP_CONNECT);
            selector.wakeup();

            // Ждём подключения (блокирующий режим для упрощения)
            long startTime = System.currentTimeMillis();
            while (!channel.finishConnect()) {
                if (System.currentTimeMillis() - startTime > CONNECT_TIMEOUT_MS) {
                    logger.warning("Connection timeout to node " + nodeId);
                    channel.close();
                    return null;
                }
                Thread.sleep(10);
            }

            // Инициализируем буферы
            readBuffers.put(channel, ByteBuffer.allocate(BUFFER_SIZE));
            writeQueues.put(channel, new ArrayDeque<>());

            connections.put(nodeId, channel);
            logger.info("Connected to node " + nodeId + " at " + node.address() + ":" + node.port());

            return channel;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to connect to node " + nodeId, e);
            return null;
        }
    }

    /**
     * Очистка ресурсов канала
     */
    private void cleanupChannel(SocketChannel channel) {
        readBuffers.remove(channel);
        writeQueues.remove(channel);

        // Удаляем из connections
        connections.entrySet().removeIf(entry -> entry.getValue() == channel);
    }

    /**
     * Закрытие транспорта
     */
    @Override
    public void close() {
        running = false;

        // Закрываем все соединения
        connections.values().forEach(ch -> {
            try {
                ch.close();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error closing connection", e);
            }
        });
        connections.clear();

        // Закрываем server channel и selector
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

        // Очищаем буферы
        readBuffers.clear();
        writeQueues.clear();

        logger.info("Ring transport stopped");
    }

    /**
     * Callback интерфейс для обработки полученных сообщений
     */
    @FunctionalInterface
    public interface MessageReceivedCallback {
        /**
         * Вызывается при получении и расшифровке сообщения
         *
         * @param message          полученное сообщение (с метаданными и статусами)
         * @param decryptedContent расшифрованное содержимое
         */
        void onMessageReceived(ChatMessage message, byte[] decryptedContent);
    }
}