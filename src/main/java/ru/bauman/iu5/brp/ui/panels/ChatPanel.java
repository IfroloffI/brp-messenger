package ru.bauman.iu5.brp.ui.panels;

import java.util.List;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class ChatPanel extends VBox {
	private final Label conversationLabel = new Label("Выберите узел слева");
	private final ListView<String> messageList = new ListView<>();
	private final TextField inputField = new TextField();

	public ChatPanel(Runnable onSendMessage) {
		getStyleClass().add("chat-panel");
		setSpacing(8);
		setPadding(new Insets(12));

		Label title = new Label("Чат");
		title.getStyleClass().add("section-title");

		conversationLabel.getStyleClass().add("meta-label");
		messageList.setPlaceholder(new Label("Сообщения будут отображаться здесь"));

		inputField.setPromptText("Введите сообщение");
		HBox.setHgrow(inputField, Priority.ALWAYS);

		Button sendButton = new Button("Отправить");
		sendButton.getStyleClass().add("accent-button");
		sendButton.setOnAction(e -> onSendMessage.run());

		HBox inputRow = new HBox(8, inputField, sendButton);

		getChildren().addAll(title, conversationLabel, messageList, inputRow);
		VBox.setVgrow(messageList, Priority.ALWAYS);
	}

	public String getMessageText() {
		return inputField.getText().trim();
	}

	public void clearMessageText() {
		inputField.clear();
	}

	public void setConversationTitle(String text) {
		conversationLabel.setText(text);
	}

	public void setMessages(List<String> messages) {
		messageList.setItems(FXCollections.observableArrayList(messages));
		if (!messages.isEmpty()) {
			messageList.scrollTo(messages.size() - 1);
		}
	}
}
