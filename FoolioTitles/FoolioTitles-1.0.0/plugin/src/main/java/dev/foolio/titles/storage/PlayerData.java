// FoolioTitles - Development by DeJIoVoy
package dev.foolio.titles.storage;

import java.util.UUID;

public record PlayerData(UUID uuid, String name, int tokens, int strikes, Integer activeTitle) {
}
