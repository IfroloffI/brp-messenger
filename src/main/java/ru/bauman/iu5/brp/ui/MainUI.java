package ru.bauman.iu5.brp.ui;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import ru.bauman.iu5.brp.api.BRPService;

public class MainUI extends Application {

    private final BRPService service = BRPService.getInstance();

    @Override
    public void start(Stage stage) {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));

        Label statusLabel = new Label("Status: Loading...");
        Button statusBtn = new Button("Get Node Status");
        Button apiBtn = new Button("Call API");

        // Status button
        statusBtn.setOnAction(e -> {
            String status = service.getNodeStatus();
            statusLabel.setText(status);
        });

        // API button
        apiBtn.setOnAction(e -> {
            String response = service.processMessage("test message from UI");
            statusLabel.setText(response);
        });

        root.getChildren().addAll(
                new Label("BRP Messenger"),
                statusBtn,
                apiBtn,
                statusLabel
        );

        Scene scene = new Scene(root, 400, 300);
        stage.setTitle("BRP Messenger");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
