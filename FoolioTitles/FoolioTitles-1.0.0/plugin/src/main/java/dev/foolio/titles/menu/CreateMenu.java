package dev.foolio.titles.menu;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import dev.foolio.titles.FoolioTitles;
import dev.foolio.titles.storage.PlayerData;
import dev.foolio.titles.text.Lang;
import dev.foolio.titles.text.TitleFormatter;

/**
 * Создание титула: игрок вводит текст в чат (его никто не видит), затем
 * меню открывается снова с превью и кнопкой отправки на проверку.
 */
public final class CreateMenu extends Menu {

    private final PlayerData data;
    private final String draft;

    private CreateMenu(FoolioTitles plugin, Player viewer, PlayerData data, String draft) {
        super(plugin, viewer, "create");
        this.data = data;
        this.draft = draft;
    }

    /** @param draft уже введённый титул (MiniMessage) или null */
    public static void open(FoolioTitles plugin, Player player, String draft) {
        plugin.service().loadPlayer(player.getUniqueId(),
                data -> new CreateMenu(plugin, player, data, draft).open(), player);
    }

    @Override
    protected void render() {
        TitleFormatter f = plugin.formatter();
        TagResolver.Builder b = TagResolver.builder().resolvers(
                Lang.text("player", viewer.getName()),
                Lang.num("tokens", data.tokens()),
                Lang.num("min", f.minLength()),
                Lang.num("max", f.maxLength()));
        if (draft != null) {
            String plain = f.plainText(draft);
            b.resolvers(
                    Lang.comp("title", f.render(draft)),
                    Lang.comp("suffix", f.suffixComponent(draft)),
                    Lang.num("length", plain.codePointCount(0, plain.length())));
        }
        TagResolver r = b.build();

        place("info", r, null);
        if (draft == null) {
            place("input", r, type -> startInput());
        } else {
            place("preview", r, type -> startInput());
            place("submit", r, type -> {
                if (data.tokens() < 1) {
                    plugin.lang().send(viewer, "player.no-tokens");
                    plugin.sounds().play(viewer, "error");
                    return;
                }
                long wait = plugin.service().submitCooldownLeft(viewer);
                if (wait > 0) {
                    plugin.lang().send(viewer, "player.cooldown", Lang.num("seconds", wait));
                    plugin.sounds().play(viewer, "error");
                    return;
                }
                if (!lock()) {
                    return;
                }
                close();
                plugin.service().submit(viewer, draft, () -> StorageMenu.open(plugin, viewer, 0));
            });
            place("retry", r, type -> startInput());
        }
        place("back", r, type -> {
            click();
            MainMenu.open(plugin, viewer);
        });
    }

    private void startInput() {
        if (data.tokens() < 1) {
            plugin.lang().send(viewer, "player.no-tokens");
            plugin.sounds().play(viewer, "error");
            return;
        }
        click();
        close();
        TitleFormatter f = plugin.formatter();
        plugin.lang().send(viewer, "input.prompt",
                Lang.num("min", f.minLength()),
                Lang.num("max", f.maxLength()),
                Lang.num("seconds", plugin.chatInput().timeoutSeconds()));
        plugin.chatInput().await(viewer, text -> {
            TitleFormatter.Result result = plugin.formatter().validate(text);
            if (!result.ok()) {
                plugin.lang().send(viewer, "input.error." + result.errorKey(),
                        Lang.num("min", f.minLength()), Lang.num("max", f.maxLength()));
                plugin.sounds().play(viewer, "error");
                open(plugin, viewer, draft);
                return;
            }
            plugin.sounds().play(viewer, "click");
            open(plugin, viewer, result.raw());
        }, () -> open(plugin, viewer, draft));
    }
}
