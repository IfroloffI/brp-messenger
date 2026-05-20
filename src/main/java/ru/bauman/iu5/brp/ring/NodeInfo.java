package ru.bauman.iu5.brp.ring;

import java.net.InetAddress;
import java.security.PublicKey;

/**
 * Информация об узле в кольце с публичными ключами
 */
public record NodeInfo(
        long nodeId,
        InetAddress address,
        int port,
        long lastSeen,
        PublicKey encryptionPublicKey,  // для шифрования сообщений этому узлу
        PublicKey signingPublicKey      // для проверки подписей от этого узла
) {
    /**
     * Конструктор без ключей (для обратной совместимости)
     */
    public NodeInfo(long nodeId, InetAddress address, int port, long lastSeen) {
        this(nodeId, address, port, lastSeen, null, null);
    }

    /**
     * Обновить timestamp последнего контакта
     */
    public NodeInfo withUpdatedTimestamp(long newLastSeen) {
        return new NodeInfo(nodeId, address, port, newLastSeen, encryptionPublicKey, signingPublicKey);
    }

    /**
     * Обновить публичные ключи
     */
    public NodeInfo withKeys(PublicKey encryptionKey, PublicKey signingKey) {
        return new NodeInfo(nodeId, address, port, lastSeen, encryptionKey, signingKey);
    }

    /**
     * Проверить, истёк ли таймаут узла
     */
    public boolean isExpired(long currentTime, long timeout) {
        return (currentTime - lastSeen) > timeout;
    }

    /**
     * Проверить, есть ли у узла оба публичных ключа
     */
    public boolean hasKeys() {
        return encryptionPublicKey != null && signingPublicKey != null;
    }

    @Override
    public String toString() {
        return String.format("NodeInfo{id=%d, addr=%s:%d, lastSeen=%d, hasKeys=%b}",
                nodeId, address.getHostAddress(), port, lastSeen, hasKeys());
    }
}
