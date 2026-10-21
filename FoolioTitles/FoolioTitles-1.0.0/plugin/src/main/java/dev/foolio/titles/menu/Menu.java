package dev.foolio.titles.menu;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import dev.foolio.titles.FoolioTitles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Базовое меню. Каждое меню — свой InventoryHolder, поэтому наши инвентари легко отличить от чужих. */
public abstract class Menu implements InventoryHolder {

    @FunctionalInterface
    public interface ClickAction {
        void click(ClickType type);
    }

    protected final FoolioTitles plugin;
    protected final Player viewer;
    protected final String id;
    private final Map<Integer, ClickAction> actions = new HashMap<>();
    private Inventory inventory;

    protected Menu(FoolioTitles plugin, Player viewer, String id) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.id = id;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected MenuConfig cfg() {
        return plugin.menus();
    }

    /** Заполнить инвентарь. Вызывается при открытии и при обновлении. */
    protected abstract void render();

    /** Плейсхолдеры для заголовка меню. */
    protected TagResolver titleResolver() {
        return TagResolver.empty();
    }

    public void open() {
        if (!viewer.isOnline()) {
            return;
        }
        int size = cfg().size(id);
        inventory = Bukkit.createInventory(this, size,
                MiniMessage.miniMessage().deserialize(cfg().title(id), titleResolver()));
        actions.clear();
        MenuConfig.ItemTemplate filler = cfg().filler(id);
        if (filler != null) {
            ItemStack fill = filler.build(TagResolver.empty());
            for (int i = 0; i < size; i++) {
                inventory.setItem(i, fill);
            }
        }
        render();
        plugin.menuListener().track(viewer.getUniqueId(), this);
        viewer.openInventory(inventory);
    }

    protected void set(int slot, ItemStack item, ClickAction action) {
        if (slot < 0 || inventory == null || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    /** Поставить предмет из menus.yml в его слот. */
    protected void place(String key, TagResolver resolver, ClickAction action) {
        MenuConfig.ItemTemplate t = cfg().item(id, key);
        set(t.slot(), t.build(resolver), action);
    }

    protected void place(String key, TagResolver resolver, Material override, UUID head, ClickAction action) {
        MenuConfig.ItemTemplate t = cfg().item(id, key);
        set(t.slot(), t.build(resolver, override, head), action);
    }

    protected List<Integer> contentSlots() {
        return cfg().contentSlots(id, inventory.getSize());
    }

    void handleClick(int slot, ClickType type) {
        ClickAction action = actions.get(slot);
        if (action != null) {
            action.click(type);
        }
    }

    protected void click() {
        plugin.sounds().play(viewer, "click");
    }

    protected void close() {
        viewer.closeInventory();
    }
}
