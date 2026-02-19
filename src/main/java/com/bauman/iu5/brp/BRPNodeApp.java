package com.bauman.iu5.brp;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class BRPNodeApp extends Application {
    @Override
    public void start(Stage stage) {
        VBox root = new VBox(10);
        root.getChildren().add(new Label("Hello from BRP Messenger!"));

        Scene scene = new Scene(root, 300, 200);
        stage.setTitle("BRP P2P Template");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
