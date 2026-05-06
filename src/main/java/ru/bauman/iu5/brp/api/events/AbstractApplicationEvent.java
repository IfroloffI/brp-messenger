package ru.bauman.iu5.brp.api.events;

import java.time.Instant;
import java.util.Objects;

/**
 * Базовая реализация ApplicationEvent с общими полями.
 */
public abstract class AbstractApplicationEvent implements ApplicationEvent {
    private final EventType type;
    private final Instant timestamp;

    protected AbstractApplicationEvent(EventType type) {
        this.type = type;
        this.timestamp = Instant.now();
    }

    @Override
    public EventType getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbstractApplicationEvent that = (AbstractApplicationEvent) o;
        return type == that.type && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, timestamp);
    }
}
