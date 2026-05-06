package ru.bauman.iu5.brp.api.events;

/**
 * Событие успешного запуска сетевого стека.
 */
public class NetworkStartedEvent extends AbstractApplicationEvent {
    private final int tcpPort;
    private final boolean udpDiscoveryEnabled;

    public NetworkStartedEvent(int tcpPort, boolean udpDiscoveryEnabled) {
        super(EventType.NETWORK_STARTED);
        this.tcpPort = tcpPort;
        this.udpDiscoveryEnabled = udpDiscoveryEnabled;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public boolean isUdpDiscoveryEnabled() {
        return udpDiscoveryEnabled;
    }

    @Override
    public String getDescription() {
        return "Сеть запущена на порту " + tcpPort +
                (udpDiscoveryEnabled ? " (UDP Discovery активен)" : " (ручной режим)");
    }

    @Override
    public String toString() {
        return "NetworkStartedEvent{tcpPort=" + tcpPort + ", udpDiscovery=" + udpDiscoveryEnabled + "}";
    }
}
