package ru.bauman.iu5.brp.api.mock;

import ru.bauman.iu5.brp.api.ApplicationApi;
import ru.bauman.iu5.brp.api.dto.*;
import ru.bauman.iu5.brp.api.events.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Mock реализация ApplicationApi для разработки UI без реального сетевого стека.
 * Имитирует все сетевые операции с задержками и генерирует события.
 */
public class MockApplicationApi implements ApplicationApi {

    private final MockDataGenerator dataGenerator = new MockDataGenerator();

    // Состояние
    private boolean running = false;
    private int tcpPort;
    private boolean udpDiscoveryEnabled;
    private final long myNodeId = 99999L;
    private String myNodeName = "Ilya";

    // Данные
    private final List<NodeDto> nodes = new CopyOnWriteArrayList<>();
    private final Map<Long, List<ChatMessageDto>> messageHistory = new ConcurrentHashMap<>();
    private final Map<String, ChatMessageDto> messagesById = new ConcurrentHashMap<>();
    private final Map<String, FileTransferDto> activeTransfers = new ConcurrentHashMap<>();
    private final Map<Long, PublicKey> publicKeys = new ConcurrentHashMap<>();
    private KeyPair localKeyPair;

    // События
    private final List<ApplicationEventListener> listeners = new CopyOnWriteArrayList<>();

    // Настройки
    private Path downloadDirectory = Paths.get(System.getProperty("user.home"), "Downloads");

    // Статистика
    private final NetworkStatistics statistics = new NetworkStatistics();
    private final List<NetworkError> recentErrors = new CopyOnWriteArrayList<>();

    // Планировщик для имитации асинхронных операций
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Random random = ThreadLocalRandom.current();

