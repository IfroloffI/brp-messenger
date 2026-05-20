package ru.bauman.iu5.brp.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * UDP Discovery пакет с публичными ключами
 * Формат: [nodeId:8][ipBytes:4][port:4][encKeyLen:4][encKey:variable][signKeyLen:4][signKey:variable]
 */
public class DiscoveryPacket {
    private final long nodeId;
    private final InetAddress address;
    private final int port;
    private final byte[] publicEncryptionKey;
    private final byte[] publicSigningKey;

    public DiscoveryPacket(long nodeId, InetAddress address, int port,
                           byte[] publicEncryptionKey, byte[] publicSigningKey) {
        this.nodeId = nodeId;
        this.address = address;
        this.port = port;
        this.publicEncryptionKey = publicEncryptionKey;
        this.publicSigningKey = publicSigningKey;
    }

    /**
     * Сериализует пакет в байты для отправки по UDP
     */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // 1. Node ID (8 bytes)
        dos.writeLong(nodeId);

        // 2. IP address (4 bytes)
        byte[] ipBytes = address.getAddress();
        dos.write(ipBytes);

        // 3. Port (4 bytes)
        dos.writeInt(port);

        // 4. Encryption public key length + key
        if (publicEncryptionKey != null) {
            dos.writeInt(publicEncryptionKey.length);
            dos.write(publicEncryptionKey);
        } else {
            dos.writeInt(0);
        }

        // 5. Signing public key length + key
        if (publicSigningKey != null) {
            dos.writeInt(publicSigningKey.length);
            dos.write(publicSigningKey);
        } else {
            dos.writeInt(0);
        }

        return baos.toByteArray();
    }

    /**
     * Десериализует пакет из байтов
     */
    public static DiscoveryPacket deserialize(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);

        // 1. Node ID
        long nodeId = dis.readLong();

        // 2. IP address
        byte[] ipBytes = new byte[4];
        dis.readFully(ipBytes);
        InetAddress address;
        try {
            address = InetAddress.getByAddress(ipBytes);
        } catch (UnknownHostException e) {
            throw new IOException("Invalid IP address in packet", e);
        }

        // 3. Port
        int port = dis.readInt();

        // 4. Encryption public key
        int encKeyLen = dis.readInt();
        byte[] encryptionKey = null;
        if (encKeyLen > 0) {
            encryptionKey = new byte[encKeyLen];
            dis.readFully(encryptionKey);
        }

        // 5. Signing public key
        int signKeyLen = dis.readInt();
        byte[] signingKey = null;
        if (signKeyLen > 0) {
            signingKey = new byte[signKeyLen];
            dis.readFully(signingKey);
        }

        return new DiscoveryPacket(nodeId, address, port, encryptionKey, signingKey);
    }

    // Getters

    public long getNodeId() {
        return nodeId;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public byte[] getPublicEncryptionKey() {
        return publicEncryptionKey;
    }

    public byte[] getPublicSigningKey() {
        return publicSigningKey;
    }

    @Override
    public String toString() {
        return String.format("DiscoveryPacket{nodeId=%d, address=%s, port=%d, encKeyLen=%d, signKeyLen=%d}",
                nodeId, address.getHostAddress(), port,
                publicEncryptionKey != null ? publicEncryptionKey.length : 0,
                publicSigningKey != null ? publicSigningKey.length : 0);
    }
}
