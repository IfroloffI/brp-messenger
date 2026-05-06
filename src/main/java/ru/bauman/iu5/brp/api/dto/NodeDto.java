package ru.bauman.iu5.brp.api.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * DTO для информации об узле в сети.
 */
public class NodeDto {
    private final long nodeId;
    private String name;
    private final String ipAddress;
    private final int port;
    private boolean online;
    private Instant lastSeen;
    private boolean hasPublicKey;

    public NodeDto(long nodeId, String name, String ipAddress, int port, boolean online) {
        this.nodeId = nodeId;
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
        this.online = online;
        this.lastSeen = online ? Instant.now() : null;
        this.hasPublicKey = false;
    }

    public long getNodeId() {
        return nodeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
        if (online) {
            this.lastSeen = Instant.now();
        }
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public boolean hasPublicKey() {
        return hasPublicKey;
    }

    public void setHasPublicKey(boolean hasPublicKey) {
        this.hasPublicKey = hasPublicKey;
    }

    /**
     * Возвращает отображаемое имя узла (имя или ID если имя неизвестно).
     */
    public String getDisplayName() {
        return name != null && !name.isEmpty() ? name : "Node_" + nodeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeDto nodeDto = (NodeDto) o;
        return nodeId == nodeDto.nodeId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "NodeDto{" +
                "nodeId=" + nodeId +
                ", name='" + name + "\\'" +
                ", address=" + ipAddress + ":" + port +
                ", online=" + online +
                '}';
    }
}
