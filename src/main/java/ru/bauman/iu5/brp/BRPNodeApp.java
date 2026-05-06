package ru.bauman.iu5.brp;

import javafx.application.Application;
import ru.bauman.iu5.brp.api.ApplicationApi;
import ru.bauman.iu5.brp.api.dto.NetworkException;
import ru.bauman.iu5.brp.api.mock.MockApplicationApi;
import ru.bauman.iu5.brp.ui.MainWindow;

public class BRPNodeApp {
    private static ApplicationApi api;

    public static void main(String[] args) {
        try {
            api = new MockApplicationApi();

            try {
                api.start(5000, true);
            } catch (NetworkException e) {
                System.err.println("x Ошибка запуска: " + e.getMessage());
                System.exit(1);
            }
        } catch (Exception e) {
            // Общая обработка
        }

        MainWindow.setApplicationApi(api);

        Application.launch(MainWindow.class, args);
    }
}
