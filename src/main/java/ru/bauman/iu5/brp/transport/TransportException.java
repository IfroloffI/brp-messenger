package ru.bauman.iu5.brp.transport;

/**
 * Thrown by RingTransport when a message cannot be delivered to the network
 * (no encryption key, no route, connection failure, etc.).
 */
public class TransportException extends Exception {
    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
