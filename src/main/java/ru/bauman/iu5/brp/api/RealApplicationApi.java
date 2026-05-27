package ru.bauman.iu5.brp.api;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PublicKey;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import ru.bauman.iu5.brp.api.dto.ChatMessageDto;
import ru.bauman.iu5.brp.api.dto.DeliveryStatus;
import ru.bauman.iu5.brp.api.dto.ErrorSeverity;
import ru.bauman.iu5.brp.api.dto.FileTransferDto;
import ru.bauman.iu5.brp.api.dto.FileTransferStatus;
import ru.bauman.iu5.brp.api.dto.NetworkError;
import ru.bauman.iu5.brp.api.dto.NetworkException;
import ru.bauman.iu5.brp.api.dto.NetworkStatistics;
import ru.bauman.iu5.brp.api.dto.NodeDto;
import ru.bauman.iu5.brp.api.dto.SignatureStatus;
import ru.bauman.iu5.brp.api.events.ApplicationEvent;
import ru.bauman.iu5.brp.api.events.ApplicationEventListener;
import ru.bauman.iu5.brp.api.events.FileReceivedEvent;
import ru.bauman.iu5.brp.api.events.FileSendStartedEvent;
import ru.bauman.iu5.brp.api.events.FileTransferCancelledEvent;
import ru.bauman.iu5.brp.api.events.FileTransferCompletedEvent;
import ru.bauman.iu5.brp.api.events.FileTransferErrorEvent;
import ru.bauman.iu5.brp.api.events.MessageDeliveredEvent;
import ru.bauman.iu5.brp.api.events.MessageReceivedEvent;
import ru.bauman.iu5.brp.api.events.NetworkErrorEvent;
import ru.bauman.iu5.brp.api.events.NetworkStartedEvent;
import ru.bauman.iu5.brp.api.events.NetworkStoppedEvent;
import ru.bauman.iu5.brp.crypto.CryptoService;
import ru.bauman.iu5.brp.crypto.KeyStorage;
import ru.bauman.iu5.brp.discovery.DiscoveryService;
import ru.bauman.iu5.brp.protocol.ChatMessage;
import ru.bauman.iu5.brp.protocol.MessageType;
import ru.bauman.iu5.brp.ring.NodeInfo;
import ru.bauman.iu5.brp.ring.RingState;
import ru.bauman.iu5.brp.storage.DeliveryTracker;
import ru.bauman.iu5.brp.storage.MessageHistoryStore;
import ru.bauman.iu5.brp.storage.OutboxStore;
import ru.bauman.iu5.brp.transport.RingTransport;

/**
 * Real implementation of ApplicationApi using the P2P network stack.
 * Bridges the network layer with the UI layer via DTOs and events.
 */
public class RealApplicationApi implements ApplicationApi {
    private static final Logger logger = Logger.getLogger(RealApplicationApi.class.getName());

    // Core components
    private RingState ringState;
    private RingTransport transport;
    private CryptoService cryptoService;
    private OutboxStore outboxStore;
    private DeliveryTracker deliveryTracker;
    private DiscoveryService discoveryService;
    private MessageHistoryStore messageHistory;
    private NetworkStatistics statistics;

    // State
    private volatile boolean running = false;
    private long localNodeId;
    private Path downloadDir;
    private String localNodeName = "Node";

    // Event handling
    private final CopyOnWriteArrayList<ApplicationEventListener> listeners = new CopyOnWriteArrayList<>();

    // File transfer tracking
    private final ConcurrentHashMap<String, FileTransferDto> activeTransfers = new ConcurrentHashMap<>();
    private final AtomicLong transferIdCounter = new AtomicLong(0);

    // Recent errors
    private final ConcurrentLinkedDeque<NetworkError> recentErrors = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_ERRORS = 100;

    public RealApplicationApi() {
        this.statistics = new NetworkStatistics();
    }

