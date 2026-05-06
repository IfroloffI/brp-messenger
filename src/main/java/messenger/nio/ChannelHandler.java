package messenger.nio;

import java.io.IOException;
import java.nio.channels.SelectionKey;

/**
 * Обработчик NIO событий для канала.
 * <p>
 * Все методы вызываются из NIO потока (NioEventLoop).
 * Методы должны быть быстрыми и не блокироваться.
 */
public interface ChannelHandler {

    /**
     * Обработка события OP_ACCEPT (для ServerSocketChannel).
     * Вызывается когда новое входящее соединение готово быть принято.
     *
     * @param key SelectionKey канала
     * @throws IOException при ошибке I/O
     */
    void handleAccept(SelectionKey key) throws IOException;

    /**
     * Обработка события OP_CONNECT (для SocketChannel).
     * Вызывается когда исходящее соединение завершено.
     *
     * @param key SelectionKey канала
     * @throws IOException при ошибке I/O
     */
    void handleConnect(SelectionKey key) throws IOException;

    /**
     * Обработка события OP_READ.
     * Вызывается когда данные доступны для чтения.
     *
     * @param key SelectionKey канала
     * @throws IOException при ошибке I/O
     */
    void handleRead(SelectionKey key) throws IOException;

    /**
     * Обработка события OP_WRITE.
     * Вызывается когда канал готов для записи.
     *
     * @param key SelectionKey канала
     * @throws IOException при ошибке I/O
     */
    void handleWrite(SelectionKey key) throws IOException;

    /**
     * Обработка ошибки канала.
     * Вызывается при любом исключении в других handler методах.
     *
     * @param key SelectionKey канала
     * @param e   Исключение которое произошло
     */
    void handleError(SelectionKey key, Exception e);
}