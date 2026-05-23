package ru.bauman.iu5.brp.ui.panels;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ru.bauman.iu5.brp.api.dto.NodeDto;

public class NetworkPanel extends VBox {
	private final TextField localNameField = new TextField();
	private final TextField portField = new TextField("5000");
	private final CheckBox discoveryCheckBox = new CheckBox("UDP discovery");
	private final Label statusLabel = new Label("Сеть остановлена");
	private final Label topologyLabel = new Label("-");
	private final ListView<NodeDto> nodeList = new ListView<>();

	public NetworkPanel(Runnable onStart, Runnable onStop, Runnable onRefresh, Consumer<NodeDto> onNodeSelected, Function<NodeDto, String> nodeTextFactory) {
		getStyleClass().add("network-panel");
		setSpacing(8);
		setPadding(new Insets(12));
		setPrefWidth(360);

		Label title = new Label("Сеть и узлы");
		title.getStyleClass().add("section-title");

		localNameField.setPromptText("Локальное имя");
		localNameField.setText("Node");

		HBox controls = new HBox(8, new Label("Port:"), portField, discoveryCheckBox);
		HBox.setHgrow(portField, Priority.ALWAYS);

		Button startButton = new Button("Start");
		startButton.getStyleClass().add("accent-button");
		startButton.setOnAction(e -> onStart.run());

		Button stopButton = new Button("Stop");
		stopButton.getStyleClass().add("danger-button");
		stopButton.setOnAction(e -> onStop.run());

		Button refreshButton = new Button("Refresh");
		refreshButton.getStyleClass().add("ghost-button");
		refreshButton.setOnAction(e -> onRefresh.run());

		HBox actions = new HBox(8, startButton, stopButton, refreshButton);

		nodeList.setCellFactory(listView -> new ListCell<>() {
			@Override
			protected void updateItem(NodeDto item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
				} else {
					setText(nodeTextFactory.apply(item));
				}
			}
		});
		nodeList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
			if (newValue != null) {
				onNodeSelected.accept(newValue);
			}
		});

		nodeList.setPlaceholder(new Label("Запустите сеть, чтобы увидеть узлы"));

		statusLabel.getStyleClass().add("meta-label");
		topologyLabel.getStyleClass().add("meta-label");

		getChildren().addAll(title, localNameField, controls, actions, statusLabel, new Label("Кольцо"), topologyLabel, nodeList);
		VBox.setVgrow(nodeList, Priority.ALWAYS);
	}

	public String getLocalNodeName() {
		return localNameField.getText().trim();
	}

	public int getTcpPort() {
		return Integer.parseInt(portField.getText().trim());
	}

	public boolean isDiscoveryEnabled() {
		return discoveryCheckBox.isSelected();
	}

	public void setStatusText(String text) {
		statusLabel.setText(text);
	}

	public void setTopologyText(String text) {
		topologyLabel.setText(text == null || text.isBlank() ? "-" : text);
	}

	public void setNodes(List<NodeDto> nodes) {
		NodeDto selected = nodeList.getSelectionModel().getSelectedItem();
		nodeList.setItems(FXCollections.observableArrayList(nodes));
		if (selected != null) {
			nodes.stream()
					.filter(node -> node.getNodeId() == selected.getNodeId())
					.findFirst()
					.ifPresent(node -> nodeList.getSelectionModel().select(node));
		}
	}

	public NodeDto getSelectedNode() {
		return nodeList.getSelectionModel().getSelectedItem();
	}
}
