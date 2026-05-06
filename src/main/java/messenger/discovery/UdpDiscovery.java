package messenger.discovery;

import messenger.nio.*;
import messenger.protocol.DiscoveryPacket;
import messenger.ring.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * UDP Discovery на NIO для автоматического обнаружения узлов в сети.
 * <p>
 * Особенности:
 * - Broadcast beacon каждые 2 секунды
 * - Cleanup таймаутов каждую секунду
 * - Разрешение конфликтов nodeId
 * - Все I/O операции через NioEventLoop
 */
public final class UdpDiscovery implements AutoCloseable {

    private static final int DISCOVERY_PORT = 9999;
    private static final int BEACON_INTERVAL_MS = 2000;
    private static final int CLEANUP_INTERVAL_MS = 1000;
    private static final int NODE_TIMEOUT_MS = 10_000;

    private final RingState ringState;
    private final int tcpPort;
    private final NioEventLoop eventLoop;

    private volatile DatagramChannel channel;
    private InetAddress broadcastAddress;
    private volatile boolean running;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "UdpDiscovery-Scheduler");
                t.setDaemon(true);
                return t;
            });

    public UdpDiscovery(RingState ringState, int tcpPort, NioEventLoop eventLoop) {
        this.ringState = ringState;
        this.tcpPort = tcpPort;
        this.eventLoop = eventLoop;
    }

    public void start() throws IOException {
        running = true;

        channel = DatagramChannel.open(StandardProtocolFamily.INET);
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.SO_BROADCAST, true);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

        channel.bind(new InetSocketAddress(DISCOVERY_PORT));

        broadcastAddress = InetAddress.getByName("255.255.255.255");

        eventLoop.register(channel, SelectionKey.OP_READ, new DiscoveryHandler());

        System.out.println("[UdpDiscovery] Started on port " + DISCOVERY_PORT + " (NIO mode)");

        scheduler.scheduleAtFixedRate(
                this::sendBeacon,
                1000,
                BEACON_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        scheduler.scheduleAtFixedRate(
                this::cleanupTimeouts,
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void sendBeacon() {
        if (!running || channel == null || !channel.isOpen()) {
            return;
        }

        try {
            DiscoveryPacket packet = new DiscoveryPacket(
                    ringState.myId(),
                    getLocalIpAddress(),
                    tcpPort
            );

            ByteBuffer buffer = packet.toByteBuffer();
            InetSocketAddress destination = new InetSocketAddress(broadcastAddress, DISCOVERY_PORT);

            eventLoop.execute(() -> {
                try {
                    if (channel != null && channel.isOpen()) {
                        buffer.rewind();
                        int sent = channel.send(buffer, destination);

                        if (sent > 0) {
                            System.out.println("[UdpDiscovery] Beacon sent: nodeId=" +
                                    ringState.myId() + ", bytes=" + sent);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("[UdpDiscovery] Beacon send error: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            System.err.println("[UdpDiscovery] Beacon preparation error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void cleanupTimeouts() {
        if (!running) {
            return;
        }

        try {
            int removed = ringState.removeOldNodes(NODE_TIMEOUT_MS);
            if (removed > 0) {
                System.out.println("[UdpDiscovery] Cleaned up " + removed + " timed-out nodes");
            }
        } catch (Exception e) {
            System.err.println("[UdpDiscovery] Cleanup error: " + e.getMessage());
        }
    }

    private class DiscoveryHandler implements ChannelHandler {

        private final ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);

        @Override
        public void handleAccept(SelectionKey key) {
        }

        @Override
        public void handleConnect(SelectionKey key) {
        }

        @Override
        public void handleRead(SelectionKey key) throws IOException {
            DatagramChannel dgChannel = (DatagramChannel) key.channel();

            receiveBuffer.clear();
            SocketAddress sender = dgChannel.receive(receiveBuffer);

            if (sender == null) {
                return;
            }

            receiveBuffer.flip();

            DiscoveryPacket packet = DiscoveryPacket.fromByteBuffer(receiveBuffer);
            if (packet == null) {
                System.err.println("[UdpDiscovery] Failed to parse packet from " + sender);
                return;
            }

            System.out.println("[UdpDiscovery] Received from " + sender +
                    ": nodeId=" + packet.nodeId() + ", ip=" + packet.ipAddress().getHostAddress());

            handleDiscoveryPacket(packet, sender);
        }

        private void handleDiscoveryPacket(DiscoveryPacket packet, SocketAddress sender) {
            long remoteId = packet.nodeId();
            InetAddress remoteIp = packet.ipAddress();
            int remoteTcpPort = packet.tcpPort();

            if (remoteId == ringState.myId()) {
                return;
            }

            if (ringState.hasNode(remoteId)) {
                NodeInfo existing = ringState.getNode(remoteId);
                if (existing != null && !existing.ip().equals(remoteIp)) {
                    if (remoteId < ringState.myId()) {
                        System.err.println("[UdpDiscovery] NodeId conflict detected! Regenerating...");
                        regenerateNodeId();
                        return;
                    } else {
                        System.out.println("[UdpDiscovery] Ignoring conflicting node: " + remoteId);
                        return;
                    }
                }
            }

            NodeInfo node = new NodeInfo(remoteId, remoteIp, remoteTcpPort, System.currentTimeMillis());
            boolean added = ringState.addOrUpdateNode(node);

            if (added) {
                System.out.println("[UdpDiscovery] Discovered new node: " +
                        remoteId + " at " + remoteIp.getHostAddress() + ":" + remoteTcpPort);
            }
        }

        @Override
        public void handleWrite(SelectionKey key) {
        }

        @Override
        public void handleError(SelectionKey key, Exception e) {
            System.err.println("[UdpDiscovery] Handler error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void regenerateNodeId() {
        try {
            long oldId = ringState.myId();
            ringState.regenerateMyId();
            long newId = ringState.myId();
            System.out.println("[UdpDiscovery] NodeId regenerated: " + oldId + " -> " + newId);
        } catch (Exception e) {
            System.err.println("[UdpDiscovery] Failed to regenerate nodeId: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private InetAddress getLocalIpAddress() throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 80);
            InetAddress localAddr = socket.getLocalAddress();
            System.out.println("[UdpDiscovery] Local IP: " + localAddr.getHostAddress());
            return localAddr;
        } catch (Exception e) {
            InetAddress fallback = InetAddress.getLocalHost();
            System.out.println("[UdpDiscovery] Using fallback IP: " + fallback.getHostAddress());
            return fallback;
        }
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }

        running = false;

        System.out.println("[UdpDiscovery] Shutting down...");

        scheduler.shutdownNow();

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }

        System.out.println("[UdpDiscovery] Stopped");
    }
}
