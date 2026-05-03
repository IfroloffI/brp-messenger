package messenger.ring;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public final class RingState {
    private static final Logger LOG = Logger.getLogger(RingState.class.getName());

    private volatile long myId;

    private volatile NodeInfo leftNeighbor;
    private volatile NodeInfo rightNeighbor;

    public RingState(long myId) {
        this.myId = myId;
    }

    public long myId() {
        return myId;
    }

    public void setMyId(long newId) {
        long previous = this.myId;
        if (newId == previous) {
            return;
        }
        this.myId = newId;
        LOG.info("[myId=" + newId + "] [RING] myId remapped | " + previous + " -> " + newId);
    }

    public Optional<NodeInfo> leftNeighbor() {
        return Optional.ofNullable(leftNeighbor);
    }

    public Optional<NodeInfo> rightNeighbor() {
        return Optional.ofNullable(rightNeighbor);
    }

    public synchronized void recompute(Map<Long, InetAddress> liveNodes) {
        InetAddress myIp = liveNodes.get(myId);
        if (myIp == null) {
            return;
        }

        List<Long> ids = new ArrayList<>(liveNodes.keySet());
        ids.sort(Comparator.naturalOrder());
        if (!ids.contains(myId)) {
            return;
        }

        List<Long> ringOrder = buildRingOrder(ids);
        if (ringOrder.size() <= 1) {
            leftNeighbor = null;
            rightNeighbor = null;
            logState("single-node ring");
            return;
        }

        int index = ringOrder.indexOf(myId);
        int leftIndex = (index - 1 + ringOrder.size()) % ringOrder.size();
        int rightIndex = (index + 1) % ringOrder.size();
        long leftId = ringOrder.get(leftIndex);
        long rightId = ringOrder.get(rightIndex);
        NodeInfo newLeft = new NodeInfo(leftId, liveNodes.get(leftId));
        NodeInfo newRight = new NodeInfo(rightId, liveNodes.get(rightId));

        boolean changed = !equalsNeighbor(leftNeighbor, newLeft) || !equalsNeighbor(rightNeighbor, newRight);
        leftNeighbor = newLeft;
        rightNeighbor = newRight;
        if (changed) {
            LOG.info(logPrefix() + " [RING] neighbors updated | left=" + newLeft + " | right=" + newRight);
            logState("recomputed");
        }
    }

    private static List<Long> buildRingOrder(List<Long> sortedIds) {
        long first = sortedIds.get(0);
        List<Long> others = new ArrayList<>(sortedIds.subList(1, sortedIds.size()));
        others.sort(Comparator.reverseOrder());

        List<Long> result = new ArrayList<>();
        result.add(first);
        result.addAll(others);
        return result;
    }

    private boolean equalsNeighbor(NodeInfo first, NodeInfo second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.nodeId() == second.nodeId() && first.ip().equals(second.ip());
    }

    public void logState(String reason) {
        String left = leftNeighbor == null ? "null" : leftNeighbor.nodeId() + "(" + leftNeighbor.ip().getHostAddress() + ")";
        String right = rightNeighbor == null ? "null" : rightNeighbor.nodeId() + "(" + rightNeighbor.ip().getHostAddress() + ")";
        LOG.info(logPrefix() + " [RING] state | reason=" + reason + " | myId=" + myId + " | left=" + left + " | right=" + right);
    }

    private String logPrefix() {
        return "[myId=" + myId + "]";
    }
}
