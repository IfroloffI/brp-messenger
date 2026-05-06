package messenger.protocol;

import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * UDP пакет для автоматического обнаружения узлов.
 * <p>
 * Формат (16 байт):
 * - nodeId: 8 байт (long)
 * - IP address: 4 байта (IPv4)
 * - TCP port: 4 байта (int)
 */
public record DiscoveryPacket(
        long nodeId,
        InetAddress ipAddress,
        int tcpPort
) {
    /**
     * Сериализация в ByteBuffer.
     *
     * @return ByteBuffer готовый к отправке (position=0, limit=16)
     */
    public ByteBuffer toByteBuffer() {
        byte[] ipBytes = ipAddress.getAddress();

        if (ipBytes.length != 4) {
            throw new IllegalStateException("Only IPv4 is supported");
        }

        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(nodeId);
        buffer.put(ipBytes);
        buffer.putInt(tcpPort);

        buffer.flip();
        return buffer;
    }

    /**
     * Десериализация из ByteBuffer.
     *
     * @param buffer ByteBuffer с данными (минимум 16 байт)
     * @return DiscoveryPacket или null при ошибке
     */
    public static DiscoveryPacket fromByteBuffer(ByteBuffer buffer) {
        try {
            if (buffer.remaining() < 16) {
                return null;
            }

            long nodeId = buffer.getLong();

            byte[] ipBytes = new byte[4];
            buffer.get(ipBytes);
            InetAddress ipAddress = InetAddress.getByAddress(ipBytes);

            int tcpPort = buffer.getInt();

            return new DiscoveryPacket(nodeId, ipAddress, tcpPort);

        } catch (Exception e) {
            System.err.println("[DiscoveryPacket] Parse error: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format("DiscoveryPacket[nodeId=%d, ip=%s, port=%d]",
                nodeId, ipAddress.getHostAddress(), tcpPort);
    }
}
