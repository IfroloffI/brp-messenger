package messenger.queue;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

public final class OutboxStore implements Closeable {
    private static final Logger LOG = Logger.getLogger(OutboxStore.class.getName());

    private final long myId;
    private final DB db;
    private final Map<Long, ArrayDeque<OutboundMessage>> queuesByNeighbor;

    public OutboxStore(long myId, Path storageFile) throws IOException {
        this.myId = myId;
        this.db = DBMaker.fileDB(storageFile.toFile())
                .fileMmapEnableIfSupported()
                .checksumHeaderBypass()
                .make();
        this.queuesByNeighbor = db
                .hashMap("outboxQueues", Serializer.LONG, Serializer.JAVA)
                .createOrOpen();
    }

    public synchronized void enqueue(long neighborId, OutboundMessage message) {
        Deque<OutboundMessage> queue = queuesByNeighbor.computeIfAbsent(neighborId, ignored -> new ArrayDeque<>());
        queue.addLast(message);
        db.commit();
        LOG.info(prefix() + " [QUEUE] enqueue | neighborId=" + neighborId + " | msgId=" + message.messageId()
                + " | seq=" + message.sequenceNumber() + " | size=" + queue.size());
    }

    public synchronized OutboundMessage peek(long neighborId) {
        Deque<OutboundMessage> queue = queuesByNeighbor.get(neighborId);
        return queue == null ? null : queue.peekFirst();
    }

    public synchronized OutboundMessage poll(long neighborId) {
        Deque<OutboundMessage> queue = queuesByNeighbor.get(neighborId);
        if (queue == null) {
            return null;
        }
        OutboundMessage removed = queue.pollFirst();
        if (queue.isEmpty()) {
            queuesByNeighbor.remove(neighborId);
        }
        db.commit();
        return removed;
    }

    public synchronized int size(long neighborId) {
        Deque<OutboundMessage> queue = queuesByNeighbor.get(neighborId);
        return queue == null ? 0 : queue.size();
    }

    public synchronized List<Long> neighborsWithPendingMessages() {
        return new ArrayList<>(queuesByNeighbor.keySet());
    }

    @Override
    public synchronized void close() {
        db.commit();
        if (!db.isClosed()) {
            db.close();
        }
    }

    private String prefix() {
        return "[myId=" + myId + "]";
    }
}
