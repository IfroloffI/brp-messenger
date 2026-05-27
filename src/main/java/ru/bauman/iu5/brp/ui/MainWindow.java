package ru.bauman.iu5.brp.ui;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import ru.bauman.iu5.brp.api.ApplicationApi;
import ru.bauman.iu5.brp.api.dto.ChatMessageDto;
import ru.bauman.iu5.brp.api.dto.NetworkException;
import ru.bauman.iu5.brp.api.dto.NodeDto;
import ru.bauman.iu5.brp.api.events.ApplicationEvent;
import ru.bauman.iu5.brp.api.events.ApplicationEventListener;
import ru.bauman.iu5.brp.api.events.EventType;
import ru.bauman.iu5.brp.api.events.MessageReceivedEvent;
import ru.bauman.iu5.brp.ui.components.StatusBar;
import ru.bauman.iu5.brp.ui.panels.ChatPanel;
import ru.bauman.iu5.brp.ui.panels.FilePanel;
import ru.bauman.iu5.brp.ui.panels.NetworkPanel;

public class MainWindow extends Application {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static ApplicationApi sharedApi;

    private ApplicationApi api;
    private Stage stage;
    private Timeline refreshTimer;
    private NetworkPanel networkPanel;
    private ChatPanel chatPanel;
    private FilePanel filePanel;
    private StatusBar statusBar;
    private Path selectedFile;
    private Long selectedNodeId;

    private final ApplicationEventListener listener = event -> Platform.runLater(() -> handleEvent(event));

    public static void setApplicationApi(ApplicationApi api) {
        sharedApi = api;
    }

