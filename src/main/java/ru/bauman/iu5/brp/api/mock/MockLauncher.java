package ru.bauman.iu5.brp.api.mock;

import ru.bauman.iu5.brp.api.ApplicationApi;

/**
 * Точка входа для запуска приложения с mock API.
 * Используется для разработки и тестирования UI без реального сетевого стека.
 *
 * Запуск: java ru.bauman.iu5.brp.api.mock.MockLauncher
 */
public class MockLauncher {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("BRP Messenger - Mock Mode");
        System.out.println("МГТУ им. Баумана, кафедра ИУ5, группа ИУ5-62Б");
        System.out.println("Разработчики: Фролов И.О., Яковлев С.А., Астахов И.А., Копылов Н.Н.");
        System.out.println("=".repeat(70));
        System.out.println();
        System.out.println("Режим: MOCK (тестирование UI без реальной сети)");
        System.out.println();

        // Создание mock API
        ApplicationApi api = new MockApplicationApi();

        System.out.println("✓ Mock API инициализирован");
        System.out.println("✓ Генератор тестовых данных готов");
        System.out.println();

        // Автозапуск сети для удобства
        try {
            api.start(5000, true);
            System.out.println("✓ Mock сеть запущена на порту 5000");
            System.out.println("✓ UDP Discovery включен (будут обнаружены 2-3 тестовых узла)");
            System.out.println();
        } catch (Exception e) {
            System.err.println("✗ Ошибка запуска mock сети: " + e.getMessage());
            return;
        }

        // TODO: UI
        System.out.println("TODO: Запуск UI");
        System.out.println("Раскомментируйте следующую строку после создания MainWindow:");
        javax.swing.SwingUtilities.invokeLater(() -> new ru.bauman.iu5.brp.ui.MainWindow());
        System.out.println();

        // Пример использования API
        demonstrateApi(api);

        // Держим приложение запущенным
        System.out.println();
        System.out.println("Приложение работает. Нажмите Ctrl+C для остановки.");

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            api.stop();
            System.out.println("\nПриложение остановлено");
        }
    }

    /**
     * Демонстрация использования mock API.
     */
    private static void demonstrateApi(ApplicationApi api) {
        System.out.println("=".repeat(70));
        System.out.println("Демонстрация Mock API");
        System.out.println("=".repeat(70));

        // Подписка на события
        api.addEventListener(event -> {
            System.out.println("[EVENT] " + event.getType() + ": " + event.getDescription());
        });

        System.out.println();
        System.out.println("✓ Подписка на события установлена");
        System.out.println("  События будут отображаться в консоли по мере их возникновения");
        System.out.println();
        System.out.println("Ожидаемые события:");
        System.out.println("  - NODE_JOINED (через 2-8 секунд) - обнаружение узлов");
        System.out.println("  - RING_RECONFIGURED - реконфигурация кольца");
        System.out.println("  - MESSAGE_RECEIVED (через 15+ секунд) - входящие сообщения");

        // Пример отправки сообщения через 10 секунд
        new Thread(() -> {
            try {
                Thread.sleep(10000);

                var onlineNodes = api.getOnlineNodes();
                if (!onlineNodes.isEmpty()) {
                    long targetId = onlineNodes.get(0).getNodeId();
                    String messageId = api.sendMessage(targetId, "Привет из mock API!");
                    System.out.println();
                    System.out.println("[DEMO] Отправлено тестовое сообщение, ID: " + messageId);
                }
            } catch (Exception e) {
                System.err.println("[DEMO] Ошибка отправки сообщения: " + e.getMessage());
            }
        }).start();
    }
}
