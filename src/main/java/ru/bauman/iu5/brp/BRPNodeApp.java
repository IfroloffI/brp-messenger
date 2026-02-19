package ru.bauman.iu5.brp;

import javafx.application.Application;
import ru.bauman.iu5.brp.ui.MainUI;
import ru.bauman.iu5.brp.api.BRPService;

public class BRPNodeApp {
    public static void main(String[] args) {
        // Тест singleton API
        BRPService service = BRPService.getInstance();
        System.out.println("Main: " + service.getNodeStatus());

        // Запуск UI
        Application.launch(MainUI.class, args);
    }
}
