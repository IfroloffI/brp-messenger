package ru.bauman.iu5.brp.ui;

import javafx.application.Application;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import ru.bauman.iu5.brp.api.ApplicationApi;

public class MainWindow extends Application {
    private static ApplicationApi sharedApi; // Статическое поле
    private ApplicationApi api;

    public static void setApplicationApi(ApplicationApi api) {
        sharedApi = api; // Сохраняем API
    }

    @Override
    public void init() {
        this.api = sharedApi; // Получаем API в init()
    }

    @Override
    public void start(Stage stage) {
        new Label("API: " + (api != null ? "Подключен" : "НЕТ"));
        new Label("Статус: " + (api.isRunning() ? "Запущен" : "Остановлен"));
    }
}
