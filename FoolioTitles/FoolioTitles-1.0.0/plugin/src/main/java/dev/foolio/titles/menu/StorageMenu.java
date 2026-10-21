package dev.foolio.titles.menu;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.TitleService;
import dev.foolio.titles.storage.TitleRecord;
import dev.foolio.titles.storage.TitleStatus;
import dev.foolio.titles.text.Lang;

import java.util.Objects;

/**
 * Хранилище: только титулы, созданные через плагин (одобренные и на проверке).
 * ЛКМ по одобренному — надеть/снять, Shift+ПКМ — удалить; ПКМ по заявке — отозвать.
 */
public final class StorageMenu extends PagedMenu {

    private final TitleService.Snapshot data;
    private final int slotLimit;
    /** Титул, ожидающий подтверждения удаления (второй Shift+ПКМ). */
    private Integer confirmDelete;

    private StorageMenu(FoolioTitles plugin, Player viewer, TitleService.Snapshot data, int page) {
        super(plugin, viewer, "storage", page);
        this.data = data;
        this.slotLimit = plugin.service().slotLimit(viewer);
    }

    public static void open(FoolioTitles plugin, Player player, int page) {
        plugin.service().loadStorage(player.getUniqueId(),
                snapshot -> new StorageMenu(plugin, player, snapshot, page).open(), player);
    }

    @Override
    protected int total() {
        return data.titles().size();
    }

    private void reopen() {
        open(plugin, viewer, currentPage());
    }

    @Override
    protected void render() {
        TagResolver common = TagResolver.resolver(
                Lang.text("player", viewer.getName()),
                Lang.num("tokens", data.player().tokens()),
                Lang.num("slots_used", data.titles().size()),
                Lang.num("slots_max", slotLimit),
                Lang.num("page", currentPage() + 1),
                Lang.num("pages", pages()));

        if (data.titles().isEmpty()) {
            place("empty", common, null);
        }

        fillPage((index, slot) -> {
            TitleRecord t = data.titles().get(index);
            TagResolver r = TagResolver.resolver(common,
                    Lang.comp("title", plugin.formatter().render(t.raw())),
                    Lang.num("id", t.id()),
                    Lang.text("date", plugin.lang().date(t.createdAt())));
            if (t.status() == TitleStatus.PENDING) {
                MenuConfig.ItemTemplate tpl = cfg().item(id, "pending-title");
                set(slot, tpl.build(r), type -> {
                    if ((type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) && lock()) {
                        plugin.service().cancel(viewer, t.id(), ifStillHere(this::reopen));
                    }
                });
                return;
            }
            boolean active = Objects.equals(data.player().activeTitle(), t.id());
            boolean confirming = Objects.equals(confirmDelete, t.id());
            MenuConfig.ItemTemplate tpl = cfg().item(id, confirming ? "confirm-delete"
                    : active ? "active-title" : "title");
            set(slot, tpl.build(r), type -> {
                if (type == ClickType.SHIFT_RIGHT) {
                    if (!confirming) {
                        confirmDelete = t.id();
                        click();
                        rerender();
                    } else if (lock()) {
                        plugin.service().deleteOwn(viewer, t.id(), ifStillHere(this::reopen));
                    }
                } else if (type == ClickType.LEFT || type == ClickType.SHIFT_LEFT) {
                    if (!lock()) {
                        return;
                    }
                    if (active) {
                        plugin.service().unequip(viewer, ifStillHere(this::reopen));
                    } else {
                        plugin.service().equip(viewer, t.id(), ifStillHere(this::reopen));
                    }
                }
            });
        });

        place("info", common, null);
        pageButtons(common, p -> open(plugin, viewer, p));
        place("create", common, type -> {
            click();
            CreateMenu.open(plugin, viewer, null);
        });
        place("back", common, type -> {
            click();
            MainMenu.open(plugin, viewer);
        });
    }
}
