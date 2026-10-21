package dev.foolio.titles.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import dev.foolio.titles.FoolioTitles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Ввод титула через чат. Сообщение игрока перехватывается и отменяется,
 * поэтому никто, кроме него самого, его не видит (в чат оно не уходит).
 */
public final class ChatInput implements Listener {

    private record Session(Consumer<String> onInput, Runnable onCancel, BukkitTask timeout) { }

    private final FoolioTitles plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Игроки, чьё сообщение уже перехвачено в старом событии чата — гасим и новое Paper-событие. */
    private final Set<UUID> captured = ConcurrentHashMap.newKeySet();
    private int timeoutSeconds = 60;
    private List<String> cancelWords = new ArrayList<>(List.of("отмена", "cancel"));

    public ChatInput(FoolioTitles plugin) {
        this.plugin = plugin;
    }

    public void load(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        timeoutSeconds = Math.max(5, section.getInt("timeout-seconds", 60));
        List<String> words = section.getStringList("cancel-words");
        if (!words.isEmpty()) {
            cancelWords = new ArrayList<>();
            for (String w : words) {
                cancelWords.add(w.toLowerCase(Locale.ROOT).strip());
            }
        }
    }

    public boolean isWaiting(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /**
     * Ждать ввод в чат.
     * @param onInput  вызывается в основном потоке с введённым текстом
     * @param onCancel вызывается в основном потоке при отмене или по таймауту
     */
    public void await(Player player, Consumer<String> onInput, Runnable onCancel) {
        UUID uuid = player.getUniqueId();
        stop(uuid);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Session s = sessions.remove(uuid);
            if (s != null) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    plugin.lang().send(p, "input.timeout");
                    plugin.sounds().play(p, "error");
                }
                s.onCancel().run();
            }
        }, timeoutSeconds * 20L);
        sessions.put(uuid, new Session(onInput, onCancel, task));
    }

    private void stop(UUID uuid) {
        Session old = sessions.remove(uuid);
        if (old != null) {
            old.timeout().cancel();
        }
    }

    public void stopAll() {
        for (UUID uuid : sessions.keySet()) {
            stop(uuid);
        }
        captured.clear();
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    private void handle(Player player, String message, Cancellable event) {
        UUID uuid = player.getUniqueId();
        Session session = sessions.remove(uuid);
        if (session == null) {
            if (captured.remove(uuid)) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        captured.add(uuid);
        session.timeout().cancel();
        Bukkit.getScheduler().runTask(plugin, () -> {
            // подстраховка: если второе событие так и не пришло — не трогаем следующие сообщения
            Bukkit.getScheduler().runTaskLater(plugin, () -> captured.remove(uuid), 2L);
            if (!player.isOnline()) {
                return;
            }
            String text = message == null ? "" : message.strip();
            if (cancelWords.contains(text.toLowerCase(Locale.ROOT))) {
                plugin.lang().send(player, "input.cancelled");
                session.onCancel().run();
            } else {
                session.onInput().accept(text);
            }
        });
    }

    // Старое событие (его слушают многие чат-плагины) — гасим как можно раньше.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        handle(event.getPlayer(), event.getMessage(), event);
    }

    // Новое событие Paper.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        handle(event.getPlayer(), PlainTextComponentSerializer.plainText().serialize(event.message()), event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        stop(uuid);
        captured.remove(uuid);
    }
}
