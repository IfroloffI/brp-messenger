package ru.bauman.iu5.brp.api.events;

/**
 * Событие отключения узла от кольца (ТЗ п. 5.3.2).
 */
public class NodeLeftEvent extends AbstractApplicationEvent {
    private final long nodeId;
    private final String nodeName;
    private final boolean wasGraceful;

    public NodeLeftEvent(long nodeId, String nodeName, boolean wasGraceful) {
        super(EventType.NODE_LEFT);
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.wasGraceful = wasGraceful;
    }

    public long getNodeId() {
        return nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public boolean wasGraceful() {
        return wasGraceful;
    }

    @Override
    public String getDescription() {
        return "Узел " + (nodeName != null ? nodeName : nodeId) +
                (wasGraceful ? " корректно отключился" : " потерян (таймаут)");
    }

    @Override
    public String toString() {
        return "NodeLeftEvent{nodeId=" + nodeId + ", graceful=" + wasGraceful + "}";
    }
}
