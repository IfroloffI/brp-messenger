package ru.bauman.iu5.brp.api.events;

import ru.bauman.iu5.brp.api.dto.FileTransferDto;

/**
 * Событие обновления прогресса передачи файла.
 */
public class FileTransferProgressEvent extends AbstractApplicationEvent {
    private final FileTransferDto transfer;

    public FileTransferProgressEvent(FileTransferDto transfer) {
        super(EventType.FILE_TRANSFER_PROGRESS);
        this.transfer = transfer;
    }

    public FileTransferDto getTransfer() {
        return transfer;
    }

    @Override
    public String getDescription() {
        return String.format("Прогресс передачи '%s': %.1f%%",
                transfer.getFileName(), transfer.getProgressPercent());
    }

    @Override
    public String toString() {
        return "FileTransferProgressEvent{transfer=" + transfer + "}";
    }
}
