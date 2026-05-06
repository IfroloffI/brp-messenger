package ru.bauman.iu5.brp.api.dto;

import java.time.Instant;

/**
 * Статистика работы сети для диагностики.
 */
public class NetworkStatistics {
    private final Instant startTime;
    private long bytesSent;
    private long bytesReceived;
    private long framesSent;
    private long framesReceived;
    private long messagesDelivered;
    private long messagesReceived;
    private long filesDelivered;
    private long filesReceived;
    private int ackFramesSent;
    private int ackFramesReceived;
    private int retransmissions;
    private int currentRingSize;
    private int totalErrors;

    public NetworkStatistics() {
        this.startTime = Instant.now();
    }

    public Instant getStartTime() {
        return startTime;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public void addBytesSent(long bytes) {
        this.bytesSent += bytes;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public void addBytesReceived(long bytes) {
        this.bytesReceived += bytes;
    }

    public long getFramesSent() {
        return framesSent;
    }

    public void incrementFramesSent() {
        this.framesSent++;
    }

    public long getFramesReceived() {
        return framesReceived;
    }

    public void incrementFramesReceived() {
        this.framesReceived++;
    }

    public long getMessagesDelivered() {
        return messagesDelivered;
    }

    public void incrementMessagesDelivered() {
        this.messagesDelivered++;
    }

    public long getMessagesReceived() {
        return messagesReceived;
    }

    public void incrementMessagesReceived() {
        this.messagesReceived++;
    }

    public long getFilesDelivered() {
        return filesDelivered;
    }

    public void incrementFilesDelivered() {
        this.filesDelivered++;
    }

    public long getFilesReceived() {
        return filesReceived;
    }

    public void incrementFilesReceived() {
        this.filesReceived++;
    }

    public int getAckFramesSent() {
        return ackFramesSent;
    }

    public void incrementAckFramesSent() {
        this.ackFramesSent++;
    }

    public int getAckFramesReceived() {
        return ackFramesReceived;
    }

    public void incrementAckFramesReceived() {
        this.ackFramesReceived++;
    }

    public int getRetransmissions() {
        return retransmissions;
    }

    public void incrementRetransmissions() {
        this.retransmissions++;
    }

    public int getCurrentRingSize() {
        return currentRingSize;
    }

    public void setCurrentRingSize(int currentRingSize) {
        this.currentRingSize = currentRingSize;
    }

    public int getTotalErrors() {
        return totalErrors;
    }

    public void incrementTotalErrors() {
        this.totalErrors++;
    }

    /**
     * Возвращает время работы сети в секундах.
     */
    public long getUptimeSeconds() {
        return Instant.now().getEpochSecond() - startTime.getEpochSecond();
    }

    @Override
    public String toString() {
        return "NetworkStatistics{" +
                "uptime=" + getUptimeSeconds() + "s" +
                ", sent=" + bytesSent + " bytes" +
                ", received=" + bytesReceived + " bytes" +
                ", messages=" + messagesDelivered + "/" + messagesReceived +
                ", files=" + filesDelivered + "/" + filesReceived +
                ", retransmissions=" + retransmissions +
                ", errors=" + totalErrors +
                ", ringSize=" + currentRingSize +
                '}';
    }
}
