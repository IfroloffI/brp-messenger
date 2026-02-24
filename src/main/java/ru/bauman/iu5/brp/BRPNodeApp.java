package ru.bauman.iu5.brp;

import javafx.application.Application;
import ru.bauman.iu5.brp.ui.MainWindow;

public class BRPNodeApp {

    public static void main(String[] args) {
        System.out.println("BRP Messenger v1.0 (ИУ5-62Б)");
        System.out.println("Архитектура: OSI + Clean Architecture");
        System.out.println("Структура готова к реализации");

        // Запуск UI
        Application.launch(MainWindow.class, args);
    }
}
