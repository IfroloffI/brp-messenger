package messenger.ring;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Состояние кольцевой топологии.
 * <p>
 * Thread-safe: все операции синхронизированы.
 */
public final class RingState {

    private final AtomicLong myNodeId;
    private final Map<Long, NodeInfo> nodes;

    public RingState() {
        this.myNodeId = new AtomicLong(generateNodeId());
        this.nodes = new ConcurrentHashMap<>();
    }

    /**
     * Получить ID текущего узла.
     */
    public long myId() {
        return myNodeId.get();
    }

    /**
     * Регенерация nodeId (при конфликте).
     */
    public void regenerateMyId() {
        long newId = generateNodeId();
        myNodeId.set(newId);
        System.out.println("[RingState] NodeId regenerated: " + newId);
    }

    /**
     * Добавление или обновление узла.
     *
     * @param node Информация об узле
     * @return true если узел новый, false если обновлён
     */
    public boolean addOrUpdateNode(NodeInfo node) {
        boolean isNew = !nodes.containsKey(node.nodeId());
        nodes.put(node.nodeId(), node);

        if (isNew) {
            System.out.println("[RingState] Added node: " + node);
            rebuildRing();
        }

        return isNew;
    }

    /**
     * Удаление устаревших узлов.
     *
     * @param timeoutMs Таймаут в миллисекундах
     * @return Количество удалённых узлов
     */
    public int removeOldNodes(long timeoutMs) {
        long now = System.currentTimeMillis();
        int removed = 0;

        Iterator<Map.Entry<Long, NodeInfo>> it = nodes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, NodeInfo> entry = it.next();
            long lastSeen = entry.getValue().lastSeenMs();

            if (now - lastSeen > timeoutMs) {
                System.out.println("[RingState] Removing timed-out node: " + entry.getKey() +
                        " (last seen " + (now - lastSeen) + "ms ago)");
                it.remove();
                removed++;
            }
        }

        if (removed > 0) {
            rebuildRing();
        }

        return removed;
    }

    /**
     * Проверка существования узла.
     */
    public boolean hasNode(long nodeId) {
        return nodes.containsKey(nodeId);
    }

    /**
     * Получение информации об узле.
     */
    public NodeInfo getNode(long nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Получение всех узлов.
     */
    public List<NodeInfo> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * Получение правого соседа (следующий по кольцу).
     */
    public Optional<NodeInfo> rightNeighbor() {
        List<Long> sortedIds = getSortedNodeIds();

        if (sortedIds.isEmpty()) {
            return Optional.empty();
        }

        long myId = myId();
        int myIndex = sortedIds.indexOf(myId);

        // Если нас нет в списке или мы одни
        if (myIndex == -1 || sortedIds.size() == 1) {
            return Optional.empty();
        }

        // Следующий по кольцу (с wraparound)
        int rightIndex = (myIndex + 1) % sortedIds.size();
        long rightId = sortedIds.get(rightIndex);

        return Optional.ofNullable(nodes.get(rightId));
    }

    /**
     * Получение левого соседа (предыдущий по кольцу).
     */
    public Optional<NodeInfo> leftNeighbor() {
        List<Long> sortedIds = getSortedNodeIds();

        if (sortedIds.isEmpty()) {
            return Optional.empty();
        }

        long myId = myId();
        int myIndex = sortedIds.indexOf(myId);

        if (myIndex == -1 || sortedIds.size() == 1) {
            return Optional.empty();
        }

        // Предыдущий по кольцу (с wraparound)
        int leftIndex = (myIndex - 1 + sortedIds.size()) % sortedIds.size();
        long leftId = sortedIds.get(leftIndex);

        return Optional.ofNullable(nodes.get(leftId));
    }

    /**
     * Получение отсортированных ID узлов (включая себя).
     */
    private List<Long> getSortedNodeIds() {
        List<Long> ids = new ArrayList<>(nodes.keySet());
        ids.add(myId());
        Collections.sort(ids);
        return ids;
    }

    /**
     * Перестроение кольца (вызывается при изменении топологии).
     */
    private void rebuildRing() {
        List<Long> sortedIds = getSortedNodeIds();
        System.out.println("[RingState] Ring topology: " + sortedIds);

        rightNeighbor().ifPresentOrElse(
                right -> System.out.println("[RingState] Right neighbor: " + right.nodeId()),
                () -> System.out.println("[RingState] No right neighbor")
        );

        leftNeighbor().ifPresentOrElse(
                left -> System.out.println("[RingState] Left neighbor: " + left.nodeId()),
                () -> System.out.println("[RingState] No left neighbor")
        );
    }

    /**
     * Генерация нового nodeId на основе timestamp и random.
     */
    private static long generateNodeId() {
        return System.currentTimeMillis() + new Random().nextInt(10000);
    }

    /**
     * Количество известных узлов (не считая себя).
     */
    public int nodeCount() {
        return nodes.size();
    }
}
