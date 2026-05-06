package messenger.app;

import messenger.discovery.UdpDiscovery;
import messenger.nio.NioEventLoop;
import messenger.protocol.ChatMessage;
import messenger.ring.RingState;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * Main класс для запуска узла кольцевого мессенджера (NIO версия).
 * <p>
 * Запуск:
 * mvn clean compile
 * mvn exec:java -Dexec.mainClass="messenger.app.Main"
 * <p>
 * Для запуска нескольких узлов:
 * - Откройте несколько терминалов
 * - В каждом запустите эту же команду
 * - Узлы автоматически обнаружат друг друга через UDP broadcast
 */
public final class Main {

    private static final int TCP_PORT = 8080;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private Main() {
    }

    public static void main(String[] args) {
        printBanner();

        NioEventLoop eventLoop = null;
        UdpDiscovery discovery = null;

        try {
            // 1. Создаём NioEventLoop (главный селектор для всех I/O)
            eventLoop = new NioEventLoop();
            eventLoop.start();
            log("NioEventLoop started");

            // 2. Создаём RingState (состояние топологии кольца)
            RingState ringState = new RingState();
            long myId = ringState.myId();
            log("Node ID: " + myId);

            // 3. Запускаем UDP Discovery для автообнаружения узлов
            discovery = new UdpDiscovery(ringState, TCP_PORT, eventLoop);
            discovery.start();
            log("UDP Discovery started on port 9999");

            // 4. Ждём 3 секунды для начального discovery
            System.out.println();
            log("Waiting for node discovery...");
            Thread.sleep(3000);

            // 5. Показываем начальную топологию
            printTopology(ringState);

            // 6. Регистрируем shutdown hook для graceful остановки
            final NioEventLoop finalEventLoop = eventLoop;
            final UdpDiscovery finalDiscovery = discovery;

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println();
                log("Shutting down...");

                if (finalDiscovery != null) {
                    finalDiscovery.close();
                }

                if (finalEventLoop != null) {
                    finalEventLoop.close();
                }

                System.out.println("Goodbye!");
            }));

            // 7. Запускаем фоновый поток для вывода топологии
            startTopologyMonitor(ringState);

            // 8. Главный цикл (пока просто консоль для выхода)
            runConsole(ringState);

        } catch (Exception e) {
            error("Failed to start application: " + e.getMessage());
            e.printStackTrace();

            // Cleanup при ошибке
            if (discovery != null) {
                discovery.close();
            }
            if (eventLoop != null) {
                eventLoop.close();
            }

            System.exit(1);
        }
    }

    /**
     * Запуск фонового монитора топологии (каждые 10 секунд).
     */
    private static void startTopologyMonitor(RingState ringState) {
        Thread monitor = new Thread(() -> {
            try {
                while (!Thread.interrupted()) {
                    Thread.sleep(10_000); // 10 секунд
                    printTopology(ringState);
                }
            } catch (InterruptedException e) {
                // Нормальная остановка
            }
        }, "TopologyMonitor");

        monitor.setDaemon(true);
        monitor.start();
    }

    /**
     * Консольный интерфейс.
     */
    private static void runConsole(RingState ringState) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Commands:");
        System.out.println("  topology  - Show current ring topology");
        System.out.println("  info      - Show node information");
        System.out.println("  exit      - Shutdown node");
        System.out.println("=".repeat(60));
        System.out.println();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");

                if (!scanner.hasNextLine()) {
                    break;
                }

                String line = scanner.nextLine().trim();

                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+", 2);
                String command = parts[0].toLowerCase();

                switch (command) {
                    case "exit":
                    case "quit":
                    case "q":
                        System.out.println("Exiting...");
                        return;

                    case "topology":
                    case "top":
                        printTopology(ringState);
                        break;

                    case "info":
                        printNodeInfo(ringState);
                        break;

                    case "help":
                    case "?":
                        printHelp();
                        break;

                    default:
                        System.out.println("Unknown command: " + command);
                        System.out.println("Type 'help' for available commands");
                }
            }
        }
    }

    /**
     * Вывод баннера.
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("  BRP Messenger - Ring Topology with NIO");
        System.out.println("  " + Instant.now());
        System.out.println("=".repeat(60));
        System.out.println();
    }

    /**
     * Вывод текущей топологии кольца.
     */
    private static void printTopology(RingState ringState) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("[" + currentTime() + "] Ring Topology");
        System.out.println("=".repeat(60));
        System.out.println("My ID: " + ringState.myId());
        System.out.println("Known nodes: " + ringState.nodeCount());

        if (ringState.nodeCount() > 0) {
            System.out.println();
            System.out.println("Nodes in ring:");
            ringState.getAllNodes().forEach(node ->
                    System.out.println("  • " + node)
            );
        }

        System.out.println();
        ringState.leftNeighbor().ifPresentOrElse(
                left -> System.out.println("← Left neighbor:  " + left.nodeId() +
                        " (" + left.ip().getHostAddress() + ":" + left.tcpPort() + ")"),
                () -> System.out.println("← Left neighbor:  NONE (single node or waiting)")
        );

        ringState.rightNeighbor().ifPresentOrElse(
                right -> System.out.println("→ Right neighbor: " + right.nodeId() +
                        " (" + right.ip().getHostAddress() + ":" + right.tcpPort() + ")"),
                () -> System.out.println("→ Right neighbor: NONE (single node or waiting)")
        );

        System.out.println("=".repeat(60));
    }

    /**
     * Вывод информации об узле.
     */
    private static void printNodeInfo(RingState ringState) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Node Information");
        System.out.println("=".repeat(60));
        System.out.println("Node ID:          " + ringState.myId());
        System.out.println("TCP Port:         " + TCP_PORT);
        System.out.println("Discovery Port:   9999");
        System.out.println("Known Nodes:      " + ringState.nodeCount());
        System.out.println("Ring Size:        " + (ringState.nodeCount() + 1));
        System.out.println("Status:           RUNNING");
        System.out.println("=".repeat(60));
    }

    /**
     * Вывод помощи.
     */
    private static void printHelp() {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Available Commands");
        System.out.println("=".repeat(60));
        System.out.println("  topology, top    - Show current ring topology");
        System.out.println("  info             - Show node information");
        System.out.println("  help, ?          - Show this help");
        System.out.println("  exit, quit, q    - Shutdown node");
        System.out.println("=".repeat(60));
        System.out.println();
        System.out.println("Note: Ring transport (TCP) will be added in next phase.");
        System.out.println("Currently only UDP discovery is active.");
        System.out.println("=".repeat(60));
    }

    /**
     * Логирование с меткой времени.
     */
    private static void log(String message) {
        System.out.println("[" + currentTime() + "] " + message);
    }

    /**
     * Логирование ошибки.
     */
    private static void error(String message) {
        System.err.println("[" + currentTime() + "] ERROR: " + message);
    }

    /**
     * Текущее время в формате HH:mm:ss.
     */
    private static String currentTime() {
        return Instant.now().atZone(java.time.ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }
}