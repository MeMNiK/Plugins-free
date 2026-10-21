package dev.foolio.titles.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.TitleService;
import dev.foolio.titles.storage.TitleRecord;
import dev.foolio.titles.text.Lang;

/** Главное меню /titles. */
public final class MainMenu extends Menu {

    private final TitleService.Snapshot data;
    private final int slotLimit;

    private MainMenu(FoolioTitles plugin, Player viewer, TitleService.Snapshot data) {
        super(plugin, viewer, "main");
        this.data = data;
        this.slotLimit = plugin.service().slotLimit(viewer);
    }

    public static void open(FoolioTitles plugin, Player player) {
        plugin.service().loadStorage(player.getUniqueId(),
                snapshot -> new MainMenu(plugin, player, snapshot).open(), player);
    }

    private TitleRecord active() {
        Integer id = data.player().activeTitle();
        if (id == null) {
            return null;
        }
        for (TitleRecord t : data.titles()) {
            if (t.id() == id) {
                return t;
            }
        }
        return null;
    }

    @Override
    protected void render() {
        TitleRecord active = active();
        Component activeComp = active != null
                ? plugin.formatter().render(active.raw())
                : plugin.lang().component("menu.no-active-title");
        TagResolver r = TagResolver.resolver(
                Lang.text("player", viewer.getName()),
                Lang.num("tokens", data.player().tokens()),
                Lang.num("slots_used", data.titles().size()),
                Lang.num("slots_max", slotLimit),
                Lang.comp("active", activeComp));

        place("info", r, null);
        place("create", r, type -> {
            click();
            CreateMenu.open(plugin, viewer, null);
        });
        place("storage", r, type -> {
            click();
            StorageMenu.open(plugin, viewer, 0);
        });
        if (active != null) {
            place("unequip", r, type -> plugin.service().unequip(viewer, () -> open(plugin, viewer)));
        }
        if (viewer.hasPermission(TitleService.PERM_ADMIN)) {
            place("admin", r, type -> {
                click();
                ReviewMenu.open(plugin, viewer, 0);
            });
        }
        place("close", r, type -> close());
    }
}
