package messenger.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

import messenger.crypto.CryptoService;
import messenger.crypto.KeyPairManager;
import messenger.crypto.KeyStorage;
import messenger.discovery.DiscoveryService;
import messenger.protocol.ChatMessage;
import messenger.protocol.MessageType;
import messenger.ring.NodeInfo;
import messenger.ring.RingState;
import messenger.storage.DeliveryTracker;
import messenger.storage.OutboxStore;
import messenger.transport.RingTransport;

/**
 * Главный класс приложения P2P Messenger.
 * <p>
 * Кольцевая топология с UDP Discovery, TCP транспортом,
 * E2E шифрованием и персистентной очередью сообщений.
 */
public final class Main {
    private static final Logger logger = Logger.getLogger(Main.class.getName());

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int UDP_DISCOVERY_PORT = 9876;
    private static final String DOWNLOADS_DIR = System.getProperty("user.home") + "/.messenger/downloads";
        private static final String KEYS_DIR = System.getProperty("user.home") + "/.messenger/keys";

    private static volatile boolean running = true;

    public static void main(String[] args) {
        printBanner();

        // Парсинг аргументов
        if (args.length < 1) {
            System.err.println("Usage: java -jar messenger.jar <nodeId>");
            System.exit(1);
        }

        long myNodeId = Long.parseLong(args[0]);

        DiscoveryService discovery = null;
        RingTransport transport = null;
        OutboxStore outboxStore = null;
        DeliveryTracker deliveryTracker = null;

        try {
            System.out.println("[" + formatTime() + "] Starting node " + myNodeId + "...");

            // 1. Инициализация RingState
            RingState ringState = new RingState(myNodeId);
            System.out.println("[" + formatTime() + "] RingState initialized");

            // 2. Инициализация криптографии
            KeyStorage keyStorage = new KeyStorage(Paths.get(KEYS_DIR));
            CryptoService cryptoService = new CryptoService(keyStorage);
            KeyPairManager keyPairManager = new KeyPairManager();

            // Регистрируем свои ключи в RingState
            ringState.updateNode(new NodeInfo(
                    myNodeId,
                    InetAddress.getByName("localhost"),
                    9877,
                    System.currentTimeMillis(),
                    cryptoService.getMyEncryptionPublicKey(),
                    cryptoService.getMySigningPublicKey()
            ));

            System.out.println("[" + formatTime() + "] Cryptography initialized");
            System.out.println("  Encryption key: " +
                    keyPairManager.publicKeyToBase64(cryptoService.getMyEncryptionPublicKey()).substring(0, 20) + "...");
            System.out.println("  Signing key: " +
                    keyPairManager.publicKeyToBase64(cryptoService.getMySigningPublicKey()).substring(0, 20) + "...");

            // 3. Создание downloads директории
            ensureDownloadsDirectory();

            // 4. Инициализация OutboxStore (персистентная очередь)
            outboxStore = new OutboxStore();
            System.out.println("[" + formatTime() + "] OutboxStore initialized (queue size: " +
                    outboxStore.getQueueSize() + ")");

            // 5. Создание RingTransport
            transport = new RingTransport(myNodeId, ringState, cryptoService);
            final RingTransport finalTransport = transport;

            // 6. Создание DeliveryTracker с retry callback
            deliveryTracker = new DeliveryTracker(outboxStore, message -> {
                try {
                    finalTransport.sendMessage(message);
                    return true;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Retry send failed", e);
                    return false;
                }
            });

            // 7. Установка callback для входящих сообщений
            transport.setMessageReceivedCallback(Main::handleIncomingMessage);

            // 8. Запуск транспорта
            transport.start();
            System.out.println("[" + formatTime() + "] RingTransport started on TCP port 9877");

            // 9. Запуск DeliveryTracker
            deliveryTracker.start();
            System.out.println("[" + formatTime() + "] DeliveryTracker started");

            // 10. Запуск UDP Discovery
            discovery = new DiscoveryService(
                    myNodeId,
                    UDP_DISCOVERY_PORT,
                    ringState,
                    keyStorage
            );
            discovery.start();
            System.out.println("[" + formatTime() + "] DiscoveryService started on UDP port " + UDP_DISCOVERY_PORT);

            // 11. Graceful shutdown hook
            final DiscoveryService finalDiscovery = discovery;
            final OutboxStore finalOutbox = outboxStore;
            final DeliveryTracker finalTracker = deliveryTracker;

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[" + formatTime() + "] Shutting down...");
                running = false;

                try {
                    if (finalTracker != null) finalTracker.close();
                    if (finalTransport != null) finalTransport.close();
                    if (finalDiscovery != null) finalDiscovery.stop();
                    if (finalOutbox != null) finalOutbox.close();
                } catch (Exception e) {
                    System.err.println("Error during shutdown: " + e.getMessage());
                }

                System.out.println("[" + formatTime() + "] Shutdown complete");
            }));

            System.out.println("\n" + "=".repeat(60));
            System.out.println("Node " + myNodeId + " is ready!");
            System.out.println("=".repeat(60) + "\n");

            // 12. Консольный интерфейс
            runConsole(myNodeId, ringState, transport, outboxStore);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Fatal error", e);
            e.printStackTrace();
        } finally {
            running = false;

            try {
                if (deliveryTracker != null) deliveryTracker.close();
                if (transport != null) transport.close();
                if (discovery != null) discovery.stop();
                if (outboxStore != null) outboxStore.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error during cleanup", e);
            }
        }
    }

    /**
     * Консольный интерфейс
     */
    private static void runConsole(long myNodeId, RingState ringState,
                                   RingTransport transport, OutboxStore outboxStore) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        printHelp();

        while (running) {
            try {
                System.out.print("> ");
                String line = reader.readLine();

                if (line == null || line.trim().isEmpty()) {
                    continue;
                }

                String trimmed = line.trim();

                // Команды управления
                if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
                    System.out.println("Exiting...");
                    System.exit(0);
                }

                if (trimmed.equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                }

                if (trimmed.equalsIgnoreCase("topology")) {
                    printTopology(ringState);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("info")) {
                    printInfo(myNodeId, ringState, outboxStore);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("nodes")) {
                    printNodes(ringState);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("queue")) {
                    printQueueStatus(outboxStore);
                    continue;
                }

                // Команда: /to <nodeId> <text>
                if (trimmed.startsWith("/to ")) {
                    String[] parts = trimmed.substring(4).split(" ", 2);
                    if (parts.length < 2) {
                        System.out.println("Usage: /to <nodeId> <text>");
                        continue;
                    }

                    long recipientId = Long.parseLong(parts[0]);
                    String text = parts[1];

                    transport.sendTextMessage(recipientId, text);
                    System.out.println("✓ Message sent to node " + recipientId);
                    continue;
                }

                // Команда: /file <nodeId> <path>
                if (trimmed.startsWith("/file ")) {
                    String[] parts = trimmed.substring(6).split(" ", 2);
                    if (parts.length < 2) {
                        System.out.println("Usage: /file <nodeId> <path>");
                        continue;
                    }

                    long recipientId = Long.parseLong(parts[0]);
                    String filePath = parts[1];

                    try {
                        Path path = Paths.get(filePath);
                        if (!Files.exists(path)) {
                            System.out.println("✗ File not found: " + filePath);
                            continue;
                        }

                        byte[] fileData = Files.readAllBytes(path);
                        String fileName = path.getFileName().toString();

                        transport.sendFile(recipientId, fileName, fileData);
                        System.out.println("✓ File sent to node " + recipientId + ": " +
                                fileName + " (" + fileData.length + " bytes)");

                    } catch (IOException e) {
                        System.out.println("✗ Failed to read file: " + e.getMessage());
                    }
                    continue;
                }

                // Команда: /broadcast <text>
                if (trimmed.startsWith("/broadcast ")) {
                    String text = trimmed.substring(11);

                    // Отправляем всем известным узлам
                    int sent = 0;
                    for (NodeInfo node : ringState.getAllNodes()) {
                        if (node.nodeId() != myNodeId) {
                            transport.sendTextMessage(node.nodeId(), text);
                            sent++;
                        }
                    }

                    System.out.println("✓ Broadcast sent to " + sent + " nodes");
                    continue;
                }

                System.out.println("Unknown command. Type 'help' for available commands.");

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }

    /**
     * Обработка входящего сообщения
     */
    private static void handleIncomingMessage(ChatMessage message, byte[] decryptedContent) {
        String time = formatTime();

        if (message.getType() == MessageType.TEXT) {
            String text = new String(decryptedContent, StandardCharsets.UTF_8);

            System.out.println("\n┌─────────────────────────────────────────────");
            System.out.println("│ [" + time + "] Message from node " + message.getSenderId());
            System.out.println("│ Text: " + text);
            System.out.println("│ Signature: " + message.getSignatureStatus());
            System.out.println("│ Delivery: " + message.getDeliveryStatus());
            System.out.println("└─────────────────────────────────────────────");

        } else if (message.getType() == MessageType.FILE) {
            try {
                Path savePath = Paths.get(DOWNLOADS_DIR, message.getFileName());
                Files.write(savePath, decryptedContent);

                System.out.println("\n┌─────────────────────────────────────────────");
                System.out.println("│ [" + time + "] File from node " + message.getSenderId());
                System.out.println("│ Name: " + message.getFileName());
                System.out.println("│ Size: " + decryptedContent.length + " bytes");
                System.out.println("│ Saved: " + savePath);
                System.out.println("│ Signature: " + message.getSignatureStatus());
                System.out.println("└─────────────────────────────────────────────");

            } catch (IOException e) {
                System.err.println("✗ Failed to save file: " + e.getMessage());
            }
        }

        System.out.print("> ");
    }

    /**
     * Вывод баннера
     */
    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   P2P Messenger - Ring Topology with E2E Encryption     ║");
        System.out.println("║   " + Instant.now() + "                            ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    /**
     * Вывод справки
     */
    private static void printHelp() {
        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║ Available Commands:                                      ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║ /to <nodeId> <text>       - Send encrypted message       ║");
        System.out.println("║ /file <nodeId> <path>     - Send encrypted file          ║");
        System.out.println("║ /broadcast <text>         - Broadcast to all nodes       ║");
        System.out.println("║                                                          ║");
        System.out.println("║ topology                  - Show ring topology           ║");
        System.out.println("║ nodes                     - List all nodes with keys     ║");
        System.out.println("║ info                      - Show node information        ║");
        System.out.println("║ queue                     - Show outbox queue status     ║");
        System.out.println("║                                                          ║");
        System.out.println("║ help                      - Show this help               ║");
        System.out.println("║ exit                      - Exit application             ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");
    }

    /**
     * Вывод топологии кольца
     */
    private static void printTopology(RingState ringState) {
        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║ Ring Topology:                                           ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");

        Long left = ringState.getLeftNeighbor();
        Long right = ringState.getRightNeighbor();

        if (left != null) {
            System.out.printf("║ Left neighbor:  %-38d ║%n", left);
        } else {
            System.out.println("║ Left neighbor:  none                                     ║");
        }

        System.out.printf("║ My node ID:     %-38d ║%n", ringState.getMyNodeId());

        if (right != null) {
            System.out.printf("║ Right neighbor: %-38d ║%n", right);
        } else {
            System.out.println("║ Right neighbor: none                                     ║");
        }

        System.out.printf("║ Total nodes:    %-38d ║%n", ringState.getActiveNodeCount() + 1);
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    /**
     * Вывод списка узлов
     */
    private static void printNodes(RingState ringState) {
        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║ Known Nodes:                                             ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");

        for (NodeInfo node : ringState.getAllNodes()) {
            String hasKeys = node.hasKeys() ? "✓ keys" : "✗ no keys";
            System.out.printf("║ Node %-10d | %s:%-5d | %-15s ║%n",
                    node.nodeId(),
                    node.address(),
                    node.port(),
                    hasKeys
            );
        }

        if (ringState.getAllNodes().isEmpty()) {
            System.out.println("║ No other nodes discovered yet                            ║");
        }

        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    /**
     * Вывод информации о ноде
     */
    private static void printInfo(long myNodeId, RingState ringState, OutboxStore outboxStore) {
        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║ Node Information:                                        ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf("║ Node ID:        %-38d ║%n", myNodeId);
        System.out.printf("║ Known nodes:    %-38d ║%n", ringState.getActiveNodeCount());

        try {
            int queueSize = outboxStore.getQueueSize();
            OutboxStore.QueueStats stats = outboxStore.getStats();

            System.out.printf("║ Outbox queue:   %-38d ║%n", queueSize);
            System.out.printf("║ Fresh messages: %-38d ║%n", stats.fresh());
            System.out.printf("║ Retrying:       %-38d ║%n", stats.retrying());
            System.out.printf("║ Avg retries:    %-38.2f ║%n", stats.avgRetries());

        } catch (SQLException e) {
            System.out.println("║ Outbox queue:   error                                    ║");
        }

        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    /**
     * Вывод статуса очереди
     */
    private static void printQueueStatus(OutboxStore outboxStore) {
        try {
            OutboxStore.QueueStats stats = outboxStore.getStats();

            System.out.println("\n╔══════════════════════════════════════════════════════════╗");
            System.out.println("║ Outbox Queue Status:                                     ║");
            System.out.println("╠══════════════════════════════════════════════════════════╣");
            System.out.printf("║ Total messages:    %-34d ║%n", stats.total());
            System.out.printf("║ Fresh (retry=0):   %-34d ║%n", stats.fresh());
            System.out.printf("║ Retrying (retry>0): %-33d ║%n", stats.retrying());
            System.out.printf("║ Average retries:   %-34.2f ║%n", stats.avgRetries());
            System.out.println("╚══════════════════════════════════════════════════════════╝");

        } catch (SQLException e) {
            System.err.println("✗ Failed to get queue stats: " + e.getMessage());
        }
    }

    /**
     * Создание директории для загрузок
     */
    private static void ensureDownloadsDirectory() {
        try {
            Path dir = Paths.get(DOWNLOADS_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                System.out.println("[" + formatTime() + "] Created downloads directory: " + dir);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to create downloads directory", e);
        }
    }

    /**
     * Форматирование времени
     */
    private static String formatTime() {
        return TIME_FORMATTER.format(Instant.now());
    }
}