    @Override
    public void init() {
        api = sharedApi;
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        if (api == null) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "ApplicationApi не был передан в MainWindow");
            alert.showAndWait();
            Platform.exit();
            return;
        }

        networkPanel = new NetworkPanel(
                this::startBackendAsync,
                this::stopBackend,
                this::refreshAll,
                this::onNodeSelected,
                node -> buildNodeLabel(node)
        );
        chatPanel = new ChatPanel(this::sendMessage);
        filePanel = new FilePanel(this::chooseFile, this::sendFile, this::cancelSelectedTransfer);
        statusBar = new StatusBar();

        api.addEventListener(listener);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setPadding(new Insets(12));
        root.setLeft(networkPanel);
        root.setCenter(chatPanel);
        root.setRight(filePanel);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1360, 800);
        var css = MainWindow.class.getResource("/ui/main.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.setTitle("BRP Messenger");
        stage.setScene(scene);
        stage.show();

        refreshAll();
        startBackendAsync();

        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshAll()));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        refreshTimer.play();
    }

    @Override
    public void stop() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
        if (api != null) {
            api.removeEventListener(listener);
            api.stop();
        }
    }

    private void startBackendAsync() {
        if (api.isRunning()) {
            return;
        }

        api.setLocalNodeName(networkPanel.getLocalNodeName());
        int port = networkPanel.getTcpPort();
        boolean useDiscovery = networkPanel.isDiscoveryEnabled();

        Thread startupThread = new Thread(() -> {
            try {
                api.start(port, useDiscovery);
                Platform.runLater(this::refreshAll);
            } catch (NetworkException ex) {
                Platform.runLater(() -> showError("Не удалось запустить сеть: " + ex.getMessage()));
            }
        }, "brp-api-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    private void stopBackend() {
        api.stop();
        refreshAll();
    }

    private void refreshAll() {
        refreshNodes();
        refreshConversation();
        refreshTransfers();
        refreshStatus();
    }

    private void refreshNodes() {
        List<NodeDto> visibleNodes = api.getAllNodes().stream()
                .filter(node -> node.getNodeId() != api.getLocalNodeId())
                .collect(Collectors.toList());
        networkPanel.setNodes(visibleNodes);

        if (selectedNodeId != null && visibleNodes.stream().noneMatch(node -> node.getNodeId() == selectedNodeId)) {
            if (api.getMessageHistory(selectedNodeId, 1, 0).isEmpty()) {
                selectedNodeId = null;
            }
        }
    }

    private void refreshConversation() {
        if (selectedNodeId == null) {
            chatPanel.setConversationTitle("Выберите узел слева");
            chatPanel.setMessages(List.of());
            return;
        }

        NodeDto selectedNode = api.getNodeById(selectedNodeId).orElse(null);
        if (selectedNode == null) {
            chatPanel.setConversationTitle("Node_" + selectedNodeId + " · unknown");
            chatPanel.setMessages(api.getMessageHistory(selectedNodeId, 0, 0).stream()
                    .map(this::formatMessage)
                    .collect(Collectors.toList()));
            return;
        }

        chatPanel.setConversationTitle(selectedNode.getDisplayName() + " · " + selectedNode.getIpAddress() + ":" + selectedNode.getPort());
        chatPanel.setMessages(api.getMessageHistory(selectedNodeId, 0, 0).stream()
                .map(this::formatMessage)
                .collect(Collectors.toList()));
    }

    private void refreshTransfers() {
        filePanel.setSelectedFile(selectedFile);
        filePanel.setTransfers(api.getActiveFileTransfers().stream()
                .map(transfer -> transfer.getTransferId() + " | "
                        + transfer.getFileName() + " -> " + transfer.getPeerNodeId() + " | "
                        + String.format("%.1f", transfer.getProgressPercent()) + "% | " + transfer.getStatus())
                .collect(Collectors.toList()));

        var stats = api.getNetworkStatistics();
        filePanel.setStatsText(
                "Bytes: sent " + stats.getBytesSent() + ", received " + stats.getBytesReceived()
                        + " | Messages: " + stats.getMessagesDelivered() + "/" + stats.getMessagesReceived()
                        + " | Files: " + stats.getFilesDelivered() + "/" + stats.getFilesReceived()
                        + " | Errors: " + stats.getTotalErrors()
        );
    }

    private void refreshStatus() {
        var stats = api.getNetworkStatistics();
        statusBar.setStatusText(api.isRunning() ? "RUNNING" : "STOPPED");
        statusBar.setDetailText("Nodes: " + api.getOnlineNodes().size() + " | Ring: " + api.getRingTopology().size()
                + " | Uptime: " + stats.getUptimeSeconds() + "s");
        statusBar.setConnectionText(selectedNodeId == null ? "No conversation selected" : "Chatting with node " + selectedNodeId);
        networkPanel.setStatusText(api.isRunning() ? "Сеть запущена" : "Сеть остановлена");
        networkPanel.setTopologyText(api.getRingTopology().stream().map(String::valueOf).collect(Collectors.joining(" -> ")));
    }

    private String buildNodeLabel(NodeDto node) {
        int unread = api.getUnreadCount(node.getNodeId());
        return node.getDisplayName() + " (" + node.getNodeId() + ")"
                + " | " + node.getIpAddress() + ":" + node.getPort()
                + (node.isOnline() ? " | online" : " | offline")
                + (node.hasPublicKey() ? " | key" : " | no-key")
                + (unread > 0 ? " | unread " + unread : "");
    }

    private String formatMessage(ChatMessageDto message) {
        String direction = message.isOutgoing() ? "→" : "←";
        return "[" + TIME_FORMAT.format(message.getTimestamp()) + "] " + direction + " "
                + message.getText() + " (" + message.getDeliveryStatus() + ")";
    }

    private void onNodeSelected(NodeDto node) {
        selectedNodeId = node.getNodeId();
        api.markAsRead(node.getNodeId());
        refreshConversation();
        refreshStatus();
    }

    private void sendMessage() {
        if (selectedNodeId == null) {
            showError("Выберите узел для отправки сообщения");
            return;
        }

        String text = chatPanel.getMessageText();
        if (text.isBlank()) {
            return;
        }

        try {
            api.sendMessage(selectedNodeId, text);
            chatPanel.clearMessageText();
            refreshConversation();
            refreshStatus();
        } catch (NetworkException ex) {
            showError("Ошибка отправки сообщения: " + ex.getMessage());
        }
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл для отправки");
        var file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }

        selectedFile = file.toPath();
        filePanel.setSelectedFile(selectedFile);
    }

    private void sendFile() {
        if (selectedNodeId == null) {
            showError("Выберите узел для отправки файла");
            return;
        }
        if (selectedFile == null) {
            showError("Сначала выберите файл");
            return;
        }

        java.io.File file = selectedFile.toFile();
        if (!file.exists()) {
            showError("Выбранный файл не существует");
            return;
        }
        if (file.length() > 100L * 1024 * 1024) {
            showError("Размер файла превышает 100 МБ");
            return;
        }

        try {
            api.sendFile(selectedNodeId, selectedFile);
            refreshTransfers();
        } catch (NetworkException ex) {
            showError("Ошибка отправки файла: " + ex.getMessage());
        }
    }

    private void cancelSelectedTransfer() {
        String transferId = filePanel.getSelectedTransferId();
        if (transferId == null || transferId.isBlank()) {
            showError("Выберите передачу для отмены");
            return;
        }

        api.cancelFileTransfer(transferId);
        refreshTransfers();
    }

    private void handleEvent(ApplicationEvent event) {
        filePanel.appendEventLine("[" + event.getType() + "] " + event.getDescription());

        EventType type = event.getType();
        if (type == EventType.NODE_JOINED || type == EventType.NODE_LEFT || type == EventType.NODE_RECONNECTED || type == EventType.RING_RECONFIGURED) {
            refreshNodes();
        }

        if (type == EventType.MESSAGE_RECEIVED) {
            MessageReceivedEvent received = (MessageReceivedEvent) event;
            long senderId = received.getMessage().getSenderNodeId();
            // Открываем диалог с отправителем, чтобы новое сообщение было видно сразу.
            selectedNodeId = senderId;
            api.markAsRead(senderId);
            refreshNodes();
            refreshConversation();
        }

        if (type == EventType.MESSAGE_DELIVERED || type == EventType.MESSAGE_SEND_ERROR) {
            refreshConversation();
        }

        if (type == EventType.FILE_SEND_STARTED || type == EventType.FILE_TRANSFER_PROGRESS
                || type == EventType.FILE_TRANSFER_COMPLETED || type == EventType.FILE_TRANSFER_ERROR
                || type == EventType.FILE_TRANSFER_CANCELLED) {
            refreshTransfers();
        }

        if (type == EventType.FILE_RECEIVED) {
            refreshTransfers();
        }

        if (type == EventType.NETWORK_STARTED || type == EventType.NETWORK_STOPPED) {
            refreshStatus();
        }

        refreshStatus();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
