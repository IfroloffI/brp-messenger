package ru.bauman.iu5.brp.storage;

import ru.bauman.iu5.brp.protocol.ChatMessage;
import ru.bauman.iu5.brp.protocol.DeliveryStatus;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис отслеживания доставки сообщений с автоматическим retry.
 * <p>
 * Периодически проверяет очередь и пытается переотправить неудавшиеся сообщения.
 */
public class DeliveryTracker implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(DeliveryTracker.class.getName());

    private static final long RETRY_CHECK_INTERVAL_MS = 5000; // 5 секунд
    private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000; // 1 час

    private final OutboxStore outboxStore;
    private final ScheduledExecutorService scheduler;
    private final RetryCallback retryCallback;

    public DeliveryTracker(OutboxStore outboxStore, RetryCallback retryCallback) {
        this.outboxStore = outboxStore;
        this.retryCallback = retryCallback;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    /**
     * Запуск фоновых задач
     */
    public void start() {
        // Периодическая проверка pending сообщений
        scheduler.scheduleWithFixedDelay(
                this::checkPendingMessages,
                RETRY_CHECK_INTERVAL_MS,
                RETRY_CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        // Периодическая очистка старых сообщений
        scheduler.scheduleWithFixedDelay(
                this::cleanupOldMessages,
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        logger.info("DeliveryTracker started");
    }

    /**
     * Проверка и retry pending сообщений
     */
    private void checkPendingMessages() {
        try {
            List<OutboundMessage> pending = outboxStore.getPendingMessages();

            if (pending.isEmpty()) {
                return;
            }

            logger.fine("Checking " + pending.size() + " pending messages");

            for (OutboundMessage outbound : pending) {
                if (outbound.isExpired()) {
                    // Превышен лимит попыток - удаляем
                    logger.warning("Message expired after " + outbound.retryCount() +
                            " retries: " + outbound);
                    outboxStore.removeMessage(outbound.id());
                    continue;
                }

                if (outbound.isReadyForRetry()) {
                    // Пробуем переотправить
                    logger.info("Retrying message: " + outbound);

                    boolean success = retryCallback.onRetry(outbound.message());

                    if (success) {
                        // Успешно отправлено - удаляем из очереди
                        outboxStore.removeMessage(outbound.id());
                        logger.info("Message sent successfully: " + outbound.id());
                    } else {
                        // Не удалось - увеличиваем счётчик
                        outboxStore.incrementRetry(outbound.id());
                        logger.warning("Message retry failed: " + outbound.id());
                    }
                }
            }

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to check pending messages", e);
        }
    }

    /**
     * Очистка старых сообщений
     */
    private void cleanupOldMessages() {
        try {
            int deleted = outboxStore.cleanup();

            if (deleted > 0) {
                logger.info("Cleanup completed: deleted " + deleted + " old messages");
            }

            // Логируем статистику
            OutboxStore.QueueStats stats = outboxStore.getStats();
            logger.info("Outbox stats: " + stats);

        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to cleanup old messages", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("DeliveryTracker stopped");
    }

    /**
     * Callback для retry попыток
     */
    @FunctionalInterface
    public interface RetryCallback {
        /**
         * Вызывается при попытке переотправки сообщения
         *
         * @param message Сообщение для отправки
         * @return true если отправка успешна, false иначе
         */
        boolean onRetry(ChatMessage message);
    }
}
