package ru.bauman.iu5.brp.storage;

import ru.bauman.iu5.brp.protocol.ChatMessage;
import ru.bauman.iu5.brp.protocol.DeliveryStatus;
import ru.bauman.iu5.brp.protocol.MessageType;
import ru.bauman.iu5.brp.protocol.SignatureStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Персистентное хранилище исходящих сообщений на базе H2.
 * <p>
 * Поддерживает:
 * - Retry-логику с exponential backoff
 * - Хранение зашифрованных данных
 * - Автоматическую очистку старых сообщений
 * - Tracking статусов доставки
 */
public final class OutboxStore implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(OutboxStore.class.getName());

    private static final String DB_DIR = System.getProperty("user.home") + "/.messenger/outbox";
    private static final String DB_URL = "jdbc:h2:" + DB_DIR + "/outbox;AUTO_SERVER=TRUE";
    private static final long CLEANUP_THRESHOLD_MS = 24 * 60 * 60 * 1000; // 24 часа

    private final Connection connection;

    public OutboxStore() throws SQLException {
        ensureDirectoryExists();
        this.connection = DriverManager.getConnection(DB_URL);
        initSchema();
        logger.info("OutboxStore initialized at: " + DB_DIR);
    }

    private void ensureDirectoryExists() {
        try {
            Path dir = Paths.get(DB_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                logger.info("Created outbox directory: " + dir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create outbox directory", e);
        }
    }

    private void initSchema() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS outbox (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    message_id BIGINT NOT NULL,
                    sender_id BIGINT NOT NULL,
                    recipient_id BIGINT NOT NULL,
                    message_type VARCHAR(20) NOT NULL,
                    encrypted_content BLOB,
                    file_name VARCHAR(512),
                    encrypted_session_key BLOB,
                    signature BLOB,
                    sender_public_signing_key BLOB,
                    signature_status VARCHAR(20),
                    delivery_status VARCHAR(20) NOT NULL,
                    ttl INT NOT NULL,
                    timestamp BIGINT NOT NULL,
                    retry_count INT DEFAULT 0,
                    next_retry_time BIGINT NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            logger.info("Schema initialized");
        }

        // Индексы для производительности
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_next_retry ON outbox(next_retry_time)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_created_at ON outbox(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_status ON outbox(delivery_status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_message_id ON outbox(message_id)");
            logger.fine("Indexes created");
        }
    }

    /**
     * Добавление сообщения в очередь
     */
    public long addMessage(ChatMessage message) throws SQLException {
        String sql = """
                INSERT INTO outbox (
                    message_id, sender_id, recipient_id, message_type,
                    encrypted_content, file_name, encrypted_session_key,
                    signature, sender_public_signing_key, signature_status,
                    delivery_status, ttl, timestamp, retry_count,
                    next_retry_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """;

        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, message.getMessageId());
            stmt.setLong(2, message.getSenderId());
            stmt.setLong(3, message.getRecipientId());
            stmt.setString(4, message.getType().name());
            stmt.setBytes(5, message.getEncryptedContent());
            stmt.setString(6, message.getFileName());
            stmt.setBytes(7, message.getEncryptedSessionKey());
            stmt.setBytes(8, message.getSignature());
            stmt.setBytes(9, message.getSenderPublicSigningKey());
            stmt.setString(10, message.getSignatureStatus() != null
                    ? message.getSignatureStatus().name() : null);
            stmt.setString(11, message.getDeliveryStatus().name());
            stmt.setInt(12, message.getTtl());
            stmt.setLong(13, message.getTimestamp());

            long now = System.currentTimeMillis();
            stmt.setLong(14, now); // next_retry_time = сразу
            stmt.setLong(15, now); // created_at

            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    logger.info(String.format("Added message to outbox: id=%d, msgId=%d, type=%s",
                            id, message.getMessageId(), message.getType()));
                    return id;
                }
            }
        }

        throw new SQLException("Failed to get generated key");
    }

    /**
     * Получение сообщений, готовых для retry
     */
    public List<OutboundMessage> getPendingMessages() throws SQLException {
        String sql = """
                SELECT * FROM outbox 
                WHERE next_retry_time <= ? 
                  AND retry_count < ?
                ORDER BY created_at ASC
                LIMIT 100
                """;

        List<OutboundMessage> result = new ArrayList<>();
        long now = System.currentTimeMillis();

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, now);
            stmt.setInt(2, OutboundMessage.MAX_RETRIES);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        }

        logger.fine("Retrieved " + result.size() + " pending messages");
        return result;
    }

    /**
     * Удаление сообщения из очереди (успешно отправлено)
     */
    public void removeMessage(long id) throws SQLException {
        String sql = "DELETE FROM outbox WHERE id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                logger.fine("Removed message from outbox: id=" + id);
            }
        }
    }

    /**
     * Обновление статуса доставки
     */
    public void updateDeliveryStatus(long messageId, DeliveryStatus status) throws SQLException {
        String sql = "UPDATE outbox SET delivery_status = ? WHERE message_id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setLong(2, messageId);
            stmt.executeUpdate();

            logger.fine(String.format("Updated delivery status: msgId=%d, status=%s",
                    messageId, status));
        }
    }

    /**
     * Увеличение счётчика попыток с exponential backoff
     */
    public void incrementRetry(long id) throws SQLException {
        String sql = """
                UPDATE outbox 
                SET retry_count = retry_count + 1,
                    next_retry_time = ?
                WHERE id = ?
                """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            // Получаем текущий retry count
            int currentRetry = getRetryCount(id);
            long nextRetry = OutboundMessage.calculateNextRetryTime(currentRetry + 1);

            stmt.setLong(1, nextRetry);
            stmt.setLong(2, id);
            stmt.executeUpdate();

            logger.fine(String.format("Incremented retry: id=%d, retry=%d, nextRetry=%d",
                    id, currentRetry + 1, nextRetry));
        }
    }

    /**
     * Получение текущего retry count
     */
    private int getRetryCount(long id) throws SQLException {
        String sql = "SELECT retry_count FROM outbox WHERE id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        return 0;
    }

    /**
     * Очистка старых и истёкших сообщений
     */
    public int cleanup() throws SQLException {
        long cutoff = System.currentTimeMillis() - CLEANUP_THRESHOLD_MS;

        String sql = """
                DELETE FROM outbox 
                WHERE created_at < ? 
                   OR retry_count >= ?
                """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, cutoff);
            stmt.setInt(2, OutboundMessage.MAX_RETRIES);

            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                logger.info("Cleaned up " + deleted + " old/expired messages");
            }

            return deleted;
        }
    }

    /**
     * Получение размера очереди
     */
    public int getQueueSize() throws SQLException {
        String sql = "SELECT COUNT(*) FROM outbox";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
        }

        return 0;
    }

    /**
     * Получение статистики очереди
     */
    public QueueStats getStats() throws SQLException {
        String sql = """
                SELECT 
                    COUNT(*) as total,
                    SUM(CASE WHEN retry_count = 0 THEN 1 ELSE 0 END) as fresh,
                    SUM(CASE WHEN retry_count > 0 THEN 1 ELSE 0 END) as retrying,
                    AVG(retry_count) as avg_retries
                FROM outbox
                """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return new QueueStats(
                        rs.getInt("total"),
                        rs.getInt("fresh"),
                        rs.getInt("retrying"),
                        rs.getDouble("avg_retries")
                );
            }
        }

        return new QueueStats(0, 0, 0, 0.0);
    }

    /**
     * Маппинг строки БД в OutboundMessage
     */
    private OutboundMessage mapRow(ResultSet rs) throws SQLException {
        ChatMessage.Builder builder = new ChatMessage.Builder()
                .messageId(rs.getLong("message_id"))
                .senderId(rs.getLong("sender_id"))
                .recipientId(rs.getLong("recipient_id"))
                .type(MessageType.valueOf(rs.getString("message_type")))
                .timestamp(rs.getLong("timestamp"))
                .ttl(rs.getInt("ttl"))
                .deliveryStatus(DeliveryStatus.valueOf(rs.getString("delivery_status")));

        // Encrypted content
        byte[] encContent = rs.getBytes("encrypted_content");
        if (encContent != null) {
            builder.encryptedContent(encContent);
        }

        // File name
        String fileName = rs.getString("file_name");
        if (fileName != null) {
            builder.fileName(fileName);
        }

        // Encrypted session key
        byte[] encKey = rs.getBytes("encrypted_session_key");
        if (encKey != null) {
            builder.encryptedSessionKey(encKey);
        }

        // Signature
        byte[] signature = rs.getBytes("signature");
        if (signature != null) {
            builder.signature(signature);
        }

        // Sender public signing key
        byte[] pubKey = rs.getBytes("sender_public_signing_key");
        if (pubKey != null) {
            builder.senderPublicSigningKey(pubKey);
        }

        // Signature status
        String sigStatus = rs.getString("signature_status");
        if (sigStatus != null) {
            builder.signatureStatus(SignatureStatus.valueOf(sigStatus));
        }

        ChatMessage message = builder.build();

        return new OutboundMessage(
                rs.getLong("id"),
                message,
                rs.getInt("retry_count"),
                rs.getLong("next_retry_time"),
                rs.getLong("created_at")
        );
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            logger.info("OutboxStore closed");
        }
    }

    /**
     * Статистика очереди
     */
    public record QueueStats(
            int total,
            int fresh,
            int retrying,
            double avgRetries
    ) {
        @Override
        public String toString() {
            return String.format("QueueStats[total=%d, fresh=%d, retrying=%d, avgRetries=%.2f]",
                    total, fresh, retrying, avgRetries);
        }
    }
}
