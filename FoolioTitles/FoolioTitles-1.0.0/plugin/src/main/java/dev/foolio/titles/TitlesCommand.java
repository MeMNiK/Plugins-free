package dev.foolio.titles;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import dev.foolio.titles.menu.MainMenu;
import dev.foolio.titles.menu.PlayerTitlesMenu;
import dev.foolio.titles.menu.ReviewMenu;
import dev.foolio.titles.text.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /titles                                — меню титулов
 * /titles admin                          — очередь проверки
 * /titles admin player <ник>             — титулы игрока (снять за нарушение)
 * /titles token give|take|set <ник> <n>  — токены (give подходит для сайта/FoolioBridge)
 * /titles token check <ник>
 * /titles strikes check|reset <ник>
 * /titles reload
 */
public final class TitlesCommand implements TabExecutor {

    /** Верхняя граница для одной операции с токенами (защита от опечаток и переполнения). */
    private static final int MAX_TOKENS = 1_000_000;

    private final FoolioTitles plugin;

    public TitlesCommand(FoolioTitles plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission(TitleService.PERM_ADMIN)) {
            return true;
        }
        lang().send(sender, "error.no-permission");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                lang().send(sender, sender.hasPermission(TitleService.PERM_ADMIN) ? "help.admin" : "error.players-only");
                return true;
            }
            if (!player.hasPermission(TitleService.PERM_USE)) {
                lang().send(player, "error.no-permission");
                return true;
            }
            MainMenu.open(plugin, player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> lang().send(sender, sender.hasPermission(TitleService.PERM_ADMIN) ? "help.admin" : "help.player");
            case "admin" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    lang().send(sender, "error.players-only");
                    return true;
                }
                if (args.length >= 3 && args[1].equalsIgnoreCase("player")) {
                    plugin.service().resolve(args[2], target -> {
                        if (target == null) {
                            lang().send(player, "error.player-not-found", Lang.text("player", args[2]));
                        } else {
                            PlayerTitlesMenu.open(plugin, player, target, 0);
                        }
                    }, player);
                } else {
                    ReviewMenu.open(plugin, player, 0);
                }
            }
            case "token", "tokens" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (args.length < 3) {
                    lang().send(sender, "help.admin");
                    return true;
                }
                String action = args[1].toLowerCase(Locale.ROOT);
                if (!List.of("give", "take", "set", "check").contains(action)) {
                    lang().send(sender, "help.admin");
                    return true;
                }
                int amount = 0;
                if (!action.equals("check")) {
                    if (args.length < 4) {
                        lang().send(sender, "help.admin");
                        return true;
                    }
                    try {
                        amount = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        amount = -1;
                    }
                    if (amount < 0 || amount > MAX_TOKENS || (amount == 0 && !action.equals("set"))) {
                        lang().send(sender, "error.bad-number", Lang.text("value", args[3]));
                        return true;
                    }
                }
                plugin.service().tokens(sender, action, args[2], amount);
            }
            case "strikes" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (args.length < 3 || !List.of("check", "reset").contains(args[1].toLowerCase(Locale.ROOT))) {
                    lang().send(sender, "help.admin");
                    return true;
                }
                plugin.service().strikes(sender, args[1].toLowerCase(Locale.ROOT), args[2]);
            }
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }
                plugin.reloadAll();
                lang().send(sender, "admin.reloaded");
            }
            default -> lang().send(sender, sender.hasPermission(TitleService.PERM_ADMIN) ? "help.admin" : "help.player");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission(TitleService.PERM_ADMIN);
        if (args.length == 1) {
            options.add("help");
            if (admin) {
                options.addAll(List.of("admin", "token", "strikes", "reload"));
            }
        } else if (admin && args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "admin" -> options.add("player");
                case "token", "tokens" -> options.addAll(List.of("give", "take", "set", "check"));
                case "strikes" -> options.addAll(List.of("check", "reset"));
                default -> { }
            }
        } else if (admin && args.length == 3) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("token") || first.equals("tokens") || first.equals("strikes")
                    || (first.equals("admin") && args[1].equalsIgnoreCase("player"))) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    options.add(p.getName());
                }
            }
        } else if (admin && args.length == 4 && (args[0].equalsIgnoreCase("token") || args[0].equalsIgnoreCase("tokens"))
                && !args[1].equalsIgnoreCase("check")) {
            options.addAll(List.of("1", "5", "10"));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}
