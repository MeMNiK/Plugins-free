package dev.foolio.titles.menu;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.TitleService;
import dev.foolio.titles.storage.TitleRecord;
import dev.foolio.titles.text.Lang;

import java.util.Objects;

/** Админ-меню: одобренные титулы конкретного игрока, Shift+ПКМ — снять титул за нарушение. */
public final class PlayerTitlesMenu extends PagedMenu {

    private final TitleService.Target target;
    private final TitleService.Snapshot data;

    private PlayerTitlesMenu(FoolioTitles plugin, Player viewer, TitleService.Target target,
                             TitleService.Snapshot data, int page) {
        super(plugin, viewer, "player-titles", page);
        this.target = target;
        this.data = data;
    }

    public static void open(FoolioTitles plugin, Player admin, TitleService.Target target, int page) {
        plugin.service().loadApproved(target.uuid(),
                snapshot -> new PlayerTitlesMenu(plugin, admin, target, snapshot, page).open(), admin);
    }

    @Override
    protected int total() {
        return data.titles().size();
    }

    @Override
    protected TagResolver titleResolver() {
        return TagResolver.resolver(super.titleResolver(), Lang.text("player", target.name()));
    }

    @Override
    protected void render() {
        TagResolver common = TagResolver.resolver(
                Lang.text("player", target.name()),
                Lang.num("tokens", data.player().tokens()),
                Lang.num("strikes", data.player().strikes()),
                Lang.num("count", data.titles().size()));

        if (data.titles().isEmpty()) {
            place("empty", common, null);
        }

        String reason = plugin.getConfig().getString("rejections.revoke-reason", "Нарушение правил");
        fillPage((index, slot) -> {
            TitleRecord t = data.titles().get(index);
            boolean active = Objects.equals(data.player().activeTitle(), t.id());
            TagResolver r = TagResolver.resolver(common,
                    Lang.comp("title", plugin.formatter().render(t.raw())),
                    Lang.text("raw", t.raw()),
                    Lang.num("id", t.id()),
                    Lang.text("admin", t.reviewedBy() == null ? "-" : t.reviewedBy()),
                    Lang.text("date", plugin.lang().date(t.createdAt())));
            MenuConfig.ItemTemplate tpl = cfg().item(id, active ? "active-title" : "title");
            set(slot, tpl.build(r), type -> {
                if (type == ClickType.SHIFT_RIGHT) {
                    plugin.service().revoke(viewer, t.id(), reason,
                            () -> open(plugin, viewer, target, currentPage()));
                }
            });
        });

        place("info", common, null, target.uuid(), null);
        pageButtons(common, p -> open(plugin, viewer, target, p));
        place("back", common, type -> {
            click();
            ReviewMenu.open(plugin, viewer, 0);
        });
    }
}
