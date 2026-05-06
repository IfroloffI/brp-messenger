package ru.bauman.iu5.brp.api.events;

/**
 * Событие получения публичного ключа от узла (обмен ключами при соединении).
 */
public class PublicKeyReceivedEvent extends AbstractApplicationEvent {
    private final long nodeId;
    private final String keyFingerprint;

    public PublicKeyReceivedEvent(long nodeId, String keyFingerprint) {
        super(EventType.PUBLIC_KEY_RECEIVED);
        this.nodeId = nodeId;
        this.keyFingerprint = keyFingerprint;
    }

    public long getNodeId() {
        return nodeId;
    }

    public String getKeyFingerprint() {
        return keyFingerprint;
    }

    @Override
    public String getDescription() {
        return "Получен публичный ключ от узла " + nodeId + " (fingerprint: " + keyFingerprint + ")";
    }

    @Override
    public String toString() {
        return "PublicKeyReceivedEvent{nodeId=" + nodeId + ", fingerprint='" + keyFingerprint + "'}";
    }
}
