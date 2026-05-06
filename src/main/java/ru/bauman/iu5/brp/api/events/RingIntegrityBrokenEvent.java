package ru.bauman.iu5.brp.api.events;

/**
 * Событие разрыва целостности кольца (ТЗ п. 5.3.2).
 * Генерируется при потере соединения с соседями.
 */
public class RingIntegrityBrokenEvent extends AbstractApplicationEvent {
    private final String reason;
    private final Long disconnectedNodeId;

    public RingIntegrityBrokenEvent(String reason, Long disconnectedNodeId) {
        super(EventType.RING_INTEGRITY_BROKEN);
        this.reason = reason;
        this.disconnectedNodeId = disconnectedNodeId;
    }

    public String getReason() {
        return reason;
    }

    public Long getDisconnectedNodeId() {
        return disconnectedNodeId;
    }

    @Override
    public String getDescription() {
        return "Целостность кольца нарушена: " + reason +
                (disconnectedNodeId != null ? " (узел " + disconnectedNodeId + ")" : "");
    }

    @Override
    public String toString() {
        return "RingIntegrityBrokenEvent{reason='" + reason + "', nodeId=" + disconnectedNodeId + "}";
    }
}
