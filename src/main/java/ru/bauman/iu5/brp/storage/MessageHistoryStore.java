package ru.bauman.iu5.brp.storage;

import ru.bauman.iu5.brp.api.dto.ChatMessageDto;
import ru.bauman.iu5.brp.api.dto.DeliveryStatus;
import ru.bauman.iu5.brp.api.dto.SignatureStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * H2-backed persistent message history storage.
 * Stores incoming and outgoing messages for UI chat history (ТЗ п. 5.2.4).
 */
public class MessageHistoryStore {
    private static final Logger logger = Logger.getLogger(MessageHistoryStore.class.getName());
    private static final String DB_PATH = System.getProperty("user.home") + "/.messenger/history/";
    private static final String DB_NAME = "message_history";
    private static final int CLEANUP_DAYS = 90; // Keep messages for 90 days

    private final Connection connection;

    public MessageHistoryStore() throws SQLException {
        ensureDatabase();
        this.connection = createConnection();
        createSchema();
    }

    private void ensureDatabase() {
        try {
            Path dbPath = Paths.get(DB_PATH);
            if (!Files.exists(dbPath)) {
                Files.createDirectories(dbPath);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to create database directory", e);
        }
    }

    private Connection createConnection() throws SQLException {
        String url = "jdbc:h2:" + DB_PATH + DB_NAME;
        return DriverManager.getConnection(url);
    }

    private void createSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS message_history (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                message_id VARCHAR(255) NOT NULL UNIQUE,
                sender_node_id BIGINT NOT NULL,
                recipient_node_id BIGINT NOT NULL,
                timestamp BIGINT NOT NULL,
                text CLOB NOT NULL,
                signature_status VARCHAR(50) NOT NULL,
                delivery_status VARCHAR(50) NOT NULL,
                is_outgoing BOOLEAN NOT NULL,
                is_read BOOLEAN DEFAULT FALSE,
                created_at BIGINT DEFAULT CURRENT_TIMESTAMP
            );
            CREATE INDEX IF NOT EXISTS idx_peer ON message_history(sender_node_id, recipient_node_id);
            CREATE INDEX IF NOT EXISTS idx_timestamp ON message_history(timestamp DESC);
            CREATE INDEX IF NOT EXISTS idx_read_status ON message_history(is_outgoing, is_read);
        """;

        try (Statement stmt = connection.createStatement()) {
            for (String statement : sql.split(";")) {
                if (!statement.trim().isEmpty()) {
                    stmt.execute(statement.trim());
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create schema", e);
        }
    }

    /**
     * Save message to history.
     */
    public synchronized void saveMessage(ChatMessageDto message) throws SQLException {
        String sql = """
            INSERT INTO message_history
            (message_id, sender_node_id, recipient_node_id, timestamp, text, signature_status, delivery_status, is_outgoing, is_read)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, message.getMessageId());
            pstmt.setLong(2, message.getSenderNodeId());
            pstmt.setLong(3, message.getTargetNodeId());
            pstmt.setLong(4, message.getTimestamp().toEpochMilli());
            pstmt.setString(5, message.getText());
            pstmt.setString(6, message.getSignatureStatus().name());
            pstmt.setString(7, message.getDeliveryStatus().name());
            pstmt.setBoolean(8, message.isOutgoing());
            pstmt.setBoolean(9, message.isRead());
            pstmt.executeUpdate();
        }
    }

    /**
     * Get message history with peer (conversations where either party is sender or recipient).
     */
    public synchronized List<ChatMessageDto> getHistory(long localNodeId, long peerNodeId, int limit, int offset) throws SQLException {
        String sql = """
            SELECT message_id, sender_node_id, recipient_node_id, timestamp, text, signature_status, delivery_status, is_outgoing, is_read
            FROM message_history
            WHERE (sender_node_id = ? AND recipient_node_id = ?)
               OR (sender_node_id = ? AND recipient_node_id = ?)
            ORDER BY timestamp DESC
            LIMIT ? OFFSET ?
        """;

        List<ChatMessageDto> messages = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, localNodeId);
            pstmt.setLong(2, peerNodeId);
            pstmt.setLong(3, peerNodeId);
            pstmt.setLong(4, localNodeId);
            pstmt.setInt(5, limit > 0 ? limit : Integer.MAX_VALUE);
            pstmt.setInt(6, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(resultSetToDto(rs));
                }
            }
        }

        Collections.reverse(messages); // oldest first
        return messages;
    }

    /**
     * Get last message with peer.
     */
    public synchronized Optional<ChatMessageDto> getLastMessage(long localNodeId, long peerNodeId) throws SQLException {
        String sql = """
            SELECT message_id, sender_node_id, recipient_node_id, timestamp, text, signature_status, delivery_status, is_outgoing, is_read
            FROM message_history
            WHERE (sender_node_id = ? AND recipient_node_id = ?)
               OR (sender_node_id = ? AND recipient_node_id = ?)
            ORDER BY timestamp DESC
            LIMIT 1
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, localNodeId);
            pstmt.setLong(2, peerNodeId);
            pstmt.setLong(3, peerNodeId);
            pstmt.setLong(4, localNodeId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(resultSetToDto(rs));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Get unread count for incoming messages from peer.
     */
    public synchronized int getUnreadCount(long localNodeId, long peerNodeId) throws SQLException {
        String sql = """
            SELECT COUNT(*) as count FROM message_history
            WHERE recipient_node_id = ? AND sender_node_id = ? AND is_read = FALSE
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, localNodeId);
            pstmt.setLong(2, peerNodeId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count");
                }
            }
        }

        return 0;
    }

    /**
     * Mark all messages from peer as read.
     */
    public synchronized void markAsRead(long localNodeId, long peerNodeId) throws SQLException {
        String sql = """
            UPDATE message_history
            SET is_read = TRUE
            WHERE recipient_node_id = ? AND sender_node_id = ?
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, localNodeId);
            pstmt.setLong(2, peerNodeId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Delete all message history with peer.
     */
    public synchronized void deleteHistory(long localNodeId, long peerNodeId) throws SQLException {
        String sql = """
            DELETE FROM message_history
            WHERE (sender_node_id = ? AND recipient_node_id = ?)
               OR (sender_node_id = ? AND recipient_node_id = ?)
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, localNodeId);
            pstmt.setLong(2, peerNodeId);
            pstmt.setLong(3, peerNodeId);
            pstmt.setLong(4, localNodeId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Get last messages with each peer (for chat list preview).
     */
    public synchronized Map<Long, ChatMessageDto> getLastMessagesPerPeer(long localNodeId) throws SQLException {
        String sql = """
            SELECT DISTINCT ON (peer_id) peer_id, message_id, sender_node_id, recipient_node_id, timestamp, text, signature_status, delivery_status, is_outgoing, is_read
            FROM (
                SELECT CASE
                    WHEN sender_node_id = ? THEN recipient_node_id
                    ELSE sender_node_id
                END as peer_id,
                message_id, sender_node_id, recipient_node_id, timestamp, text, signature_status, delivery_status, is_outgoing, is_read
                FROM message_history
                WHERE sender_node_id = ? OR recipient_node_id = ?
            )
            ORDER BY peer_id, timestamp DESC
        """;

        Map<Long, ChatMessageDto> messages = new HashMap<>();

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, localNodeId);
            pstmt.setLong(2, localNodeId);
            pstmt.setLong(3, localNodeId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ChatMessageDto dto = resultSetToDto(rs);
                    long peerId = rs.getLong("peer_id");
                    messages.put(peerId, dto);
                }
            }
        }

        return messages;
    }

    /**
     * Cleanup old messages (older than CLEANUP_DAYS).
     */
    public synchronized int cleanup() throws SQLException {
        long cutoffTime = System.currentTimeMillis() - (CLEANUP_DAYS * 24 * 60 * 60 * 1000L);
        String sql = "DELETE FROM message_history WHERE timestamp < ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, cutoffTime);
            return pstmt.executeUpdate();
        }
    }

    private ChatMessageDto resultSetToDto(ResultSet rs) throws SQLException {
        String messageId = rs.getString("message_id");
        long senderId = rs.getLong("sender_node_id");
        long recipientId = rs.getLong("recipient_node_id");
        long timestamp = rs.getLong("timestamp");
        String text = rs.getString("text");
        SignatureStatus sigStatus = SignatureStatus.valueOf(rs.getString("signature_status"));
        DeliveryStatus delStatus = DeliveryStatus.valueOf(rs.getString("delivery_status"));
        boolean isOutgoing = rs.getBoolean("is_outgoing");
        boolean isRead = rs.getBoolean("is_read");

        ChatMessageDto dto = new ChatMessageDto(messageId, senderId, recipientId, Instant.ofEpochMilli(timestamp), text, sigStatus);
        dto.setDeliveryStatus(delStatus);
        dto.setRead(isRead);
        return dto;
    }

    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
