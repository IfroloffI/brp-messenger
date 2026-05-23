package ru.bauman.iu5.brp.ui.panels;

import java.nio.file.Path;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class FilePanel extends VBox {
	private final TextField filePathField = new TextField();
	private final ListView<String> transferList = new ListView<>();
	private final TextArea eventLog = new TextArea();
	private final Label statsLabel = new Label("Статистика: -");

	public FilePanel(Runnable onChooseFile, Runnable onSendFile, Runnable onCancelTransfer) {
		getStyleClass().add("file-panel");
		setSpacing(8);
		setPadding(new Insets(12));
		setPrefWidth(420);

		Label title = new Label("Файлы и события");
		title.getStyleClass().add("section-title");

		filePathField.setPromptText("Выбранный файл");
		filePathField.setEditable(false);
		HBox.setHgrow(filePathField, Priority.ALWAYS);

		Button chooseButton = new Button("Выбрать файл");
		chooseButton.getStyleClass().add("ghost-button");
		chooseButton.setOnAction(e -> onChooseFile.run());

		Button sendButton = new Button("Отправить файл");
		sendButton.getStyleClass().add("accent-button");
		sendButton.setOnAction(e -> onSendFile.run());

		HBox fileRow = new HBox(8, filePathField, chooseButton, sendButton);

		Label transfersTitle = new Label("Активные передачи");
		transfersTitle.getStyleClass().add("meta-label");
		transferList.setPlaceholder(new Label("Активных передач нет"));
		transferList.setPrefHeight(180);

		Button cancelButton = new Button("Отменить передачу");
		cancelButton.getStyleClass().add("danger-button");
		cancelButton.setMaxWidth(Double.MAX_VALUE);
		cancelButton.setOnAction(e -> onCancelTransfer.run());

		Label logTitle = new Label("Журнал событий");
		logTitle.getStyleClass().add("meta-label");

		eventLog.setEditable(false);
		eventLog.setWrapText(true);

		statsLabel.getStyleClass().add("meta-label");

		getChildren().addAll(title, fileRow, transfersTitle, transferList, cancelButton, logTitle, eventLog, statsLabel);
		VBox.setVgrow(eventLog, Priority.ALWAYS);
	}

	public void setSelectedFile(Path path) {
		filePathField.setText(path == null ? "" : path.toString());
	}

	public String getSelectedTransferId() {
		String selected = transferList.getSelectionModel().getSelectedItem();
		if (selected == null || selected.isBlank()) {
			return null;
		}
		int divider = selected.indexOf(" | ");
		return divider > 0 ? selected.substring(0, divider) : selected;
	}

	public void setTransfers(List<String> transfers) {
		transferList.setItems(FXCollections.observableArrayList(transfers));
	}

	public void setStatsText(String text) {
		statsLabel.setText(text);
	}

	public void appendEventLine(String line) {
		if (!eventLog.getText().isEmpty()) {
			eventLog.appendText("\n");
		}
		eventLog.appendText(line);
		eventLog.positionCaret(eventLog.getText().length());
	}
}
