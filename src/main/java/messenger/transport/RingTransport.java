package messenger.transport;

import messenger.protocol.ChatMessage;
import messenger.queue.OutboundMessage;
import messenger.queue.OutboxStore;
import messenger.ring.NodeInfo;
import messenger.ring.RingState;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class RingTransport implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RingTransport.class.getName());
    private static final int MAX_SEEN_MESSAGES = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    private final RingState ringState;
    private final int tcpPort;
    private final OutboxStore outboxStore;
    private final Consumer<ChatMessage> onLocalDeliver;
    private final AtomicLong sequenceCounter = new AtomicLong(0L);
    private final Map<String, Boolean> seenMessageIds = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_SEEN_MESSAGES;
                }
            }
    );

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile boolean running;
    private volatile ServerSocket serverSocket;

    private volatile Socket leftSocket;
    private volatile DataInputStream leftInput;

    private volatile Socket rightSocket;
    private volatile DataOutputStream rightOutput;
    private volatile long rightNeighborId = -1L;
    private final AtomicBoolean connectingRight = new AtomicBoolean(false);

    public RingTransport(RingState ringState, int tcpPort, OutboxStore outboxStore, Consumer<ChatMessage> onLocalDeliver) {
        this.ringState = ringState;
        this.tcpPort = tcpPort;
        this.outboxStore = outboxStore;
        this.onLocalDeliver = onLocalDeliver;
    }

    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(tcpPort);
        LOG.info(prefix() + " [TRANSPORT] listening | bind=0.0.0.0:" + tcpPort);
        executor.execute(this::acceptLoop);
    }

    public void refreshTopology() {
        if (!running) {
            return;
        }
        Optional<NodeInfo> right = ringState.rightNeighbor();
        if (right.isEmpty() || right.get().nodeId() == ringState.myId()) {
            closeRightConnection("no right neighbor");
            return;
        }
        NodeInfo rightInfo = right.get();
        synchronized (this) {
            if (rightSocket != null
                    && rightSocket.isConnected()
                    && !rightSocket.isClosed()
                    && rightNeighborId == rightInfo.nodeId()) {
                return;
            }
        }
        if (!connectingRight.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                connectRight(rightInfo);
            } finally {
                connectingRight.set(false);
            }
        });
    }

    public Optional<Long> currentLeftNeighborId() {
        return ringState.leftNeighbor().map(NodeInfo::nodeId);
    }

    public Optional<Long> currentRightNeighborId() {
        return ringState.rightNeighbor().map(NodeInfo::nodeId);
    }

    public void sendToRing(long targetId, String payload) {
        long sequence = sequenceCounter.incrementAndGet();
        long senderId = ringState.myId();
        String messageId = ChatMessage.buildMessageId(senderId, sequence);
        ChatMessage message = new ChatMessage(messageId, sequence, senderId, targetId, payload);
        markSeen(message.messageId());
        if (targetId == senderId || targetId == ChatMessage.TARGET_BROADCAST) {
            onLocalDeliver.accept(message);
        }

        DataOutputStream currentRight = rightOutput;
        if (currentRight == null) {
            LOG.info(prefix() + " [TRANSPORT] no right connection | seq=" + message.sequenceNumber()
                    + " | targetId=" + targetIdToLog(targetId) + " | action=enqueue");
            enqueueForRight(message);
            return;
        }

        try {
            synchronized (this) {
                message.writeTo(currentRight);
            }
            LOG.info(prefix() + " [TRANSPORT] send | seq=" + message.sequenceNumber() + " | targetId="
                    + targetIdToLog(targetId) + " | payloadLength=" + payload.length());
        } catch (IOException ex) {
            LOG.warning(prefix() + " [TRANSPORT] send failed | reason=" + ex.getMessage());
            enqueueForRight(message);
            closeRightConnection("send failed");
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                LOG.info(prefix() + " [TRANSPORT] accepted | remoteAddr=" + socket.getRemoteSocketAddress());
                executor.execute(() -> handleAccepted(socket));
            } catch (IOException ex) {
                if (running) {
                    LOG.warning(prefix() + " [TRANSPORT] accept failed | reason=" + ex.getMessage());
                }
            }
        }
    }

    private void handleAccepted(Socket socket) {
        long remoteId;
        DataInputStream input;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.setKeepAlive(true);
            input = new DataInputStream(socket.getInputStream());
            remoteId = input.readLong();
            socket.setSoTimeout(0);
        } catch (SocketTimeoutException ex) {
            LOG.warning(prefix() + " [TRANSPORT] handshake timeout | remoteAddr="
                    + socket.getRemoteSocketAddress() + " | timeoutMs=" + HANDSHAKE_TIMEOUT_MS);
            closeSilently(socket);
            return;
        } catch (IOException ex) {
            LOG.warning(prefix() + " [TRANSPORT] handshake failed | remoteAddr="
                    + socket.getRemoteSocketAddress() + " | reason=" + ex.getMessage());
            closeSilently(socket);
            return;
        }

        synchronized (this) {
            Socket old = leftSocket;
            if (old != null) {
                try {
                    old.close();
                } catch (IOException ignored) {
                }
                if (leftSocket == old) {
                    leftSocket = null;
                    leftInput = null;
                }
            }
            leftSocket = socket;
            leftInput = input;
        }
        LOG.info(prefix() + " [TRANSPORT] connected | side=left | remoteId=" + remoteId
                + " | remoteAddr=" + socket.getRemoteSocketAddress());
        executor.execute(() -> leftReadLoop(socket, input));
    }

    private static void closeSilently(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // no-op
        }
    }

    private void connectRight(NodeInfo rightInfo) {
        String endpoint = rightInfo.ip().getHostAddress() + ":" + tcpPort;
        LOG.info(prefix() + " [TRANSPORT] connecting | to=" + endpoint + " | role=right | targetId="
                + rightInfo.nodeId() + " | timeoutMs=" + CONNECT_TIMEOUT_MS);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(rightInfo.ip(), tcpPort), CONNECT_TIMEOUT_MS);
            socket.setKeepAlive(true);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeLong(ringState.myId());
            output.flush();

            synchronized (this) {
                closeRightConnection("reconnect right");
                rightSocket = socket;
                rightOutput = output;
                rightNeighborId = rightInfo.nodeId();
            }
            LOG.info(prefix() + " [TRANSPORT] connected | side=right | remoteId=" + rightInfo.nodeId()
                    + " | remoteAddr=" + socket.getRemoteSocketAddress());
            drainOutbox(rightInfo.nodeId());
        } catch (SocketTimeoutException ex) {
            LOG.warning(prefix() + " [TRANSPORT] connecting | to=" + endpoint
                    + " | role=right | status=failed | reason=connect timeout after " + CONNECT_TIMEOUT_MS + "ms");
            closeSilently(socket);
        } catch (IOException ex) {
            LOG.warning(prefix() + " [TRANSPORT] connecting | to=" + endpoint
                    + " | role=right | status=failed | reason=" + ex.getMessage());
            closeSilently(socket);
        }
    }

    private void leftReadLoop(Socket ownedSocket, DataInputStream input) {
        while (running) {
            try {
                ChatMessage message = ChatMessage.readFrom(input);
                if (isSeen(message.messageId())) {
                    continue;
                }
                markSeen(message.messageId());

                LOG.info(prefix() + " [TRANSPORT] received | fromId=" + message.senderId() + " | seq="
                        + message.sequenceNumber() + " | targetId=" + targetIdToLog(message.targetId())
                        + " | payloadLength=" + message.payload().length());

                boolean forMe = message.targetId() == ringState.myId();
                boolean isBroadcast = message.targetId() == ChatMessage.TARGET_BROADCAST;
                if (forMe || isBroadcast) {
                    onLocalDeliver.accept(message);
                    LOG.info(prefix() + " [TRANSPORT] deliver local | fromId=" + message.senderId()
                            + " | seq=" + message.sequenceNumber());
                }

                if (!forMe) {
                    forwardRight(message);
                }
            } catch (EOFException ex) {
                closeLeftConnectionIfOwner(ownedSocket, "left closed");
                return;
            } catch (IOException ex) {
                LOG.warning(prefix() + " [TRANSPORT] read failed | side=left | reason=" + ex.getMessage());
                closeLeftConnectionIfOwner(ownedSocket, "left read failed");
                return;
            }
        }
    }

    private void forwardRight(ChatMessage message) {
        DataOutputStream output = rightOutput;
        if (output == null) {
            enqueueForRight(message);
            return;
        }
        try {
            synchronized (this) {
                message.writeTo(output);
            }
            LOG.info(prefix() + " [TRANSPORT] forward | fromId=" + message.senderId()
                    + " | seq=" + message.sequenceNumber() + " | targetId=" + targetIdToLog(message.targetId()));
        } catch (IOException ex) {
            LOG.warning(prefix() + " [TRANSPORT] forward failed | reason=" + ex.getMessage());
            enqueueForRight(message);
            closeRightConnection("forward failed");
        }
    }

    private void enqueueForRight(ChatMessage message) {
        Optional<NodeInfo> right = ringState.rightNeighbor();
        if (right.isEmpty()) {
            return;
        }
        long neighborId = right.get().nodeId();
        OutboundMessage outboundMessage = new OutboundMessage(
                message.messageId(),
                message.sequenceNumber(),
                message.senderId(),
                message.targetId(),
                message.payload(),
                System.currentTimeMillis()
        );
        outboxStore.enqueue(neighborId, outboundMessage);
    }

    private void drainOutbox(long neighborId) {
        int pending = outboxStore.size(neighborId);
        if (pending == 0) {
            return;
        }
        LOG.info(prefix() + " [QUEUE] drain start | neighborId=" + neighborId + " | pending=" + pending);

        while (running) {
            OutboundMessage message = outboxStore.peek(neighborId);
            if (message == null) {
                LOG.info(prefix() + " [QUEUE] drain stop | neighborId=" + neighborId + " | reason=empty");
                return;
            }
            DataOutputStream output = rightOutput;
            if (output == null) {
                LOG.info(prefix() + " [QUEUE] drain stop | neighborId=" + neighborId + " | reason=right unavailable");
                return;
            }
            try {
                synchronized (this) {
                    message.toChatMessage().writeTo(output);
                }
                outboxStore.poll(neighborId);
                LOG.info(prefix() + " [QUEUE] sent and removed | neighborId=" + neighborId + " | msgId="
                        + message.messageId() + " | remaining=" + outboxStore.size(neighborId));
            } catch (IOException ex) {
                LOG.warning(prefix() + " [QUEUE] drain stop | neighborId=" + neighborId
                        + " | reason=" + ex.getMessage());
                closeRightConnection("drain failed");
                return;
            }
        }
    }

    private boolean isSeen(String messageId) {
        synchronized (seenMessageIds) {
            return seenMessageIds.containsKey(messageId);
        }
    }

    private void markSeen(String messageId) {
        synchronized (seenMessageIds) {
            seenMessageIds.put(messageId, Boolean.TRUE);
        }
    }

    private String targetIdToLog(long targetId) {
        return targetId == ChatMessage.TARGET_BROADCAST ? "broadcast" : Long.toString(targetId);
    }

    private synchronized void closeLeftConnection(String reason) {
        closeLeftConnectionIfOwner(leftSocket, reason);
    }

    private synchronized void closeLeftConnectionIfOwner(Socket owner, String reason) {
        if (owner == null || leftSocket != owner) {
            return;
        }
        try {
            owner.close();
        } catch (IOException ignored) {
            // no-op
        }
        leftSocket = null;
        leftInput = null;
        LOG.info(prefix() + " [TRANSPORT] connection closed | side=left | reason=" + reason);
    }

    private synchronized void closeRightConnection(String reason) {
        if (rightSocket == null) {
            return;
        }
        try {
            rightSocket.close();
        } catch (IOException ignored) {
            // no-op
        }
        rightSocket = null;
        rightOutput = null;
        rightNeighborId = -1L;
        LOG.info(prefix() + " [TRANSPORT] connection closed | side=right | reason=" + reason);
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // no-op
        }
        closeLeftConnection("shutdown");
        closeRightConnection("shutdown");
        executor.shutdownNow();
    }

    private String prefix() {
        return "[myId=" + ringState.myId() + "]";
    }
}
