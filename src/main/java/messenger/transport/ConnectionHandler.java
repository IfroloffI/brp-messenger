package messenger.transport;

import messenger.nio.ChannelHandler;
import messenger.protocol.ChatMessage;
import messenger.protocol.MessageCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.function.Consumer;

/**
 * Обработчик TCP соединения с соседом по кольцу.
 * <p>
 * Реализует ChannelHandler для NIO event loop.
 * Буферизует входящие данные и парсит сообщения по мере поступления.
 */
public final class ConnectionHandler implements ChannelHandler {

    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB

    private final SocketChannel channel;
    private final Consumer<ChatMessage> messageConsumer;
    private final ByteBuffer readBuffer;
    private final String remoteName;

    /**
     * Создание обработчика соединения.
     *
     * @param channel TCP канал
     * @param messageConsumer Обработчик полученных сообщений
     * @param remoteName Имя удалённого узла (для логов)
     */
    public ConnectionHandler(SocketChannel channel,
                             Consumer<ChatMessage> messageConsumer,
                             String remoteName) {
        this.channel = channel;
        this.messageConsumer = messageConsumer;
        this.remoteName = remoteName;
        this.readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    }

    @Override
    public void handleAccept(SelectionKey key) throws IOException {
        // Не используется для клиентских соединений
    }

    @Override
    public void handleConnect(SelectionKey key) throws IOException {
        // Завершение асинхронного connect
        if (channel.finishConnect()) {
            System.out.println("[Connection:" + remoteName + "] Connected");
        }
    }

    @Override
    public void handleRead(SelectionKey key) throws IOException {
        int bytesRead = channel.read(readBuffer);

        if (bytesRead == -1) {
            System.out.println("[Connection:" + remoteName + "] Remote closed connection");
            channel.close();
            key.cancel();
            return;
        }

        if (bytesRead == 0) {
            return;
        }

        System.out.println("[Connection:" + remoteName + "] Read " + bytesRead + " bytes");

        // Переключаем буфер в режим чтения
        readBuffer.flip();

        // Парсим все доступные сообщения
        while (MessageCodec.hasCompleteMessage(readBuffer)) {
            ChatMessage message = MessageCodec.decode(readBuffer);
            if (message != null) {
                System.out.println("[Connection:" + remoteName + "] Received: " + message);
                messageConsumer.accept(message);
            } else {
                break;
            }
        }

        // Компактим буфер (непрочитанные данные в начало)
        readBuffer.compact();
    }

    @Override
    public void handleWrite(SelectionKey key) throws IOException {
        // Запись обрабатывается через отдельный метод sendMessage
    }

    @Override
    public void handleError(SelectionKey key, Exception e) {
        System.err.println("[Connection:" + remoteName + "] Error: " + e.getMessage());
        try {
            channel.close();
            key.cancel();
        } catch (IOException ex) {
            System.err.println("[Connection:" + remoteName + "] Error closing: " + ex.getMessage());
        }
    }

    /**
     * Отправка сообщения через соединение.
     *
     * @param message Сообщение для отправки
     * @return true если успешно отправлено
     */
    public boolean sendMessage(ChatMessage message) {
        try {
            ByteBuffer buffer = MessageCodec.encode(message);

            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                if (written == 0) {
                    // Канал заблокирован, нужна регистрация на WRITE
                    System.err.println("[Connection:" + remoteName + "] Channel blocked on write");
                    return false;
                }
            }

            System.out.println("[Connection:" + remoteName + "] Sent: " + message);
            return true;
        } catch (IOException e) {
            System.err.println("[Connection:" + remoteName + "] Send error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Получение канала.
     */
    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * Проверка открыт ли канал.
     */
    public boolean isOpen() {
        return channel.isOpen() && channel.isConnected();
    }
}