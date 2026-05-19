-- ============================================================================
-- P2P Messenger Database Schema
-- Поддержка E2E шифрования, цифровых подписей и retry-логики
-- ============================================================================

-- Таблица: Персистентная очередь исходящих сообщений
-- Используется для retry-логики и гарантии доставки
CREATE TABLE IF NOT EXISTS outbox
(
    -- Идентификаторы
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    message_id
    BIGINT
    NOT
    NULL,
    sender_id
    BIGINT
    NOT
    NULL,
    recipient_id
    BIGINT
    NOT
    NULL,

    -- Тип и метаданные
    message_type
    VARCHAR
(
    20
) NOT NULL,
    file_name VARCHAR
(
    512
),
    timestamp BIGINT NOT NULL,
    ttl INT NOT NULL DEFAULT 255,

    -- Криптография (E2E encryption + digital signature)
    encrypted_content BLOB,
    encrypted_session_key BLOB,
    signature BLOB,
    sender_public_signing_key BLOB,

    -- Статусы
    signature_status VARCHAR
(
    20
),
    delivery_status VARCHAR
(
    20
) NOT NULL DEFAULT 'PENDING',

    -- Retry механизм
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time BIGINT NOT NULL,
    created_at BIGINT NOT NULL,

    -- Индексы для производительности
    INDEX idx_message_id
(
    message_id
),
    INDEX idx_next_retry
(
    next_retry_time
),
    INDEX idx_created_at
(
    created_at
),
    INDEX idx_status
(
    delivery_status
),
    INDEX idx_retry_count
(
    retry_count
)
    );

-- Таблица: История сообщений (опционально, для будущего функционала)
-- Хранит расшифрованные сообщения для отображения в истории чата
CREATE TABLE IF NOT EXISTS message_history
(
    -- Идентификаторы
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    message_id
    BIGINT
    NOT
    NULL
    UNIQUE,
    sender_id
    BIGINT
    NOT
    NULL,
    recipient_id
    BIGINT
    NOT
    NULL,

    -- Тип и контент
    message_type
    VARCHAR
(
    20
) NOT NULL,
    content_text TEXT,
    file_name VARCHAR
(
    512
),
    file_path VARCHAR
(
    1024
),

    -- Метаданные
    timestamp BIGINT NOT NULL,
    received_at BIGINT NOT NULL,

    -- Статусы безопасности
    signature_status VARCHAR
(
    20
),
    delivery_status VARCHAR
(
    20
),

    -- Направление (входящее/исходящее)
    direction VARCHAR
(
    10
) NOT NULL, -- 'INCOMING' / 'OUTGOING'

-- Индексы
    INDEX idx_message_id
(
    message_id
),
    INDEX idx_sender_recipient
(
    sender_id,
    recipient_id
),
    INDEX idx_timestamp
(
    timestamp
),
    INDEX idx_direction
(
    direction
)
    );

-- Таблица: Кеш публичных ключей узлов (опционально)
-- Для ускорения доступа к ключам без обращения к RingState
CREATE TABLE IF NOT EXISTS node_keys
(
    node_id
    BIGINT
    PRIMARY
    KEY,
    encryption_public_key
    BLOB
    NOT
    NULL,
    signing_public_key
    BLOB
    NOT
    NULL,
    last_seen
    BIGINT
    NOT
    NULL,

    INDEX
    idx_last_seen
(
    last_seen
)
    );

-- ============================================================================
-- Комментарии к полям
-- ============================================================================

-- outbox.message_id: Уникальный ID сообщения (используется для ACK и дедупликации)
-- outbox.encrypted_content: AES-зашифрованный текст или файл
-- outbox.encrypted_session_key: RSA-зашифрованный AES ключ (hybrid encryption)
-- outbox.signature: RSA подпись для верификации отправителя
-- outbox.sender_public_signing_key: Публичный ключ для проверки подписи
-- outbox.signature_status: NOT_CHECKED / VERIFIED / INVALID / MISSING
-- outbox.delivery_status: PENDING / SENT / DELIVERED / FAILED
-- outbox.next_retry_time: Timestamp следующей попытки (exponential backoff)

-- message_history.content_text: Расшифрованный текст (хранится только для TEXT)
-- message_history.file_path: Путь к сохранённому файлу (хранится только для FILE)
-- message_history.direction: INCOMING (от других) / OUTGOING (мои отправленные)