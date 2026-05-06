package ru.bauman.iu5.brp.api.events;

/**
 * Событие восстановления целостности кольца после разрыва.
 */
public class RingIntegrityRestoredEvent extends AbstractApplicationEvent {
    private final int ringSize;

    public RingIntegrityRestoredEvent(int ringSize) {
        super(EventType.RING_INTEGRITY_RESTORED);
        this.ringSize = ringSize;
    }

    public int getRingSize() {
        return ringSize;
    }

    @Override
    public String getDescription() {
        return "Целостность кольца восстановлена (" + ringSize + " узлов)";
    }

    @Override
    public String toString() {
        return "RingIntegrityRestoredEvent{ringSize=" + ringSize + "}";
    }
}
