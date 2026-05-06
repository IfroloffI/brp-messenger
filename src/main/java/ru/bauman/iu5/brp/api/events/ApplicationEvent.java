package ru.bauman.iu5.brp.api.events;

import java.time.Instant;

/**
 * Базовый интерфейс для всех событий прикладного уровня.
 * События генерируются сетевым стеком и доставляются в UI через ApplicationEventListener.
 */
public interface ApplicationEvent {

    /**
     * Возвращает тип события.
     */
    EventType getType();

    /**
     * Возвращает время возникновения события.
     */
    Instant getTimestamp();

    /**
     * Возвращает человекочитаемое описание события для логов/UI.
     */
    String getDescription();
}
