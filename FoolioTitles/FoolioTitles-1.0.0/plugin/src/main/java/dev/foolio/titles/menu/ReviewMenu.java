package dev.foolio.titles.menu;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.TitleService;
import dev.foolio.titles.storage.Database;
import dev.foolio.titles.storage.TitleRecord;
import dev.foolio.titles.text.Lang;

import java.util.List;

/** Админ-меню: очередь титулов на проверку. ЛКМ — одобрить, ПКМ — выбрать причину отказа. */
public final class ReviewMenu extends PagedMenu {

    private final List<Database.QueueEntry> queue;

    private ReviewMenu(FoolioTitles plugin, Player viewer, List<Database.QueueEntry> queue, int page) {
        super(plugin, viewer, "review", page);
        this.queue = queue;
    }

    public static void open(FoolioTitles plugin, Player player, int page) {
        if (!player.hasPermission(TitleService.PERM_ADMIN)) {
            plugin.lang().send(player, "error.no-permission");
            return;
        }
        plugin.service().loadQueue(queue -> new ReviewMenu(plugin, player, queue, page).open(), player);
    }

    @Override
    protected int total() {
        return queue.size();
    }

    @Override
    protected void render() {
        int free = plugin.service().freeRejections();
        TagResolver common = TagResolver.resolver(
                Lang.num("count", queue.size()),
                Lang.num("page", currentPage() + 1),
                Lang.num("pages", pages()));

        if (queue.isEmpty()) {
            place("empty", common, null);
        }

        fillPage((index, slot) -> {
            Database.QueueEntry entry = queue.get(index);
            TitleRecord t = entry.title();
            boolean refund = entry.strikes() < free;
            TagResolver r = TagResolver.resolver(common,
                    Lang.comp("title", plugin.formatter().render(t.raw())),
                    Lang.comp("suffix", plugin.formatter().suffixComponent(t.raw())),
                    Lang.text("raw", t.raw()),
                    Lang.text("player", t.ownerName()),
                    Lang.num("id", t.id()),
                    Lang.num("strikes", entry.strikes()),
                    Lang.comp("refund", plugin.lang().component(refund ? "menu.refund-yes" : "menu.refund-no")),
                    Lang.text("date", plugin.lang().date(t.createdAt())));
            MenuConfig.ItemTemplate tpl = cfg().item(id, "request");
            set(slot, tpl.build(r, null, t.owner()), type -> {
                if (type == ClickType.LEFT || type == ClickType.SHIFT_LEFT) {
                    plugin.service().approve(viewer, t.id(), () -> { });
                } else if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT) {
                    click();
                    new RejectMenu(plugin, viewer, t, refund, currentPage()).open();
                }
            });
        });

        place("info", common, null);
        pageButtons(common, p -> open(plugin, viewer, p));
        place("refresh", common, type -> {
            click();
            open(plugin, viewer, currentPage());
        });
        place("back", common, type -> {
            click();
            MainMenu.open(plugin, viewer);
        });
    }
}
