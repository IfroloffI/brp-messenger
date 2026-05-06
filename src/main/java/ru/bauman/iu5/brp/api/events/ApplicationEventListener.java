package ru.bauman.iu5.brp.api.events;

/**
 * Слушатель событий прикладного уровня.
 * Реализуется UI-компонентами для получения уведомлений о сетевых событиях.
 */
@FunctionalInterface
public interface ApplicationEventListener {

    /**
     * Вызывается при возникновении события.
     * ВАЖНО: вызывается из сетевого потока, UI должен использовать SwingUtilities.invokeLater().
     *
     * @param event произошедшее событие
     */
    void onEvent(ApplicationEvent event);
}
