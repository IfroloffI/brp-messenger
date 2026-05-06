package messenger.ring;

import java.net.InetAddress;

/**
 * Информация об узле в кольце.
 *
 * @param nodeId     Уникальный ID узла
 * @param ip         IP адрес узла
 * @param tcpPort    TCP порт для Ring Transport
 * @param lastSeenMs Время последнего обнаружения (System.currentTimeMillis())
 */
public record NodeInfo(
        long nodeId,
        InetAddress ip,
        int tcpPort,
        long lastSeenMs
) {
    @Override
    public String toString() {
        return String.format("Node[id=%d, %s:%d, seen=%dms ago]",
                nodeId, ip.getHostAddress(), tcpPort,
                System.currentTimeMillis() - lastSeenMs);
    }
}
