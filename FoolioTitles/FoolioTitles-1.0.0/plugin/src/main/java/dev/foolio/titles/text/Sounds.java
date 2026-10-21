package dev.foolio.titles.text;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/** Звуки из config.yml (sounds). Ключи звуков ванильные, например minecraft:ui.button.click. */
public final class Sounds {

    private final Map<String, String> sounds = new HashMap<>();
    private boolean enabled = true;

    public void load(ConfigurationSection section) {
        sounds.clear();
        if (section == null) {
            enabled = false;
            return;
        }
        enabled = section.getBoolean("enabled", true);
        for (String key : section.getKeys(false)) {
            if (section.isString(key)) {
                sounds.put(key, section.getString(key));
            }
        }
    }

    public void play(Player player, String key) {
        if (!enabled || player == null) {
            return;
        }
        String sound = sounds.get(key);
        if (sound == null || sound.isEmpty()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound, 0.7f, 1.0f);
        } catch (RuntimeException ignored) {
            // неизвестный звук — молча пропускаем
        }
    }
}
