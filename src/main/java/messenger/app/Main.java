package messenger.app;

import messenger.crypto.CryptoService;
import messenger.crypto.KeyStorage;
import messenger.discovery.UdpDiscovery;
import messenger.file.FileTransferService;
import messenger.nio.NioEventLoop;
import messenger.protocol.ChatMessage;
import messenger.protocol.MessageType;
import messenger.queue.OutboxStore;
import messenger.ring.RingState;
import messenger.transport.RingTransport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Главный класс приложения BRP Messenger.
 * <p>
 * Кольцевая топология с UDP Discovery и TCP транспортом.
 * Поддержка шифрования и передачи файлов.
 */
public final class Main {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int TCP_PORT = 8080;  // Константа для TCP порта

    private static volatile boolean running = true;

    public static void main(String[] args) {
        printBanner();

        NioEventLoop eventLoop = null;
        UdpDiscovery discovery = null;
        RingTransport transport = null;
        OutboxStore outboxStore = null;

        try {
            // 1. Создание NIO Event Loop
            eventLoop = new NioEventLoop();
            Thread eventThread = new Thread(eventLoop, "NIO-EventLoop");
            eventThread.setDaemon(true);
            eventThread.start();
            System.out.println("[" + formatTime() + "] NioEventLoop started");

            // 2. Создание RingState
            RingState ringState = new RingState();
            long myNodeId = ringState.myId();
            System.out.println("[" + formatTime() + "] Node ID: " + myNodeId);

            // 3. Инициализация криптографии
            KeyStorage keyStorage = new KeyStorage();
            CryptoService cryptoService = new CryptoService(keyStorage);

            // 4. Инициализация FileTransferService
            FileTransferService fileService = new FileTransferService();

            // 5. Создание OutboxStore (H2)
            outboxStore = new OutboxStore();

            // 6. Создание UDP Discovery
            discovery = new UdpDiscovery(ringState, TCP_PORT, eventLoop);
            discovery.start();

            // 7. Создание RingTransport
            transport = new RingTransport(
                    myNodeId,
                    eventLoop,
                    ringState,
                    cryptoService,
                    outboxStore,
                    message -> handleIncomingMessage(message, fileService)
            );
            transport.start();

            // 8. Graceful shutdown hook
            final NioEventLoop finalEventLoop = eventLoop;
            final UdpDiscovery finalDiscovery = discovery;
            final RingTransport finalTransport = transport;
            final OutboxStore finalOutbox = outboxStore;

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[" + formatTime() + "] Shutting down...");
                running = false;

                try {
                    if (finalTransport != null) finalTransport.stop();
                    if (finalDiscovery != null) finalDiscovery.close();
                    if (finalEventLoop != null) finalEventLoop.stop();
                    if (finalOutbox != null) finalOutbox.close();
                } catch (Exception e) {
                    System.err.println("Error during shutdown: " + e.getMessage());
                }

                System.out.println("[" + formatTime() + "] Shutdown complete");
            }));

            // 9. Консольный интерфейс
            runConsole(ringState, transport, fileService, myNodeId);

        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            running = false;

            try {
                if (transport != null) transport.stop();
                if (discovery != null) discovery.close();
                if (eventLoop != null) eventLoop.stop();
                if (outboxStore != null) outboxStore.close();
            } catch (Exception e) {
                System.err.println("Error during cleanup: " + e.getMessage());
            }
        }
    }

    /**
     * Консольный интерфейс.
     */
    private static void runConsole(RingState ringState,
                                   RingTransport transport,
                                   FileTransferService fileService,
                                   long myNodeId) {
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
                    printInfo(ringState, transport, myNodeId);
                    continue;
                }

                if (trimmed.equalsIgnoreCase("connections")) {
                    System.out.println("Connections:\n" + transport.getConnectionStatus());
                    continue;
                }

                // Команда /all <text>
                if (trimmed.startsWith("/all ")) {
                    String text = trimmed.substring(5);
                    ChatMessage message = ChatMessage.text(myNodeId, 0, text, false);
                    transport.sendMessage(message);
                    System.out.println("✓ Broadcast sent (encrypted)");
                    continue;
                }

                // Команда /to <nodeId> <text>
                if (trimmed.startsWith("/to ")) {
                    String[] parts = trimmed.substring(4).split(" ", 2);
                    if (parts.length < 2) {
                        System.out.println("Usage: /to <nodeId> <text>");
                        continue;
                    }

                    long destId = Long.parseLong(parts[0]);
                    String text = parts[1];

                    ChatMessage message = ChatMessage.text(myNodeId, destId, text, false);
                    transport.sendMessage(message);
                    System.out.println("✓ Message sent to " + destId + " (encrypted)");
                    continue;
                }

                // Команда /file <path>
                if (trimmed.startsWith("/file ")) {
                    String filePath = trimmed.substring(6);

                    try {
                        byte[] fileData = fileService.readFileForSending(filePath);
                        String fileName = Path.of(filePath).getFileName().toString();

                        ChatMessage message = ChatMessage.file(myNodeId, 0, fileName, fileData, false);
                        transport.sendMessage(message);
                        System.out.println("✓ File sent: " + fileName + " (" + fileData.length + " bytes, encrypted)");
                    } catch (IOException e) {
                        System.out.println("✗ Failed to send file: " + e.getMessage());
                    }
                    continue;
                }

                System.out.println("Unknown command. Type 'help' for available commands.");

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }

    /**
     * Обработка входящего сообщения.
     */
    private static void handleIncomingMessage(ChatMessage message, FileTransferService fileService) {
        String time = formatTime();

        if (message.getType() == MessageType.TEXT) {
            System.out.println("\n[" + time + "] From " + message.getSenderId() + ": " + message.getContent());
        } else if (message.getType() == MessageType.FILE) {
            try {
                Path saved = fileService.saveReceivedFile(message.getFileName(), message.getFileData());
                System.out.println("\n[" + time + "] File from " + message.getSenderId() +
                        ": " + message.getFileName() + " saved to " + saved);
            } catch (IOException e) {
                System.err.println("\n[" + time + "] Failed to save file: " + e.getMessage());
            }
        }

        System.out.print("> ");
    }

    private static void printBanner() {
        System.out.println("============================================================");
        System.out.println("  BRP Messenger - Ring Topology with NIO & Encryption");
        System.out.println("  " + Instant.now());
        System.out.println("============================================================");
    }

    private static void printHelp() {
        System.out.println("\nAvailable commands:");
        System.out.println("  /all <text>        - Send encrypted broadcast message");
        System.out.println("  /to <id> <text>    - Send encrypted message to specific node");
        System.out.println("  /file <path>       - Send encrypted file to all nodes");
        System.out.println("  topology           - Show ring topology");
        System.out.println("  info               - Show node info");
        System.out.println("  connections        - Show connection status");
        System.out.println("  help               - Show this help");
        System.out.println("  exit               - Exit application");
        System.out.println();
    }

    private static void printTopology(RingState ringState) {
        System.out.println("\nRing Topology:");

        ringState.rightNeighbor().ifPresentOrElse(
                right -> System.out.println("Right neighbor: " + right.nodeId()),
                () -> System.out.println("Right neighbor: none")
        );

        ringState.leftNeighbor().ifPresentOrElse(
                left -> System.out.println("Left neighbor: " + left.nodeId()),
                () -> System.out.println("Left neighbor: none")
        );

        System.out.println("Total nodes: " + (ringState.nodeCount() + 1));
    }

    private static void printInfo(RingState ringState, RingTransport transport, long myNodeId) {
        System.out.println("\nNode Information:");
        System.out.println("  My ID: " + myNodeId);
        System.out.println("  Nodes in ring: " + ringState.nodeCount());

        ringState.leftNeighbor().ifPresentOrElse(
                left -> System.out.println("  Left neighbor: " + left.nodeId()),
                () -> System.out.println("  Left neighbor: none")
        );

        ringState.rightNeighbor().ifPresentOrElse(
                right -> System.out.println("  Right neighbor: " + right.nodeId()),
                () -> System.out.println("  Right neighbor: none")
        );

        System.out.println("\nConnections:");
        System.out.println(transport.getConnectionStatus());
    }

    private static String formatTime() {
        return TIME_FORMATTER.format(Instant.now());
    }
}