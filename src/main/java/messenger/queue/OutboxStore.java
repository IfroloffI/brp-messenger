package messenger.queue;

import messenger.protocol.ChatMessage;
import messenger.protocol.MessageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Персистентная очередь исходящих сообщений на базе H2.
 * <p>
 * Поддерживает retry логику и автоматическую очистку старых сообщений.
 */
public final class OutboxStore implements AutoCloseable {

    private static final String DB_DIR = System.getProperty("user.home") + "/.brp-messenger/outbox";
    private static final String DB_URL = "jdbc:h2:" + DB_DIR + "/outbox;AUTO_SERVER=TRUE";

    private final Connection connection;

    public OutboxStore() throws SQLException {
        ensureDirectoryExists();
        this.connection = DriverManager.getConnection(DB_URL);
        initSchema();
        System.out.println("[OutboxStore] Initialized at: " + DB_DIR);
    }

    private void ensureDirectoryExists() {
        try {
            Path dir = Paths.get(DB_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                System.out.println("[OutboxStore] Created directory: " + dir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create outbox directory", e);
        }
    }

    private void initSchema() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS outbox (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    sender_id BIGINT NOT NULL,
                    destination_id BIGINT NOT NULL,
                    message_type VARCHAR(10) NOT NULL,
                    content TEXT,
                    file_name VARCHAR(255),
                    file_data BLOB,
                    encrypted BOOLEAN NOT NULL,
                    timestamp BIGINT NOT NULL,
                    retry_count INT DEFAULT 0,
                    created_at BIGINT NOT NULL
                )
                """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            System.out.println("[OutboxStore] Schema initialized");
        }

        // Создаём индексы для производительности
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_created_at ON outbox(created_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_retry ON outbox(retry_count)");
            System.out.println("[OutboxStore] Indexes created");
        }
    }

    /**
     * Добавление сообщения в очередь.
     *
     * @param message Сообщение для отправки
     * @return ID записи в БД
     */
    public long addMessage(ChatMessage message) throws SQLException {
        String sql = """
                INSERT INTO outbox (sender_id, destination_id, message_type, content, 
                                   file_name, file_data, encrypted, timestamp, created_at, retry_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """;

        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, message.getSenderId());
            stmt.setLong(2, message.getDestinationId());
            stmt.setString(3, message.getType().name());
            stmt.setString(4, message.getContent());
            stmt.setString(5, message.getFileName());
            stmt.setBytes(6, message.getFileData());
            stmt.setBoolean(7, message.isEncrypted());
            stmt.setLong(8, message.getTimestamp());
            stmt.setLong(9, System.currentTimeMillis());

            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    System.out.println("[OutboxStore] Added message: id=" + id +
                            ", type=" + message.getType());
                    return id;
                }
            }
        }

        throw new SQLException("Failed to get generated key");
    }

    /**
     * Получение всех неотправленных сообщений.
     *
     * @return Список сообщений для retry
     */
    public List<OutboundMessage> getPendingMessages() throws SQLException {
        String sql = "SELECT * FROM outbox ORDER BY created_at ASC";
        List<OutboundMessage> result = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                result.add(mapRow(rs));
            }
        }

        return result;
    }

    /**
     * Удаление сообщения из очереди (успешно отправлено).
     *
     * @param id ID записи в БД
     */
    public void removeMessage(long id) throws SQLException {
        String sql = "DELETE FROM outbox WHERE id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                System.out.println("[OutboxStore] Removed message: id=" + id);
            }
        }
    }

    /**
     * Увеличение счётчика попыток отправки.
     *
     * @param id ID записи в БД
     */
    public void incrementRetry(long id) throws SQLException {
        String sql = "UPDATE outbox SET retry_count = retry_count + 1 WHERE id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        }
    }

    /**
     * Очистка старых сообщений (> 24 часов).
     */
    public void cleanupOldMessages() throws SQLException {
        long cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 часа
        String sql = "DELETE FROM outbox WHERE created_at < ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, cutoff);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                System.out.println("[OutboxStore] Cleaned up " + deleted + " old messages");
            }
        }
    }

    /**
     * Получение количества сообщений в очереди.
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
     * Маппинг строки БД в OutboundMessage.
     */
    private OutboundMessage mapRow(ResultSet rs) throws SQLException {
        ChatMessage.Builder builder = new ChatMessage.Builder()
                .senderId(rs.getLong("sender_id"))
                .destinationId(rs.getLong("destination_id"))
                .type(MessageType.valueOf(rs.getString("message_type")))
                .encrypted(rs.getBoolean("encrypted"))
                .timestamp(rs.getLong("timestamp"));

        String content = rs.getString("content");
        if (content != null) {
            builder.content(content);
        }

        String fileName = rs.getString("file_name");
        if (fileName != null) {
            builder.fileName(fileName);
            builder.fileData(rs.getBytes("file_data"));
        }

        return new OutboundMessage(
                rs.getLong("id"),
                builder.build(),
                rs.getInt("retry_count"),
                rs.getLong("created_at")
        );
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            System.out.println("[OutboxStore] Closed");
        }
    }
}