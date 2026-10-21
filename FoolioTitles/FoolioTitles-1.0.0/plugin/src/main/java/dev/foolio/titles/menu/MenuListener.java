package dev.foolio.titles.menu;

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
import dev.foolio.titles.FoolioTitles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MenuListener implements Listener {

    private final FoolioTitles plugin;
    private final Map<UUID, Menu> open = new ConcurrentHashMap<>();

    public MenuListener(FoolioTitles plugin) {
        this.plugin = plugin;
    }

    void track(UUID uuid, Menu menu) {
        open.put(uuid, menu);
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
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        ClickType type = event.getClick();
        if (type == ClickType.DOUBLE_CLICK) {
            return; // двойной клик приходит вместе с одиночным — не дублируем действие
        }
        // действие выполняем в следующем тике: открывать/закрывать инвентари внутри события клика нельзя
        Bukkit.getScheduler().runTask(plugin, () -> menu.handleClick(raw, type));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
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
    }

    /** Закрыть все открытые меню плагина (при выключении/перезагрузке). */
    public void closeAll() {
        for (UUID uuid : open.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.closeInventory();
            }
        }
        open.clear();
    }

    /** Обновить у всех админов открытые меню проверки (после чужого решения). */
    public void refreshReviewMenus() {
        for (Menu menu : open.values()) {
            if (menu instanceof ReviewMenu) {
                ReviewMenu.open(plugin, menu.viewer, ((ReviewMenu) menu).page());
            }
        }
    }
}