    public MockApplicationApi() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            localKeyPair = keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка генерации ключей", e);
        }
    }

    // ========================================================================================
    // Управление сетью
    // ========================================================================================

    @Override
    public void start(int tcpPort, boolean useUdpDiscovery) throws NetworkException {
        if (running) {
            throw new NetworkException("Сеть уже запущена");
        }

        this.tcpPort = tcpPort;
        this.udpDiscoveryEnabled = useUdpDiscovery;
        this.running = true;

        fireEvent(new NetworkStartedEvent(tcpPort, useUdpDiscovery));

        if (useUdpDiscovery) {
            // Имитация обнаружения узлов через UDP Broadcast
            simulateNodeDiscovery();
        }

        // Периодическая генерация входящих сообщений
        startIncomingMessageSimulation();
    }

    @Override
    public void stop() {
        if (!running) return;

        running = false;
        scheduler.shutdownNow();

        // Отправка Uplink-кадров (имитация)
        for (NodeDto node : nodes) {
            if (node.isOnline()) {
                node.setOnline(false);
                fireEvent(new NodeLeftEvent(node.getNodeId(), node.getName(), true));
            }
        }

        fireEvent(new NetworkStoppedEvent());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void connectToNode(String ipAddress, int port) throws NetworkException {
        if (!running) {
            throw new NetworkException("Сеть не запущена");
        }

        // Имитация подключения с задержкой
        scheduler.schedule(() -> {
            NodeDto newNode = new NodeDto(
                    10000L + random.nextInt(90000),
                    "Node_" + ipAddress.split("\\.")[3],
                    ipAddress,
                    port,
                    true
            );

            nodes.add(newNode);
            statistics.setCurrentRingSize(nodes.size());

            fireEvent(new NodeJoinedEvent(newNode));
            fireEvent(new RingReconfiguredEvent(getRingTopology(), nodes.size() - 1));

            // Имитация обмена ключами
            scheduler.schedule(() -> {
                try {
                    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
                    keyGen.initialize(2048);
                    publicKeys.put(newNode.getNodeId(), keyGen.generateKeyPair().getPublic());
                    newNode.setHasPublicKey(true);

                    String fingerprint = "SHA256:" + UUID.randomUUID().toString().substring(0, 16);
                    fireEvent(new PublicKeyReceivedEvent(newNode.getNodeId(), fingerprint));
                } catch (Exception e) {
                    // ignore
                }
            }, 500, TimeUnit.MILLISECONDS);

        }, 1000 + random.nextInt(2000), TimeUnit.MILLISECONDS);
    }

    @Override
    public void disconnectFromNode(long nodeId) {
        nodes.stream()
                .filter(n -> n.getNodeId() == nodeId)
                .findFirst()
                .ifPresent(node -> {
                    node.setOnline(false);
                    fireEvent(new NodeLeftEvent(nodeId, node.getName(), true));
                    fireEvent(new RingReconfiguredEvent(getRingTopology(), nodes.size()));
                    statistics.setCurrentRingSize((int) nodes.stream().filter(NodeDto::isOnline).count());
                });
    }

    // ========================================================================================
    // Отправка данных
    // ========================================================================================

    @Override
    public String sendMessage(long targetNodeId, String text) throws NetworkException {
        if (!running) {
            throw new NetworkException("Сеть не запущена");
        }

        Optional<NodeDto> targetNode = nodes.stream()
                .filter(n -> n.getNodeId() == targetNodeId)
                .findFirst();

        if (targetNode.isEmpty() || !targetNode.get().isOnline()) {
            throw new NetworkException("Узел " + targetNodeId + " недоступен");
        }

        String messageId = UUID.randomUUID().toString();
        ChatMessageDto message = ChatMessageDto.outgoing(messageId, targetNodeId, Instant.now(), text);

        messagesById.put(messageId, message);
        messageHistory.computeIfAbsent(targetNodeId, k -> new CopyOnWriteArrayList<>()).add(message);

        statistics.incrementMessagesDelivered();
        statistics.addBytesSent(text.getBytes().length);

        // Имитация доставки с задержкой 1-3 секунды
        scheduler.schedule(() -> {
            if (random.nextDouble() > 0.1) { // 90% успеха
                message.setDeliveryStatus(DeliveryStatus.DELIVERED);
                fireEvent(new MessageDeliveredEvent(messageId, targetNodeId));
            } else {
                message.setDeliveryStatus(DeliveryStatus.ERROR);
                fireEvent(new MessageSendErrorEvent(messageId, targetNodeId, "Таймаут доставки"));
                statistics.incrementTotalErrors();
            }
        }, 1000 + random.nextInt(2000), TimeUnit.MILLISECONDS);

        return messageId;
    }

    @Override
    public String sendFile(long targetNodeId, Path filePath) throws NetworkException {
        if (!running) {
            throw new NetworkException("Сеть не запущена");
        }

        Optional<NodeDto> targetNode = nodes.stream()
                .filter(n -> n.getNodeId() == targetNodeId)
                .findFirst();

        if (targetNode.isEmpty() || !targetNode.get().isOnline()) {
            throw new NetworkException("Узел " + targetNodeId + " недоступен");
        }

        String transferId = UUID.randomUUID().toString();
        String fileName = filePath.getFileName().toString();
        long fileSize = dataGenerator.generateFileSize();

        // Проверка лимита 100 МБ
        if (fileSize > 100L * 1024 * 1024) {
            throw new IllegalArgumentException("Размер файла превышает 100 МБ");
        }

        FileTransferDto transfer = FileTransferDto.outgoing(transferId, targetNodeId, fileName, fileSize);
        activeTransfers.put(transferId, transfer);

        fireEvent(new FileSendStartedEvent(transferId, targetNodeId, fileName, fileSize));

        // Имитация прогресса передачи
        simulateFileTransfer(transfer);

        return transferId;
    }

    @Override
    public void cancelFileTransfer(String transferId) {
        FileTransferDto transfer = activeTransfers.get(transferId);
        if (transfer != null && transfer.getStatus().isActive()) {
            transfer.setStatus(FileTransferStatus.CANCELLED);
            activeTransfers.remove(transferId);
            fireEvent(new FileTransferCancelledEvent(transferId, transfer.getFileName()));
        }
    }

    // ========================================================================================
    // Получение данных о сети
    // ========================================================================================

    @Override
    public List<NodeDto> getAllNodes() {
        return new ArrayList<>(nodes);
    }

    @Override
    public List<NodeDto> getOnlineNodes() {
        return nodes.stream()
                .filter(NodeDto::isOnline)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<NodeDto> getNodeById(long nodeId) {
        return nodes.stream()
                .filter(n -> n.getNodeId() == nodeId)
                .findFirst();
    }

    @Override
    public long getLocalNodeId() {
        return myNodeId;
    }

    @Override
    public List<Long> getRingTopology() {
        return nodes.stream()
                .filter(NodeDto::isOnline)
                .map(NodeDto::getNodeId)
                .sorted()
                .collect(Collectors.toList());
    }

    // ========================================================================================
    // История сообщений
    // ========================================================================================

    @Override
    public List<ChatMessageDto> getMessageHistory(long nodeId, int limit, int offset) {
        List<ChatMessageDto> history = messageHistory.getOrDefault(nodeId, Collections.emptyList());

        int start = Math.min(offset, history.size());
        int end = limit > 0 ? Math.min(start + limit, history.size()) : history.size();

        return new ArrayList<>(history.subList(start, end));
    }

    @Override
    public List<ChatMessageDto> getLastMessages() {
        return messageHistory.entrySet().stream()
                .map(entry -> {
                    List<ChatMessageDto> messages = entry.getValue();
                    return messages.isEmpty() ? null : messages.get(messages.size() - 1);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public int getUnreadCount(long nodeId) {
        return (int) messageHistory.getOrDefault(nodeId, Collections.emptyList()).stream()
                .filter(msg -> !msg.isOutgoing() && !msg.isRead())
                .count();
    }

    @Override
    public void markAsRead(long nodeId) {
        messageHistory.getOrDefault(nodeId, Collections.emptyList()).stream()
                .filter(msg -> !msg.isOutgoing())
                .forEach(msg -> msg.setRead(true));
    }

    @Override
    public void deleteHistory(long nodeId) {
        messageHistory.remove(nodeId);
    }

    // ========================================================================================
    // Статусы доставки
    // ========================================================================================

    @Override
    public Optional<DeliveryStatus> getMessageDeliveryStatus(String messageId) {
        return Optional.ofNullable(messagesById.get(messageId))
                .map(ChatMessageDto::getDeliveryStatus);
    }

    @Override
    public Optional<FileTransferDto> getFileTransferProgress(String transferId) {
        return Optional.ofNullable(activeTransfers.get(transferId));
    }

    @Override
    public List<FileTransferDto> getActiveFileTransfers() {
        return activeTransfers.values().stream()
                .filter(t -> t.getStatus().isActive())
                .collect(Collectors.toList());
    }

    // ========================================================================================
    // Настройки
    // ========================================================================================

    @Override
    public void setDownloadDirectory(Path directory) {
        this.downloadDirectory = directory;
    }

    @Override
    public Path getDownloadDirectory() {
        return downloadDirectory;
    }

    @Override
    public void setLocalNodeName(String name) {
        this.myNodeName = name;
    }

    @Override
    public String getLocalNodeName() {
        return myNodeName;
    }

    // ========================================================================================
    // Криптография
    // ========================================================================================

    @Override
    public KeyPair getLocalKeyPair() {
        return localKeyPair;
    }

    @Override
    public Optional<PublicKey> getNodePublicKey(long nodeId) {
        return Optional.ofNullable(publicKeys.get(nodeId));
    }

    @Override
    public boolean hasPublicKey(long nodeId) {
        return publicKeys.containsKey(nodeId);
    }

    // ========================================================================================
    // События
    // ========================================================================================

    @Override
    public void addEventListener(ApplicationEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEventListener(ApplicationEventListener listener) {
        listeners.remove(listener);
    }

    // ========================================================================================
    // Диагностика
    // ========================================================================================

    @Override
    public NetworkStatistics getNetworkStatistics() {
        return statistics;
    }

    @Override
    public List<NetworkError> getRecentErrors(int limit) {
        int size = recentErrors.size();
        int start = Math.max(0, size - limit);
        return new ArrayList<>(recentErrors.subList(start, size));
    }

    // ========================================================================================
    // Вспомогательные методы
    // ========================================================================================

    private void fireEvent(ApplicationEvent event) {
        listeners.forEach(listener -> listener.onEvent(event));
    }

    private void simulateNodeDiscovery() {
        // Генерация 2-3 узлов с задержкой
        int nodeCount = 2 + random.nextInt(2);

        for (int i = 0; i < nodeCount; i++) {
            final int index = i;
            scheduler.schedule(() -> {
                NodeDto node = dataGenerator.generateNode();
                node.setOnline(true);
                nodes.add(node);

                statistics.setCurrentRingSize(nodes.size());

                fireEvent(new NodeJoinedEvent(node));
                fireEvent(new RingReconfiguredEvent(getRingTopology(), nodes.size() - 1));

                // Предзаполнение истории для первого узла
                if (index == 0) {
                    List<ChatMessageDto> history = dataGenerator.generateMessageHistory(
                            node.getNodeId(), myNodeId, 10
                    );
                    messageHistory.put(node.getNodeId(), new CopyOnWriteArrayList<>(history));
                }

            }, (2 + i * 3) * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    private void startIncomingMessageSimulation() {
        // Периодическая генерация входящих сообщений (каждые 15-30 секунд)
        scheduler.scheduleAtFixedRate(() -> {
            if (!running || nodes.isEmpty()) return;

            List<NodeDto> onlineNodes = getOnlineNodes();
            if (onlineNodes.isEmpty()) return;

            NodeDto sender = dataGenerator.randomElement(onlineNodes);
            ChatMessageDto message = dataGenerator.generateIncomingMessage(sender.getNodeId(), myNodeId);

            messageHistory.computeIfAbsent(sender.getNodeId(), k -> new CopyOnWriteArrayList<>()).add(message);
            messagesById.put(message.getMessageId(), message);

            statistics.incrementMessagesReceived();
            statistics.addBytesReceived(message.getText().getBytes().length);

            fireEvent(new MessageReceivedEvent(message));

        }, 15, 15 + random.nextInt(15), TimeUnit.SECONDS);
    }

    private void simulateFileTransfer(FileTransferDto transfer) {
        long chunkSize = 512 * 1024; // 512 КБ за итерацию
        long updateInterval = 300; // Обновление каждые 300 мс

        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (transfer.getStatus() != FileTransferStatus.UPLOADING) {
                    return;
                }

                long newProgress = Math.min(
                        transfer.getBytesTransferred() + chunkSize,
                        transfer.getFileSize()
                );
                transfer.setBytesTransferred(newProgress);

                fireEvent(new FileTransferProgressEvent(transfer));

                if (newProgress >= transfer.getFileSize()) {
                    transfer.setStatus(FileTransferStatus.COMPLETED);
                    activeTransfers.remove(transfer.getTransferId());
                    statistics.incrementFilesDelivered();

                    fireEvent(new FileTransferCompletedEvent(
                            transfer.getTransferId(),
                            transfer.getFileName(),
                            true
                    ));
                }
            }
        }, updateInterval, updateInterval, TimeUnit.MILLISECONDS);
    }
}
