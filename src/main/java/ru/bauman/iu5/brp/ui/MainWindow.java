package ru.bauman.iu5.brp.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class MainWindow extends Application {

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(20);
        root.setStyle("-fx-padding: 20;");

        root.getChildren().addAll(
                new Label("BRP Messenger"),
                new Label("Эскизное проектирование"),
                new Label("ИУ5-62Б | 25.02.2026"),
                new Label("Архитектура готова!")
        );

        Scene scene = new Scene(root, 400, 300);
        stage.setTitle("BRP P2P Node");
        stage.setScene(scene);
        stage.show();
    }
}
