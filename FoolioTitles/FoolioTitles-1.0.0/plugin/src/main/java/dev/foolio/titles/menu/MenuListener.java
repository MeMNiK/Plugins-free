package dev.foolio.titles.menu;

import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MenuListener implements Listener {

    /** Минимальный интервал между кликами одного игрока (защита от автокликеров/спама). */
    private static final long CLICK_COOLDOWN_MS = 150;

    private final FoolioTitles plugin;
    private final Map<UUID, Menu> open = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public MenuListener(FoolioTitles plugin) {
        this.plugin = plugin;
    }

    void track(UUID uuid, Menu menu) {
        open.put(uuid, menu);
    }

    /** Меню плагина, открытое у игрока сейчас (null — никакое). */
    Menu current(UUID uuid) {
        return open.get(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickLowest(InventoryClickEvent event) {
        // отменяем как можно раньше, чтобы ни один плагин не успел «вытащить» предмет из меню
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }
        // в наших меню ничего нельзя брать/класть, включая шифт-клики из своего инвентаря
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType type = event.getClick();
        // реагируем только на обычные клики мышью: Q, цифры, колёсико, двойной клик — игнорируются
        if (type != ClickType.LEFT && type != ClickType.RIGHT
                && type != ClickType.SHIFT_LEFT && type != ClickType.SHIFT_RIGHT) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.put(player.getUniqueId(), now);
        if (last != null && now - last < CLICK_COOLDOWN_MS) {
            return;
        }
        // действие выполняем в следующем тике: открывать/закрывать инвентари внутри события клика нельзя
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (open.get(player.getUniqueId()) == menu) {
                menu.handleClick(raw, type);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            open.remove(event.getPlayer().getUniqueId(), menu);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        open.remove(event.getPlayer().getUniqueId());
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    /** Закрыть все открытые меню плагина (при выключении/перезагрузке). */
    public void closeAll() {
        for (UUID uuid : new ArrayList<>(open.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.closeInventory();
            }
        }
        open.clear();
    }

    /** Обновить у админов открытые меню проверки прямо на месте (после новых заявок или чужих решений). */
    public void refreshReviewMenus() {
        if (open.values().stream().noneMatch(m -> m instanceof ReviewMenu)) {
            return;
        }
        plugin.service().loadQueue(queue -> {
            List<Database.QueueEntry> copy = List.copyOf(queue);
            for (Menu menu : open.values()) {
                if (menu instanceof ReviewMenu review) {
                    review.update(copy);
                }
            }
        }, null);
    }
}
