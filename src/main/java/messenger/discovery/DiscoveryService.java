package messenger.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PublicKey;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import messenger.crypto.KeyPairManager;
import messenger.crypto.KeyStorage;
import messenger.protocol.DiscoveryPacket;
import messenger.ring.NodeInfo;
import messenger.ring.RingState;

/**
 * Сервис обнаружения узлов через UDP broadcast с обменом публичными ключами
 */
public class DiscoveryService implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(DiscoveryService.class.getName());

    private static final int DISCOVERY_PORT = 9876;
    private static final int BEACON_INTERVAL_MS = 2000;
    private static final int BUFFER_SIZE = 2048; // увеличен для ключей

    private final long myNodeId;
    private final int myTcpPort;
    private final RingState ringState;
    private final KeyStorage keyStorage;  // ДОБАВЛЕНО
    private final KeyPairManager keyPairManager;  // ДОБАВЛЕНО

    private DatagramSocket socket;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public DiscoveryService(long myNodeId, int myTcpPort, RingState ringState, KeyStorage keyStorage) {
        this.myNodeId = myNodeId;
        this.myTcpPort = myTcpPort;
        this.ringState = ringState;
        this.keyStorage = keyStorage;  // ДОБАВЛЕНО
        this.keyPairManager = new KeyPairManager();  // ДОБАВЛЕНО
    }

    /**
     * Запускает сервис обнаружения
     */
    public void start() throws IOException {
        socket = new DatagramSocket(DISCOVERY_PORT);
        socket.setBroadcast(true);
        running = true;

        // Поток для приёма beacon'ов
        Thread receiverThread = new Thread(this::receiveBeacons, "Discovery-Receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();

        // Периодическая отправка своих beacon'ов
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(
                this::sendBeacon,
                0,
                BEACON_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        logger.info("Discovery service started on UDP port " + DISCOVERY_PORT);
    }

    /**
     * Отправляет beacon с информацией о себе + публичные ключи
     */
    private void sendBeacon() {
        try {
            InetAddress localAddress = getLocalAddress();

            // ИЗМЕНЕНО: добавляем публичные ключи в пакет
            byte[] encryptionKeyBytes = keyPairManager.publicKeyToBytes(
                    keyStorage.getEncryptionPublicKey()
            );
            byte[] signingKeyBytes = keyPairManager.publicKeyToBytes(
                    keyStorage.getSigningPublicKey()
            );

            DiscoveryPacket packet = new DiscoveryPacket(
                    myNodeId,
                    localAddress,
                    myTcpPort,
                    encryptionKeyBytes,  // ДОБАВЛЕНО
                    signingKeyBytes       // ДОБАВЛЕНО
            );

            byte[] data = packet.serialize();
            DatagramPacket udpPacket = new DatagramPacket(
                    data,
                    data.length,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT
            );

            socket.send(udpPacket);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send beacon", e);
        }
    }

    /**
     * Принимает beacon'ы от других узлов
     */
    private void receiveBeacons() {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(udpPacket);

                byte[] data = new byte[udpPacket.getLength()];
                System.arraycopy(udpPacket.getData(), 0, data, 0, udpPacket.getLength());

                DiscoveryPacket packet = DiscoveryPacket.deserialize(data);

                // Игнорируем свои пакеты
                if (packet.getNodeId() == myNodeId) {
                    continue;
                }

                // ИЗМЕНЕНО: восстанавливаем публичные ключи из байтов
                PublicKey encryptionKey = null;
                PublicKey signingKey = null;

                if (packet.getPublicEncryptionKey() != null) {
                    try {
                        encryptionKey = keyPairManager.bytesToPublicKey(
                                packet.getPublicEncryptionKey()
                        );
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to parse encryption key from node "
                                + packet.getNodeId(), e);
                    }
                }

                if (packet.getPublicSigningKey() != null) {
                    try {
                        signingKey = keyPairManager.bytesToPublicKey(
                                packet.getPublicSigningKey()
                        );
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to parse signing key from node "
                                + packet.getNodeId(), e);
                    }
                }

                // Создаём NodeInfo с ключами
                NodeInfo nodeInfo = new NodeInfo(
                        packet.getNodeId(),
                        packet.getAddress(),
                        packet.getPort(),
                        System.currentTimeMillis(),
                        encryptionKey,  // ДОБАВЛЕНО
                        signingKey      // ДОБАВЛЕНО
                );

                // Обновляем состояние кольца
                ringState.updateNode(nodeInfo);

                if (encryptionKey != null && signingKey != null) {
                    logger.fine("Received beacon from node " + packet.getNodeId() + " with keys");
                } else {
                    logger.warning("Received beacon from node " + packet.getNodeId() + " without keys");
                }

            } catch (Exception e) {
                if (running) {
                    logger.log(Level.WARNING, "Error receiving beacon", e);
                }
            }
        }
    }

    /**
     * Получает локальный IP адрес
     */
    private InetAddress getLocalAddress() throws UnknownHostException {
        try {
            // Пытаемся получить реальный IP через dummy-соединение
            try (DatagramSocket s = new DatagramSocket()) {
                s.connect(InetAddress.getByName("8.8.8.8"), 80);
                return s.getLocalAddress();
            }
        } catch (Exception e) {
            // Fallback на localhost
            return InetAddress.getLocalHost();
        }
    }

    @Override
    public void close() {
        running = false;

        if (scheduler != null) {
            scheduler.shutdown();
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        logger.info("Discovery service stopped");
    }

    /**
     * Совместимый alias для кода, который ожидает stop().
     */
    public void stop() {
        close();
    }
}