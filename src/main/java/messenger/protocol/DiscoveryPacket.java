package messenger.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class DiscoveryPacket {
    public static final String TYPE = "BEACON";

    private final long nodeId;
    private final String ip;

    public DiscoveryPacket(long nodeId, String ip) {
        this.nodeId = nodeId;
        this.ip = ip;
    }

    public long nodeId() {
        return nodeId;
    }

    public String ip() {
        return ip;
    }

    public byte[] toBytes() {
        String payload = TYPE + "|" + nodeId + "|" + ip;
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    public static Optional<DiscoveryPacket> fromBytes(byte[] bytes, int length) {
        try {
            String payload = new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
            String[] parts = payload.split("\\|", 3);
            if (parts.length != 3 || !TYPE.equals(parts[0])) {
                return Optional.empty();
            }
            long id = Long.parseLong(parts[1]);
            return Optional.of(new DiscoveryPacket(id, parts[2]));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
