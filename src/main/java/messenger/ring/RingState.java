package messenger.ring;

import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Управление состоянием логического кольца с публичными ключами узлов
 */
public class RingState {
    private static final Logger logger = Logger.getLogger(RingState.class.getName());
    private static final long NODE_TIMEOUT_MS = 10_000;

    private final long myNodeId;
    private final Map<Long, NodeInfo> nodes;
    private final ReadWriteLock lock;

    private volatile Long leftNeighbor;
    private volatile Long rightNeighbor;

    public RingState(long myNodeId) {
        this.myNodeId = myNodeId;
        this.nodes = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Обновляет информацию об узле (или добавляет новый)
     */
    public void updateNode(NodeInfo nodeInfo) {
        lock.writeLock().lock();
        try {
            NodeInfo existing = nodes.get(nodeInfo.nodeId());

            if (existing != null) {
                // Обновляем timestamp и ключи (если они есть)
                NodeInfo updated = existing.withUpdatedTimestamp(nodeInfo.lastSeen());

                // Если пришли новые ключи, обновляем их
                if (nodeInfo.hasKeys() && !existing.hasKeys()) {
                    updated = updated.withKeys(
                            nodeInfo.encryptionPublicKey(),
                            nodeInfo.signingPublicKey()
                    );
                    logger.info("Updated keys for node " + nodeInfo.nodeId());
                }

                nodes.put(nodeInfo.nodeId(), updated);
            } else {
                // Новый узел
                nodes.put(nodeInfo.nodeId(), nodeInfo);
                logger.info("New node discovered: " + nodeInfo);
                recalculateNeighbors();
            }

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Удаляет узел из кольца
     */
    public void removeNode(long nodeId) {
        lock.writeLock().lock();
        try {
            NodeInfo removed = nodes.remove(nodeId);
            if (removed != null) {
                logger.info("Node removed: " + nodeId);
                recalculateNeighbors();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Удаляет узлы с истёкшим таймаутом
     */
    public void removeExpiredNodes() {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            List<Long> expired = nodes.values().stream()
                    .filter(n -> n.isExpired(now, NODE_TIMEOUT_MS))
                    .map(NodeInfo::nodeId)
                    .collect(Collectors.toList());

            if (!expired.isEmpty()) {
                expired.forEach(nodes::remove);
                logger.info("Removed expired nodes: " + expired);
                recalculateNeighbors();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Пересчитывает соседей в логическом кольце
     */
    private void recalculateNeighbors() {
        List<Long> sortedIds = new ArrayList<>(nodes.keySet());
        sortedIds.add(myNodeId);
        Collections.sort(sortedIds);

        int myIndex = sortedIds.indexOf(myNodeId);
        int size = sortedIds.size();

        if (size == 1) {
            leftNeighbor = null;
            rightNeighbor = null;
        } else {
            int leftIndex = (myIndex - 1 + size) % size;
            int rightIndex = (myIndex + 1) % size;

            leftNeighbor = sortedIds.get(leftIndex);
            rightNeighbor = sortedIds.get(rightIndex);

            // Не указываем на самих себя
            if (leftNeighbor.equals(myNodeId)) leftNeighbor = null;
            if (rightNeighbor.equals(myNodeId)) rightNeighbor = null;
        }

        logger.info(String.format("Ring updated: left=%s, me=%d, right=%s",
                leftNeighbor, myNodeId, rightNeighbor));
    }

    /**
     * ДОБАВЛЕНО: Получить публичные ключи узла
     */
    public NodeKeys getNodeKeys(long nodeId) {
        lock.readLock().lock();
        try {
            NodeInfo node = nodes.get(nodeId);
            if (node != null && node.hasKeys()) {
                return new NodeKeys(
                        node.encryptionPublicKey(),
                        node.signingPublicKey()
                );
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * ДОБАВЛЕНО: Получить публичный ключ шифрования узла
     */
    public PublicKey getEncryptionPublicKey(long nodeId) {
        lock.readLock().lock();
        try {
            NodeInfo node = nodes.get(nodeId);
            return node != null ? node.encryptionPublicKey() : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * ДОБАВЛЕНО: Получить публичный ключ подписи узла
     */
    public PublicKey getSigningPublicKey(long nodeId) {
        lock.readLock().lock();
        try {
            NodeInfo node = nodes.get(nodeId);
            return node != null ? node.signingPublicKey() : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    // Getters

    public NodeInfo getNode(long nodeId) {
        return nodes.get(nodeId);
    }

    public Collection<NodeInfo> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    public Long getLeftNeighbor() {
        return leftNeighbor;
    }

    public Long getRightNeighbor() {
        return rightNeighbor;
    }

    public long getMyNodeId() {
        return myNodeId;
    }

    public int getActiveNodeCount() {
        return nodes.size();
    }

    /**
     * ДОБАВЛЕНО: Контейнер для пары ключей узла
     */
    public record NodeKeys(
            PublicKey encryptionKey,
            PublicKey signingKey
    ) {
    }
}