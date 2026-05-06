package ru.bauman.iu5.brp.api.events;

import java.util.List;

/**
 * Событие реконфигурации кольца (ТЗ п. 5.3.2).
 * Генерируется при изменении топологии (добавление/удаление узлов).
 */
public class RingReconfiguredEvent extends AbstractApplicationEvent {
    private final List<Long> newTopology;
    private final int oldSize;
    private final int newSize;

    public RingReconfiguredEvent(List<Long> newTopology, int oldSize) {
        super(EventType.RING_RECONFIGURED);
        this.newTopology = List.copyOf(newTopology);
        this.oldSize = oldSize;
        this.newSize = newTopology.size();
    }

    public List<Long> getNewTopology() {
        return newTopology;
    }

    public int getOldSize() {
        return oldSize;
    }

    public int getNewSize() {
        return newSize;
    }

    @Override
    public String getDescription() {
        return "Кольцо реконфигурировано: " + oldSize + " → " + newSize + " узлов";
    }

    @Override
    public String toString() {
        return "RingReconfiguredEvent{oldSize=" + oldSize + ", newSize=" + newSize +
                ", topology=" + newTopology + "}";
    }
}
