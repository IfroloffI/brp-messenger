package ru.bauman.iu5.brp.api.mock;

import ru.bauman.iu5.brp.api.dto.*;
import ru.bauman.iu5.brp.api.events.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Генератор тестовых данных для mock реализации.
 */
public class MockDataGenerator {

    private static final String[] NODE_NAMES = {
            "Alice", "Bob", "Charlie", "David", "Eve", "Frank", "Grace", "Henry"
    };

    private static final String[] MESSAGE_TEMPLATES = {
            "Привет! Как дела?",
            "Отлично, спасибо!",
            "Ты видел последние новости?",
            "Да, очень интересно",
            "Когда встретимся?",
            "Может быть завтра?",
            "Хорошо, договорились",
            "Отправляю тебе файл",
            "Получил, спасибо!",
            "Это важная информация",
            "Согласен, нужно обсудить",
            "Давай созвонимся позже",
            "Отлично работает!",
            "Проверь, пожалуйста",
            "Всё готово",
            "Спасибо за помощь!",
            "Не за что :)",
            "До встречи!",
            "Пока!"
    };

    private static final String[] FILE_NAMES = {
            "document.pdf", "presentation.pptx", "spreadsheet.xlsx",
            "photo.jpg", "archive.zip", "video.mp4",
            "report.docx", "data.csv", "diagram.png"
    };

    private final Random random = ThreadLocalRandom.current();

    /**
     * Генерирует фейковый узел с случайными параметрами.
     */
    public NodeDto generateNode() {
        long nodeId = 10000L + random.nextInt(90000);
        String name = NODE_NAMES[random.nextInt(NODE_NAMES.length)];
        String ip = "192.168.1." + (10 + random.nextInt(240));
        int port = 5000 + random.nextInt(100);
        boolean online = random.nextDouble() > 0.2; // 80% онлайн

        NodeDto node = new NodeDto(nodeId, name, ip, port, online);
        node.setHasPublicKey(random.nextDouble() > 0.3); // 70% обменялись ключами

        if (!online) {
            node.setLastSeen(Instant.now().minus(random.nextInt(60), ChronoUnit.MINUTES));
        }

        return node;
    }

    /**
     * Генерирует список узлов.
     */
    public List<NodeDto> generateNodes(int count) {
        List<NodeDto> nodes = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (int i = 0; i < count; i++) {
            NodeDto node = generateNode();
            // Избегаем дублирования имён
            while (usedNames.contains(node.getName())) {
                node.setName(NODE_NAMES[random.nextInt(NODE_NAMES.length)]);
            }
            usedNames.add(node.getName());
            nodes.add(node);
        }

        return nodes;
    }

    /**
     * Генерирует случайное текстовое сообщение.
     */
    public String generateMessageText() {
        return MESSAGE_TEMPLATES[random.nextInt(MESSAGE_TEMPLATES.length)];
    }

    /**
     * Генерирует входящее сообщение от узла.
     */
    public ChatMessageDto generateIncomingMessage(long senderNodeId, long myNodeId) {
        String messageId = UUID.randomUUID().toString();
        Instant timestamp = Instant.now().minus(random.nextInt(300), ChronoUnit.SECONDS);
        String text = generateMessageText();
        SignatureStatus signatureStatus = random.nextDouble() > 0.05 ?
                SignatureStatus.VERIFIED : SignatureStatus.UNVERIFIED;

        return new ChatMessageDto(messageId, senderNodeId, myNodeId, timestamp, text, signatureStatus);
    }

    /**
     * Генерирует историю сообщений с узлом.
     */
    public List<ChatMessageDto> generateMessageHistory(long peerNodeId, long myNodeId, int messageCount) {
        List<ChatMessageDto> history = new ArrayList<>();
        Instant baseTime = Instant.now().minus(messageCount * 5L, ChronoUnit.MINUTES);

        for (int i = 0; i < messageCount; i++) {
            boolean isOutgoing = random.nextBoolean();
            String messageId = UUID.randomUUID().toString();
            Instant timestamp = baseTime.plus(i * 5L, ChronoUnit.MINUTES);
            String text = generateMessageText();

            ChatMessageDto message;
            if (isOutgoing) {
                message = ChatMessageDto.outgoing(messageId, peerNodeId, timestamp, text);
                message.setDeliveryStatus(DeliveryStatus.DELIVERED);
            } else {
                message = new ChatMessageDto(messageId, peerNodeId, myNodeId, timestamp, text,
                        SignatureStatus.VERIFIED);
                message.setRead(random.nextDouble() > 0.3); // 70% прочитаны
            }

            history.add(message);
        }

        return history;
    }

    /**
     * Генерирует случайное имя файла.
     */
    public String generateFileName() {
        return FILE_NAMES[random.nextInt(FILE_NAMES.length)];
    }

    /**
     * Генерирует случайный размер файла (от 100 КБ до 100 МБ).
     */
    public long generateFileSize() {
        return (100L * 1024) + random.nextLong(100L * 1024 * 1024 - 100L * 1024);
    }

    /**
     * Генерирует фейковую передачу файла.
     */
    public FileTransferDto generateFileTransfer(long peerNodeId, boolean outgoing) {
        String transferId = UUID.randomUUID().toString();
        String fileName = generateFileName();
        long fileSize = generateFileSize();

        FileTransferDto transfer = outgoing ?
                FileTransferDto.outgoing(transferId, peerNodeId, fileName, fileSize) :
                FileTransferDto.incoming(transferId, peerNodeId, fileName, fileSize);

        // Случайный прогресс
        if (random.nextDouble() > 0.5) {
            long progress = random.nextLong(fileSize);
            transfer.setBytesTransferred(progress);
        }

        return transfer;
    }

    /**
     * Генерирует случайную задержку в миллисекундах (от 500 до 3000 мс).
     */
    public long generateDelay() {
        return 500 + random.nextInt(2500);
    }

    /**
     * Возвращает случайный элемент из массива.
     */
    public <T> T randomElement(T[] array) {
        return array[random.nextInt(array.length)];
    }

    /**
     * Возвращает случайный элемент из списка.
     */
    public <T> T randomElement(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}
