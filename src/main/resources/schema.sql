-- Outbox для retry механизма
CREATE TABLE IF NOT EXISTS outbox
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    sender_id
    BIGINT
    NOT
    NULL,
    destination_id
    BIGINT
    NOT
    NULL,
    message_type
    VARCHAR
(
    10
) NOT NULL,
    content TEXT,
    file_name VARCHAR
(
    255
),
    file_data BLOB,
    encrypted BOOLEAN NOT NULL,
    timestamp BIGINT NOT NULL,
    retry_count INT DEFAULT 0,
    created_at BIGINT NOT NULL,
    INDEX idx_created_at
(
    created_at
),
    INDEX idx_retry
(
    retry_count
)
    );

-- Опционально: история сообщений
CREATE TABLE IF NOT EXISTS message_history
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    sender_id
    BIGINT
    NOT
    NULL,
    destination_id
    BIGINT
    NOT
    NULL,
    message_type
    VARCHAR
(
    10
) NOT NULL,
    content TEXT,
    file_name VARCHAR
(
    255
),
    encrypted BOOLEAN NOT NULL,
    timestamp BIGINT NOT NULL,
    received_at BIGINT NOT NULL,
    INDEX idx_timestamp
(
    timestamp
)
    );