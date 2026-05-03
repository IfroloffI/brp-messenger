package messenger.discovery;

import messenger.protocol.DiscoveryPacket;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class UdpDiscovery implements Closeable {
    private static final Logger LOG = Logger.getLogger(UdpDiscovery.class.getName());

    private final int udpPort;
    private final long nodeTimeoutMs;
    private final AtomicLong myId;
    private final Supplier<InetAddress> localIpSupplier;
    private final Consumer<Long> persistNodeId;
    /** Bind socket to this IPv4 so broadcast uses the same NIC as our advertised IP. */
    private final InetAddress discoveryBindAddress;

    private final Map<Long, InetAddress> liveNodes = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastSeenMs = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private DatagramSocket socket;
    private volatile boolean running;
    private volatile BiConsumer<Long, Long> onIdRemapped;

    public UdpDiscovery(
            int udpPort,
            long nodeTimeoutMs,
            AtomicLong myId,
            Supplier<InetAddress> localIpSupplier,
            Consumer<Long> persistNodeId,
            InetAddress discoveryBindAddress
    ) {
        this.udpPort = udpPort;
        this.nodeTimeoutMs = nodeTimeoutMs;
        this.myId = myId;
        this.localIpSupplier = localIpSupplier;
        this.persistNodeId = persistNodeId;
        this.discoveryBindAddress = discoveryBindAddress;
    }

    public void setOnIdRemapped(BiConsumer<Long, Long> listener) {
        this.onIdRemapped = listener;
    }

    public void start() throws SocketException {
        socket = new DatagramSocket(udpPort, discoveryBindAddress);
        socket.setBroadcast(true);
        socket.setSoTimeout(1_000);
        running = true;

        scheduler.scheduleAtFixedRate(this::broadcastBeacon, 0, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::cleanupDeadNodes, 1, 1, TimeUnit.SECONDS);
        scheduler.execute(this::listenLoop);
    }

    public Map<Long, InetAddress> snapshotLiveNodes() {
        return Map.copyOf(liveNodes);
    }

    private void broadcastBeacon() {
        if (!running) {
            return;
        }
        try {
            long myId = this.myId.get();
            if (myId == 0L) {
                return;
            }
            String myIp = localIpSupplier.get().getHostAddress();
            DiscoveryPacket packet = new DiscoveryPacket(myId, myIp);
            byte[] bytes = packet.toBytes();
            InetAddress localForSend = localIpSupplier.get();
            DatagramPacket datagramPacket = new DatagramPacket(bytes, bytes.length);
            datagramPacket.setPort(udpPort);

            datagramPacket.setAddress(InetAddress.getByName("255.255.255.255"));
            socket.send(datagramPacket);

            InetAddress subnetBc = subnetBroadcastFor(localForSend);
            if (subnetBc != null
                    && !subnetBc.equals(datagramPacket.getAddress())) {
                datagramPacket.setAddress(subnetBc);
                socket.send(datagramPacket);
            }
        } catch (IOException ex) {
            LOG.warning("[DISCOVERY] broadcast failed | reason=" + ex.getMessage());
        }
    }

    private static InetAddress subnetBroadcastFor(InetAddress local) throws SocketException {
        if (!(local instanceof Inet4Address)) {
            return null;
        }
        NetworkInterface nic = NetworkInterface.getByInetAddress(local);
        if (nic == null) {
            return null;
        }
        for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
            if (ia.getAddress().equals(local)) {
                InetAddress b = ia.getBroadcast();
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                DiscoveryPacket.fromBytes(packet.getData(), packet.getLength()).ifPresent(parsed -> {
                    try {
                        InetAddress ip = InetAddress.getByName(parsed.ip());
                        long nodeId = parsed.nodeId();
                        InetAddress local = localIpSupplier.get();
                        long now = System.currentTimeMillis();

                        if (nodeId == 0L && !ip.equals(local)) {
                            return;
                        }

                        if (nodeId > 0L && nodeId == myId.get() && !ip.equals(local)) {
                            if (compareInetAddress(local, ip) > 0) {
                                resolveDuplicateNodeId(ip);
                            }
                        }

                        InetAddress previousIpForId = liveNodes.get(nodeId);
                        for (var it = liveNodes.entrySet().iterator(); it.hasNext(); ) {
                            var e = it.next();
                            if (e.getValue().equals(ip) && e.getKey() != nodeId) {
                                it.remove();
                                lastSeenMs.remove(e.getKey());
                            }
                        }
                        liveNodes.put(nodeId, ip);
                        lastSeenMs.put(nodeId, now);
                        if (previousIpForId == null || !previousIpForId.equals(ip)) {
                            LOG.info(logPrefix(myId.get()) + " [DISCOVERY] peer | nodeId=" + nodeId
                                    + " | ip=" + ip.getHostAddress());
                        }
                    } catch (UnknownHostException ex) {
                        LOG.warning(logPrefix(myId.get()) + " [DISCOVERY] invalid ip in packet | value="
                                + parsed.ip());
                    }
                });
            } catch (SocketTimeoutException ignored) {
                // keep polling
            } catch (IOException ex) {
                if (running) {
                    LOG.warning(logPrefix(myId.get()) + " [DISCOVERY] listener error | reason=" + ex.getMessage());
                }
            }
        }
    }

    private void resolveDuplicateNodeId(InetAddress otherIp) {
        long oldId = myId.get();
        long newId = maxKnownNodeId() + 1L;
        myId.set(newId);
        try {
            persistNodeId.accept(newId);
        } catch (RuntimeException ex) {
            LOG.warning(logPrefix(newId) + " [DISCOVERY] failed to persist new id | reason=" + ex.getMessage());
        }
        LOG.info(logPrefix(newId) + " [DISCOVERY] duplicate nodeId collision | otherIp=" + otherIp.getHostAddress()
                + " | remapped " + oldId + " -> " + newId + " (larger local IP yields new id)");
        BiConsumer<Long, Long> listener = onIdRemapped;
        if (listener != null) {
            try {
                listener.accept(oldId, newId);
            } catch (RuntimeException ex) {
                LOG.warning(logPrefix(newId) + " [DISCOVERY] onIdRemapped failed | reason=" + ex.getMessage());
            }
        }
    }

    private long maxKnownNodeId() {
        long max = myId.get();
        for (Long id : liveNodes.keySet()) {
            max = Math.max(max, id);
        }
        return max;
    }

    private static int compareInetAddress(InetAddress a, InetAddress b) {
        byte[] aa = a.getAddress();
        byte[] bb = b.getAddress();
        if (aa.length != bb.length) {
            return Integer.compare(aa.length, bb.length);
        }
        for (int i = 0; i < aa.length; i++) {
            int va = aa[i] & 0xFF;
            int vb = bb[i] & 0xFF;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private void cleanupDeadNodes() {
        long now = System.currentTimeMillis();
        long myId = this.myId.get();
        for (Map.Entry<Long, Long> entry : lastSeenMs.entrySet()) {
            long nodeId = entry.getKey();
            if (nodeId == myId) {
                continue;
            }
            if (now - entry.getValue() > nodeTimeoutMs) {
                InetAddress removedIp = liveNodes.remove(nodeId);
                lastSeenMs.remove(nodeId);
                if (removedIp != null) {
                    LOG.info(logPrefix(myId) + " [DISCOVERY] node timed out | nodeId=" + nodeId
                            + " | lastSeenIp=" + removedIp.getHostAddress());
                }
            }
        }
    }

    private String logPrefix(long myId) {
        return "[myId=" + myId + "]";
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
