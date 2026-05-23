package ru.bauman.iu5.brp;

import javafx.application.Application;
import ru.bauman.iu5.brp.api.ApplicationApi;
import ru.bauman.iu5.brp.api.RealApplicationApi;
import ru.bauman.iu5.brp.ui.MainWindow;

public class BRPNodeApp {
    public static void main(String[] args) {
        ApplicationApi api = new RealApplicationApi();
        MainWindow.setApplicationApi(api);
        Application.launch(MainWindow.class, args);
    }
}
