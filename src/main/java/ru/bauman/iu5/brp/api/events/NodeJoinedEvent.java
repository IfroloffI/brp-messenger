package ru.bauman.iu5.brp.api.events;

import ru.bauman.iu5.brp.api.dto.NodeDto;

/**
 * Событие подключения узла к кольцу (ТЗ п. 5.3.2).
 */
public class NodeJoinedEvent extends AbstractApplicationEvent {
    private final NodeDto node;

    public NodeJoinedEvent(NodeDto node) {
        super(EventType.NODE_JOINED);
        this.node = node;
    }

    public NodeDto getNode() {
        return node;
    }

    @Override
    public String getDescription() {
        return "Узел " + node.getDisplayName() + " (" + node.getNodeId() + ") подключился к кольцу";
    }

    @Override
    public String toString() {
        return "NodeJoinedEvent{node=" + node + "}";
    }
}
