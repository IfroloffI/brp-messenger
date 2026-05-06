package ru.bauman.iu5.brp.api.events;

/**
 * Событие остановки сетевого стека.
 */
public class NetworkStoppedEvent extends AbstractApplicationEvent {

    public NetworkStoppedEvent() {
        super(EventType.NETWORK_STOPPED);
    }

    @Override
    public String getDescription() {
        return "Сеть остановлена";
    }

    @Override
    public String toString() {
        return "NetworkStoppedEvent{}";
    }
}
