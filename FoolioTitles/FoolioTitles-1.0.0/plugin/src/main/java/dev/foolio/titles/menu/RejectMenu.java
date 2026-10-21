// FoolioTitles - Development by DeJIoVoy
package dev.foolio.titles.menu;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.storage.TitleRecord;
import dev.foolio.titles.text.Lang;

import java.util.List;

/** Выбор причины отказа (список из config.yml) или ввод своей причины в чат. */
public final class RejectMenu extends Menu {

    private final TitleRecord title;
    private final boolean refund;
    private final int returnPage;

    RejectMenu(FoolioTitles plugin, Player viewer, TitleRecord title, boolean refund, int returnPage) {
        super(plugin, viewer, "reject");
        this.title = title;
        this.refund = refund;
        this.returnPage = returnPage;
    }

    @Override
    protected TagResolver titleResolver() {
        return Lang.text("player", title.ownerName());
    }

    @Override
    protected void render() {
        TagResolver common = TagResolver.resolver(
                Lang.comp("title", plugin.formatter().render(title.raw())),
                Lang.text("player", title.ownerName()),
                Lang.comp("refund", plugin.lang().component(refund ? "menu.refund-yes" : "menu.refund-no")));

        List<String> reasons = plugin.getConfig().getStringList("rejections.reasons");
        List<Integer> slots = contentSlots();
        MenuConfig.ItemTemplate tpl = cfg().item(id, "reason");
        for (int i = 0; i < reasons.size() && i < slots.size(); i++) {
            String reason = reasons.get(i);
            set(slots.get(i), tpl.build(TagResolver.resolver(common, Lang.text("reason", reason))),
                    type -> reject(reason));
        }

        place("info", common, null);
        place("custom", common, type -> {
            click();
            close();
            plugin.lang().send(viewer, "input.reason-prompt",
                    Lang.num("seconds", plugin.chatInput().timeoutSeconds()));
            plugin.chatInput().await(viewer, text -> {
                if (text.isBlank()) {
                    open();
                    return;
                }
                reject(text.length() > 200 ? text.substring(0, 200) : text);
            }, this::open);
        });
        place("back", common, type -> {
            click();
            ReviewMenu.open(plugin, viewer, returnPage);
        });
    }

    private void reject(String reason) {
        if (!lock()) {
            return;
        }
        plugin.service().reject(viewer, title.id(), reason, ifStillHere(() -> ReviewMenu.open(plugin, viewer, returnPage)));
    }
}
