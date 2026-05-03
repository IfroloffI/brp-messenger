package messenger.ring;

import java.net.InetAddress;
import java.util.Objects;

public final class NodeInfo {
    private final long nodeId;
    private final InetAddress ip;

    public NodeInfo(long nodeId, InetAddress ip) {
        this.nodeId = nodeId;
        this.ip = Objects.requireNonNull(ip, "ip");
    }

    public long nodeId() {
        return nodeId;
    }

    public InetAddress ip() {
        return ip;
    }

    @Override
    public String toString() {
        return "(" + ip.getHostAddress() + ", id=" + nodeId + ")";
    }
}
