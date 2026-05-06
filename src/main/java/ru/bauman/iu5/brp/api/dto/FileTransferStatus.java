package ru.bauman.iu5.brp.api.dto;

/**
 * Статус передачи файла.
 */
public enum FileTransferStatus {
    /**
     * Загрузка файла (исходящая передача).
     */
    UPLOADING,

    /**
     * Скачивание файла (входящая передача).
     */
    DOWNLOADING,

    /**
     * Передача успешно завершена.
     */
    COMPLETED,

    /**
     * Передача отменена пользователем.
     */
    CANCELLED,

    /**
     * Ошибка передачи (сетевая ошибка, узел недоступен).
     */
    ERROR;

    public boolean isActive() {
        return this == UPLOADING || this == DOWNLOADING;
    }

    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED || this == ERROR;
    }
}
