package ru.bauman.iu5.brp.api.events;

/**
 * Событие переподключения узла после временной потери связи.
 */
public class NodeReconnectedEvent extends AbstractApplicationEvent {
    private final long nodeId;
    private final String nodeName;

    public NodeReconnectedEvent(long nodeId, String nodeName) {
        super(EventType.NODE_RECONNECTED);
        this.nodeId = nodeId;
        this.nodeName = nodeName;
    }

    public long getNodeId() {
        return nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    @Override
    public String getDescription() {
        return "Узел " + (nodeName != null ? nodeName : nodeId) + " переподключился";
    }

    @Override
    public String toString() {
        return "NodeReconnectedEvent{nodeId=" + nodeId + "}";
    }
}
