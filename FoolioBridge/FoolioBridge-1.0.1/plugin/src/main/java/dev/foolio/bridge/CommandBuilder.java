// FoolioBridge - Development by DeJIoVoy
package dev.foolio.bridge;

import dev.foolio.bridge.api.Order;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Turns an order into console commands: fills placeholders and applies the command allow-list. */
public final class CommandBuilder {

    private final Pattern namePattern;
    private final Set<String> allowed; // empty = everything allowed

    public CommandBuilder(String namePattern, List<String> allowedCommands) {
        this.namePattern = Pattern.compile(namePattern);
        this.allowed = new HashSet<>();
        for (String c : allowedCommands) {
            String s = c.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("/")) s = s.substring(1);
            if (!s.isEmpty()) allowed.add(s);
        }
    }

    public boolean isValidName(String name) {
        return name != null && namePattern.matcher(name).matches();
    }

    public static final class Result {
        public final List<String> commands = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    /** @param uuid the online player's UUID, or null to fall back to the order's uuid field */
    public Result build(Order order, String playerName, String uuid) {
        Result r = new Result();
        if (!isValidName(playerName)) {
            r.errors.add("invalid player name: " + playerName);
            return r;
        }
        for (String raw : order.commands) {
            String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
            cmd = cmd.replace("{player}", playerName)
                    .replace("{uuid}", uuid != null ? uuid : (order.uuid != null ? order.uuid : ""))
                    .replace("{order}", order.id)
                    .replace("{title}", order.title != null ? clean(order.title) : "");
            for (Map.Entry<String, String> v : order.vars.entrySet()) {
                cmd = cmd.replace("{" + v.getKey() + "}", clean(v.getValue()));
            }
            cmd = clean(cmd).trim();
            if (cmd.isEmpty()) continue;
            String base = cmd.split(" ", 2)[0].toLowerCase(Locale.ROOT);
            if (!allowed.isEmpty() && !allowed.contains(base)) {
                r.errors.add("command not allowed by config: " + base);
                continue;
            }
            r.commands.add(cmd);
        }
        return r;
    }

    /** Removes line breaks and control characters so a value can't smuggle extra input. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            b.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return b.toString();
    }
}