    @Override
    public synchronized void start(int tcpPort, boolean useUdpDiscovery) throws NetworkException {
        if (running) {
            throw new NetworkException("Network is already running");
        }

        try {
            logger.info("Starting RealApplicationApi...");

            // Generate node ID if not set
            if (localNodeId == 0) {
                localNodeId = System.nanoTime();
            }

            // 1. Initialize RingState
            ringState = new RingState(localNodeId);

            // 2. Initialize crypto
            Path keysDir = Paths.get(System.getProperty("user.home"), ".messenger", "keys");
            KeyStorage keyStorage = new KeyStorage(keysDir);
            cryptoService = new CryptoService(keyStorage);

            // Register self in RingState
            InetAddress localAddr = InetAddress.getLocalHost();
            ringState.updateNode(new NodeInfo(
                    localNodeId,
                    localAddr,
                    tcpPort,
                    System.currentTimeMillis(),
                    cryptoService.getMyEncryptionPublicKey(),
                    cryptoService.getMySigningPublicKey()
            ));

            // 3. Create message history store
            messageHistory = new MessageHistoryStore();

            // 4. Create OutboxStore
            outboxStore = new OutboxStore();

            // 5. Create RingTransport
            transport = new RingTransport(localNodeId, ringState, cryptoService);
            transport.setMessageReceivedCallback(this::handleIncomingMessage);

            // 6. Create DeliveryTracker with retry callback
            deliveryTracker = new DeliveryTracker(outboxStore, message -> {
                try {
                    transport.sendMessage(message);
                    return true;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Retry send failed", e);
                    return false;
                }
            });

            // 7. Start transport
            transport.start();
            logger.info("RingTransport started on TCP port " + tcpPort);

            // 8. Start delivery tracker
            deliveryTracker.start();

            // 9. Start discovery if enabled
            if (useUdpDiscovery) {
                discoveryService = new DiscoveryService(
                        localNodeId,
                        9877,
                        ringState,
                        keyStorage
                );
                discoveryService.start();
                logger.info("DiscoveryService started on UDP port 9876");
            }

            running = true;
            logger.info("RealApplicationApi started successfully");

            // Fire startup event
            fireEvent(new NetworkStartedEvent(tcpPort, useUdpDiscovery));

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start network", e);
            addError(ErrorSeverity.CRITICAL, "Failed to start network: " + e.getMessage());
            throw new NetworkException(ErrorSeverity.CRITICAL, "Failed to start network", e, null);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;

        try {
            logger.info("Stopping RealApplicationApi...");
            running = false;

            if (deliveryTracker != null) deliveryTracker.close();
            if (transport != null) transport.close();
            if (discoveryService != null) discoveryService.stop();
            if (outboxStore != null) outboxStore.close();
            if (messageHistory != null) messageHistory.close();

            fireEvent(new NetworkStoppedEvent());
            logger.info("RealApplicationApi stopped");

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during shutdown", e);
            addError(ErrorSeverity.WARNING, "Error during shutdown: " + e.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void connectToNode(String ipAddress, int port) throws NetworkException {
        if (!running) throw new NetworkException("Network is not running");
        // RingTransport handles connection on-demand
    }

    @Override
    public void disconnectFromNode(long nodeId) {
        // RingTransport manages connections automatically
        // Individual node disconnection is handled by heartbeat timeout
    }

    @Override
    public String sendMessage(long targetNodeId, String text) throws NetworkException {
        if (!running) throw new NetworkException("Network is not running");

        try {
            String messageId = UUID.randomUUID().toString();

            // Create and send
            transport.sendTextMessage(targetNodeId, text);

            // Save to history
            ChatMessageDto dto = ChatMessageDto.outgoing(messageId, targetNodeId, Instant.now(), text);
            messageHistory.saveMessage(dto);

            // Fire event
            fireEvent(new MessageDeliveredEvent(messageId, targetNodeId));

            return messageId;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send message", e);
            addError(ErrorSeverity.ERROR, "Failed to send message", e.getMessage(), targetNodeId);
            throw new NetworkException(ErrorSeverity.ERROR, "Failed to send message", e, targetNodeId);
        }
    }

    @Override
    public String sendFile(long targetNodeId, Path filePath) throws NetworkException {
        if (!running) throw new NetworkException("Network is not running");

        try {
            // Validate
            if (!Files.exists(filePath)) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }
            long fileSize = Files.size(filePath);
            if (fileSize > 100 * 1024 * 1024) {
                throw new IllegalArgumentException("File exceeds 100 MB limit");
            }

            String transferId = UUID.randomUUID().toString();
            String fileName = filePath.getFileName().toString();

            // Create transfer DTO
            FileTransferDto transfer = FileTransferDto.outgoing(transferId, targetNodeId, fileName, fileSize);
            activeTransfers.put(transferId, transfer);

            // Fire start event
            fireEvent(new FileSendStartedEvent(transferId, targetNodeId, fileName, fileSize));

            // Send file asynchronously
            new Thread(() -> {
                try {
                    byte[] fileData = Files.readAllBytes(filePath);
                    transport.sendFile(targetNodeId, fileName, fileData);

                    transfer.setBytesTransferred(fileData.length);
                    transfer.setStatus(FileTransferStatus.COMPLETED);

                    fireEvent(new FileTransferCompletedEvent(transferId, fileName, true));
                    activeTransfers.remove(transferId);

                } catch (Exception e) {
                    logger.log(Level.WARNING, "File transfer error", e);
                    transfer.setStatus(FileTransferStatus.ERROR);
                    transfer.setErrorMessage(e.getMessage());

                    fireEvent(new FileTransferErrorEvent(transferId, fileName, e.getMessage()));
                    activeTransfers.remove(transferId);
                }
            }).start();

            return transferId;

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send file", e);
            throw new NetworkException(ErrorSeverity.ERROR, "Failed to send file", e, targetNodeId);
        }
    }

    @Override
    public void cancelFileTransfer(String transferId) {
        FileTransferDto transfer = activeTransfers.get(transferId);
        if (transfer != null) {
            transfer.setStatus(FileTransferStatus.CANCELLED);
            fireEvent(new FileTransferCancelledEvent(transferId, transfer.getFileName()));
            activeTransfers.remove(transferId);
        }
    }

    @Override
    public List<NodeDto> getAllNodes() {
        if (ringState == null) return Collections.emptyList();

        List<NodeDto> nodes = new ArrayList<>();
        for (NodeInfo info : ringState.getAllNodes()) {
            NodeDto dto = new NodeDto(
                    info.nodeId(),
                    "Node_" + info.nodeId(),
                    info.address().getHostAddress(),
                    info.port(),
                    !info.isExpired(System.currentTimeMillis(), 10000)
            );
            dto.setHasPublicKey(info.hasKeys());
            nodes.add(dto);
        }
        return nodes;
    }

    @Override
    public List<NodeDto> getOnlineNodes() {
        return getAllNodes().stream()
                .filter(NodeDto::isOnline)
                .toList();
    }

    @Override
    public Optional<NodeDto> getNodeById(long nodeId) {
        if (ringState == null) return Optional.empty();

        NodeInfo info = ringState.getNode(nodeId);
        if (info == null) return Optional.empty();

        NodeDto dto = new NodeDto(
                info.nodeId(),
                "Node_" + info.nodeId(),
                info.address().getHostAddress(),
                info.port(),
                !info.isExpired(System.currentTimeMillis(), 10000)
        );
        dto.setHasPublicKey(info.hasKeys());
        return Optional.of(dto);
    }

    @Override
    public long getLocalNodeId() {
        return localNodeId;
    }

    @Override
    public List<Long> getRingTopology() {
        if (ringState == null) return Collections.emptyList();

        List<Long> topology = new ArrayList<>(ringState.getAllNodes().stream()
                .map(NodeInfo::nodeId)
                .toList());
        Collections.sort(topology);
        return topology;
    }

    @Override
    public List<ChatMessageDto> getMessageHistory(long nodeId, int limit, int offset) {
        try {
            return messageHistory.getHistory(localNodeId, nodeId, limit, offset);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get message history", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<ChatMessageDto> getLastMessages() {
        try {
            return new ArrayList<>(messageHistory.getLastMessagesPerPeer(localNodeId).values());
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get last messages", e);
            return Collections.emptyList();
        }
    }

    @Override
    public int getUnreadCount(long nodeId) {
        try {
            return messageHistory.getUnreadCount(localNodeId, nodeId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to get unread count", e);
            return 0;
        }
    }

    @Override
    public void markAsRead(long nodeId) {
        try {
            messageHistory.markAsRead(localNodeId, nodeId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to mark as read", e);
        }
    }

    @Override
    public void deleteHistory(long nodeId) {
        try {
            messageHistory.deleteHistory(localNodeId, nodeId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to delete history", e);
        }
    }

    @Override
    public Optional<DeliveryStatus> getMessageDeliveryStatus(String messageId) {
        // Would need to track sent messages - for now return empty
        return Optional.empty();
    }

    @Override
    public Optional<FileTransferDto> getFileTransferProgress(String transferId) {
        return Optional.ofNullable(activeTransfers.get(transferId));
    }

    @Override
    public List<FileTransferDto> getActiveFileTransfers() {
        return new ArrayList<>(activeTransfers.values());
    }

    @Override
    public void setDownloadDirectory(Path directory) {
        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }
        this.downloadDir = directory;
    }

    @Override
    public Path getDownloadDirectory() {
        if (downloadDir == null) {
            downloadDir = Paths.get(System.getProperty("user.home"), ".messenger", "downloads");
            try {
                Files.createDirectories(downloadDir);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to create downloads directory", e);
            }
        }
        return downloadDir;
    }

    @Override
    public void setLocalNodeName(String name) {
        this.localNodeName = name;
    }

    @Override
    public String getLocalNodeName() {
        return localNodeName;
    }

    @Override
    public KeyPair getLocalKeyPair() {
        if (cryptoService == null) return null;
        // Would need to expose from CryptoService
        return null; // TODO: implement
    }

    @Override
    public Optional<PublicKey> getNodePublicKey(long nodeId) {
        if (ringState == null) return Optional.empty();
        PublicKey key = ringState.getEncryptionPublicKey(nodeId);
        return Optional.ofNullable(key);
    }

    @Override
    public boolean hasPublicKey(long nodeId) {
        if (ringState == null) return false;
        NodeInfo info = ringState.getNode(nodeId);
        return info != null && info.hasKeys();
    }

    @Override
    public void addEventListener(ApplicationEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEventListener(ApplicationEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public NetworkStatistics getNetworkStatistics() {
        if (ringState != null) {
            statistics.setCurrentRingSize(ringState.getActiveNodeCount() + 1);
        }
        return statistics;
    }

    @Override
    public List<NetworkError> getRecentErrors(int limit) {
        return recentErrors.stream()
                .limit(limit > 0 ? limit : recentErrors.size())
                .toList();
    }

    // === Private helpers ===

    private void handleIncomingMessage(ChatMessage message, byte[] decryptedContent) {
        try {
            Instant timestamp = Instant.ofEpochMilli(message.getTimestamp());
            String messageId = UUID.randomUUID().toString(); // Generate ID for UI

            if (message.getType() == MessageType.TEXT) {
                String text = new String(decryptedContent);
                ChatMessageDto dto = new ChatMessageDto(
                        messageId,
                        message.getSenderId(),
                        message.getRecipientId(),
                        timestamp,
                        text,
                        message.getSignatureStatus() != null ?
                                SignatureStatus.valueOf(message.getSignatureStatus().name()) :
                                SignatureStatus.NOT_APPLICABLE
                );
                messageHistory.saveMessage(dto);
                fireEvent(new MessageReceivedEvent(dto));

            } else if (message.getType() == MessageType.FILE) {
                String fileName = message.getFileName();
                Path savePath = getDownloadDirectory().resolve(fileName);
                Files.write(savePath, decryptedContent);

                String transferId = UUID.randomUUID().toString();
                FileTransferDto transfer = FileTransferDto.incoming(
                        transferId,
                        message.getSenderId(),
                        fileName,
                        decryptedContent.length
                );
                transfer.setStatus(FileTransferStatus.COMPLETED);
                fireEvent(new FileReceivedEvent(transferId, message.getSenderId(), fileName, decryptedContent.length, savePath));
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error handling incoming message", e);
            addError(ErrorSeverity.ERROR, "Error handling incoming message: " + e.getMessage());
        }
    }

    private void fireEvent(ApplicationEvent event) {
        for (ApplicationEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error firing event", e);
            }
        }
    }

    private void addError(ErrorSeverity severity, String message, String details, Long nodeId) {
        NetworkError error = new NetworkError(severity, message, details, nodeId);
        recentErrors.addFirst(error);
        if (recentErrors.size() > MAX_RECENT_ERRORS) {
            recentErrors.removeLast();
        }
        statistics.incrementTotalErrors();
        fireEvent(new NetworkErrorEvent(severity, message, details, null));
    }

    private void addError(ErrorSeverity severity, String message) {
        addError(severity, message, null, null);
    }
}
