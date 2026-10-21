// FoolioTitles - Development by DeJIoVoy
package dev.foolio.titles.storage;

public enum TitleStatus {
    /** Ожидает проверки администрацией. */
    PENDING,
    /** Одобрен — лежит в хранилище игрока. */
    APPROVED,
    /** Отклонён администрацией. */
    REJECTED,
    /** Заявка отозвана самим игроком. */
    CANCELLED,
    /** Одобренный титул снят администрацией. */
    REVOKED,
    /** Игрок сам удалил титул из хранилища. */
    DELETED;

    public static TitleStatus parse(String s) {
        try {
            return valueOf(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            return DELETED;
        }
    }
}
