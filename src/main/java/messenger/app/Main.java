package messenger.app;

import messenger.discovery.UdpDiscovery;
import messenger.protocol.ChatMessage;
import messenger.queue.OutboxStore;
import messenger.ring.IdStorage;
import messenger.ring.RingState;
import messenger.transport.RingTransport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final int UDP_PORT = 9876;
    private static final int TCP_PORT = 9877;
    private static final long DISCOVERY_TIMEOUT_MS = 6_000;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path stateDir = Path.of(System.getProperty("user.home"), ".brp-messenger");
        Path logPath = stateDir.resolve("brp-messenger.log");
        configureLogging(logPath);
        IdStorage idStorage = new IdStorage(stateDir.resolve("node.id"));

        OptionalLong loadedId = idStorage.load();
        AtomicLong myId = new AtomicLong(loadedId.orElse(0L));

        InetAddress localIp = resolveLocalIp();
        printUi("BRP Messenger started");
        printUi("Logs file: " + logPath.toAbsolutePath());
        printUi("Local IP: " + localIp.getHostAddress());
        LOG.info(prefix(myId.get()) + " [APP] starting | myId=" + myId.get()
                + " | idSource=" + (loadedId.isPresent() ? "file" : "new") + " | ip=" + localIp.getHostAddress());

        RingState[] ringHolder = new RingState[1];
        try (UdpDiscovery discovery = new UdpDiscovery(
                UDP_PORT,
                DISCOVERY_TIMEOUT_MS,
                myId,
                () -> localIp,
                newId -> {
                    try {
                        idStorage.save(newId);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                },
                localIp
        )) {
            discovery.setOnIdRemapped((oldId, newId) -> {
                RingState ring = ringHolder[0];
                if (ring != null) {
                    ring.setMyId(newId);
                }
                printUi("Node ID remapped (collision): " + oldId + " -> " + newId);
            });
            discovery.start();

            waitForInitialDiscoveryWindow();
            if (myId.get() == 0L) {
                long assignedId = assignId(discovery.snapshotLiveNodes());
                myId.set(assignedId);
                idStorage.save(assignedId);
                printUi("Assigned node ID: " + assignedId);
                LOG.info(prefix(assignedId) + " [DISCOVERY] new node, assigned id=" + assignedId
                        + " | persisted to " + idStorage.idFilePath());
            }

            Map<Long, InetAddress> initialNodes = new java.util.HashMap<>(discovery.snapshotLiveNodes());
            initialNodes.put(myId.get(), localIp);
            RingState ringState = new RingState(myId.get());
            ringHolder[0] = ringState;
            LOG.info(prefix(myId.get()) + " [RING] started | myId=" + myId.get()
                    + " | source=" + (loadedId.isPresent() ? "file" : "new"));
            ringState.recompute(initialNodes);

            try (OutboxStore outboxStore = new OutboxStore(myId.get(), stateDir.resolve("queue.db"));
                 RingTransport transport = new RingTransport(ringState, TCP_PORT, outboxStore, Main::handleIncomingMessage)) {
                transport.start();

                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                AtomicReference<String> lastPrintedRing = new AtomicReference<>();
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        Map<Long, InetAddress> snapshot = new java.util.HashMap<>(discovery.snapshotLiveNodes());
                        snapshot.put(myId.get(), localIp);
                        ringState.recompute(snapshot);
                        transport.refreshTopology();
                        printRingStateIfChanged(transport, myId, localIp, lastPrintedRing);
                    } catch (RuntimeException ex) {
                        LOG.warning(prefix(myId.get()) + " [APP] topology refresh failed | reason=" + ex.getMessage());
                    }
                }, 0, 2, TimeUnit.SECONDS);

                runConsole(transport, myId);
                scheduler.shutdownNow();
            }
        }
    }

    private static void runConsole(RingTransport transport, AtomicLong myId) {
        String ringSummary = ringSummary(transport, myId.get());
        printUi(ringSummary);
        printUi("Commands: /all <text>, /to <id> <text>, /exit");
        printUi("");
        String left = transport.currentLeftNeighborId().map(String::valueOf).orElse("null");
        String right = transport.currentRightNeighborId().map(String::valueOf).orElse("null");
        LOG.info(prefix(myId.get()) + " [APP] ring ready | left=" + left + " | right=" + right
                + " | command format: /all <text> or /to <id> <text>");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    return;
                }
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if ("/exit".equalsIgnoreCase(line)) {
                    LOG.info(prefix(myId.get()) + " [APP] shutdown requested");
                    printUi("Shutting down...");
                    return;
                }
                if (line.startsWith("/to ")) {
                    String[] parts = line.split("\\s+", 3);
                    if (parts.length < 3) {
                        printUi("Usage: /to <id> <message>");
                        continue;
                    }
                    long targetId;
                    try {
                        targetId = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ex) {
                        printUi("Invalid target ID: " + parts[1]);
                        continue;
                    }
                    transport.sendToRing(targetId, parts[2]);
                    printUi("Sent to " + targetId + ": " + parts[2]);
                    continue;
                }
                String text = line.startsWith("/all ") ? line.substring(5) : line;
                transport.sendToRing(ChatMessage.TARGET_BROADCAST, text);
                printUi("Sent to all: " + text);
            }
        }
    }

    private static void handleIncomingMessage(ChatMessage message) {
        String target = message.targetId() == ChatMessage.TARGET_BROADCAST ? "broadcast" : Long.toString(message.targetId());
        synchronized (System.out) {
            System.out.println();
            System.out.println("[" + Instant.now() + "] from=" + message.senderId()
                    + " seq=" + message.sequenceNumber() + " target=" + target + " :: " + message.payload());
        }
    }

    private static long assignId(Map<Long, InetAddress> nodes) {
        long max = 0L;
        for (Long id : nodes.keySet()) {
            max = Math.max(max, id);
        }
        return max + 1L;
    }

    private static void printRingStateIfChanged(
            RingTransport transport,
            AtomicLong myId,
            InetAddress localIp,
            AtomicReference<String> lastPrintedRing
    ) {
        String current = ringSummary(transport, myId.get());
        String previous = lastPrintedRing.getAndSet(current);
        if (!current.equals(previous)) {
            printUi("Local IP: " + localIp.getHostAddress());
            printUi(current);
        }
    }

    private static String ringSummary(RingTransport transport, long myId) {
        String left = transport.currentLeftNeighborId().map(String::valueOf).orElse("null");
        String right = transport.currentRightNeighborId().map(String::valueOf).orElse("null");
        return "Ring ready: myId=" + myId + ", left=" + left + ", right=" + right;
    }

    private static void waitForInitialDiscoveryWindow() {
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static InetAddress resolveLocalIp() throws SocketException {
        List<InetAddress> candidates = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            String name = networkInterface.getName().toLowerCase();
            String display = networkInterface.getDisplayName() == null
                    ? ""
                    : networkInterface.getDisplayName().toLowerCase();
            if (isLikelyVirtualInterface(name, display)) {
                continue;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!address.isLoopbackAddress() && !address.isLinkLocalAddress() && address.getAddress().length == 4) {
                    candidates.add(address);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Cannot determine local IPv4 address");
        }
        return candidates.stream()
                .min(Comparator.comparingInt(Main::lanPreferenceKey).thenComparing(Main::compareInetBytes))
                .orElseThrow();
    }

    /**
     * Prefer LAN segments commonly used for home Wi‑Fi so discovery binds to the same NIC users expect.
     */
    private static int lanPreferenceKey(InetAddress a) {
        byte[] b = a.getAddress();
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;
        if (o0 == 192 && o1 == 168) {
            return 0;
        }
        if (o0 == 10) {
            return 1;
        }
        if (o0 == 172 && o1 >= 16 && o1 <= 31) {
            return 2;
        }
        return 3;
    }

    private static int compareInetBytes(InetAddress a, InetAddress b) {
        byte[] aa = a.getAddress();
        byte[] bb = b.getAddress();
        int len = Math.min(aa.length, bb.length);
        for (int i = 0; i < len; i++) {
            int c = Byte.compare(aa[i], bb[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(aa.length, bb.length);
    }

    private static boolean isLikelyVirtualInterface(String name, String displayName) {
        String n = name + " " + displayName;
        return n.contains("vmware")
                || n.contains("virtualbox")
                || n.contains("vbox")
                || n.contains("hyper-v")
                || n.contains("vethernet")
                || n.contains("wsl")
                || n.contains("docker");
    }

    private static void configureLogging(Path logPath) throws IOException {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (java.util.logging.Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }

        java.nio.file.Files.createDirectories(logPath.getParent());
        FileHandler fileHandler = new FileHandler(logPath.toString(), true);
        fileHandler.setLevel(Level.INFO);
        fileHandler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord record) {
                return Instant.ofEpochMilli(record.getMillis()) + " "
                        + record.getLevel() + " "
                        + record.getMessage() + System.lineSeparator();
            }
        });
        root.addHandler(fileHandler);
    }

    private static String prefix(long myId) {
        return "[myId=" + myId + "]";
    }

    private static void printUi(String message) {
        synchronized (System.out) {
            System.out.println(message);
        }
    }
}
