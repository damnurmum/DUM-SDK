package ru.dum.sdk.fabric.update;

/** Immutable updater snapshot shared with the render thread. Error IDs are local, not wire IDs. */
public record UpdateStatus(Stage stage, int attempt, String version, String errorCode, String detail) {
    public static final int MAX_ATTEMPTS = 3;
    public enum Stage { DISABLED, IDLE, CHECKING, DOWNLOADING, VERIFYING, RETRY_WAIT, CURRENT, READY, FAILED }

    public boolean busy() {
        return switch (stage) {
            case CHECKING, DOWNLOADING, VERIFYING, RETRY_WAIT -> true;
            default -> false;
        };
    }

    public String title() {
        return switch (stage) {
            case DISABLED -> "Автообновление отключено в dev-клиенте";
            case IDLE -> "Обновления ещё не проверялись";
            case CHECKING -> attempt > 1 ? "Повторная попытка " + attempt + "/" + MAX_ATTEMPTS : "Проверка обновлений DUM-SDK";
            case DOWNLOADING -> "Загрузка DUM-SDK " + version;
            case VERIFYING -> "Проверка подписи DUM-SDK " + version;
            case RETRY_WAIT -> "Не удалось обновить DUM-SDK";
            case CURRENT -> "Установлена актуальная версия DUM-SDK";
            case READY -> "DUM-SDK " + version + " загружен";
            case FAILED -> "Ошибка обновления DUM-SDK";
        };
    }

    public String message() {
        return switch (stage) {
            case CHECKING -> "Ищем новую версию на GitHub";
            case DOWNLOADING -> "Обновление началось. Игру можно продолжать";
            case VERIFYING -> "Проверяем файл перед установкой";
            case RETRY_WAIT -> detail + " Повторная попытка " + (attempt + 1) + "/" + MAX_ATTEMPTS
                    + " через " + (attempt == 1 ? 5 : 15) + " с. Код: " + errorCode;
            case READY -> "Установится после перезапуска игры";
            case FAILED -> detail + " Скачай SDK вручную с GitHub. Код: " + errorCode;
            case CURRENT -> "Обновление не требуется";
            case DISABLED -> "Игровая версия проверяет обновления автоматически";
            default -> "Проверить обновления можно здесь";
        };
    }
}
