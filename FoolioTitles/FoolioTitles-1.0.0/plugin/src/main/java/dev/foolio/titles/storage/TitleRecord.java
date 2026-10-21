package dev.foolio.titles.storage;

import java.util.UUID;

/** Титул, созданный через плагин. {@code raw} хранится в формате MiniMessage. */
public record TitleRecord(
        int id,
        UUID owner,
        String ownerName,
        String raw,
        TitleStatus status,
        long createdAt,
        String reviewedBy,
        long reviewedAt,
        String reason,
        boolean refunded
) {
}
