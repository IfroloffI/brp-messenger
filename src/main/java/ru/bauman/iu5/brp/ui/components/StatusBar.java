package ru.bauman.iu5.brp.ui.components;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class StatusBar extends HBox {
	private final Label statusLabel = new Label("STOPPED");
	private final Label detailLabel = new Label("No details");
	private final Label connectionLabel = new Label("No conversation selected");

	public StatusBar() {
		getStyleClass().add("status-bar");
		setSpacing(12);
		setPadding(new Insets(10, 12, 10, 12));

		Label title = new Label("BRP Messenger");
		title.getStyleClass().add("status-title");

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		statusLabel.getStyleClass().add("meta-label");
		detailLabel.getStyleClass().add("meta-label");
		connectionLabel.getStyleClass().add("meta-label");

		getChildren().addAll(title, statusLabel, spacer, detailLabel, connectionLabel);
	}

	public void setStatusText(String text) {
		statusLabel.setText(text);
	}

	public void setDetailText(String text) {
		detailLabel.setText(text);
	}

	public void setConnectionText(String text) {
		connectionLabel.setText(text);
	}
}
