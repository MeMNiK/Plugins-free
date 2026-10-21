package dev.foolio.titles.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Превращает ввод игрока в титул: поддерживает &-коды, &#RRGGBB и MiniMessage
 * (цвета, HEX, градиенты, радуга, оформление). Всё остальное (клики, ховеры,
 * шрифты и т.п.) запрещено — такие теги просто не обрабатываются и ловятся проверкой.
 */
public final class TitleFormatter {

    /** Результат проверки: либо нормализованный титул (MiniMessage), либо ключ ошибки. */
    public record Result(String raw, String errorKey) {
        public boolean ok() {
            return errorKey == null;
        }
    }

    public enum SuffixFormat { LEGACY, LEGACY_HASH, MINIMESSAGE }

    private static final String[] COLOR_TAGS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };
    private static final Pattern OBFUSCATED = Pattern.compile("(?i)<\\s*(obf|obfuscated)\\s*>");
    private static final Map<Character, Character> LOOKALIKES = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('e', 'е'), Map.entry('o', 'о'), Map.entry('p', 'р'),
            Map.entry('c', 'с'), Map.entry('y', 'у'), Map.entry('x', 'х'), Map.entry('k', 'к'),
            Map.entry('m', 'м'), Map.entry('t', 'т'), Map.entry('h', 'н'), Map.entry('b', 'в'),
            Map.entry('0', 'о'), Map.entry('3', 'з'), Map.entry('6', 'б'), Map.entry('@', 'а'),
            Map.entry('ё', 'е'));

    private final MiniMessage restricted = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolvers(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
                            StandardTags.rainbow(), StandardTags.reset())
                    .build())
            .build();
    private final MiniMessage full = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    private final LegacyComponentSerializer legacyX = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private final LegacyComponentSerializer legacyHash = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private int minLength = 1;
    private int maxLength = 16;
    private int maxRawLength = 256;
    private boolean allowObfuscated = false;
    private Pattern allowedChars;
    private List<String> forbiddenWords = new ArrayList<>();
    private String suffixTemplate = " <title>";
    private SuffixFormat suffixFormat = SuffixFormat.LEGACY;
    private boolean appendReset = true;

    public void load(ConfigurationSection title, ConfigurationSection lp, java.util.logging.Logger logger) {
        if (title != null) {
            minLength = Math.max(1, title.getInt("min-length", 1));
            maxLength = Math.max(minLength, title.getInt("max-length", 16));
            maxRawLength = Math.max(maxLength, title.getInt("max-raw-length", 256));
            allowObfuscated = title.getBoolean("allow-obfuscated", false);
            String regex = title.getString("allowed-chars", "");
            allowedChars = null;
            if (regex != null && !regex.isEmpty()) {
                try {
                    allowedChars = Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    logger.warning("title.allowed-chars: неверное регулярное выражение, проверка символов отключена: "
                            + e.getMessage());
                }
            }
            forbiddenWords = new ArrayList<>();
            for (String w : title.getStringList("forbidden-words")) {
                String n = normalizeWord(w);
                if (!n.isEmpty()) {
                    forbiddenWords.add(n);
                }
            }
        }
        if (lp != null) {
            suffixTemplate = lp.getString("suffix-template", " <title>");
            appendReset = lp.getBoolean("append-reset", true);
            try {
                suffixFormat = SuffixFormat.valueOf(lp.getString("suffix-format", "LEGACY").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warning("luckperms.suffix-format: неизвестный формат, используется LEGACY");
                suffixFormat = SuffixFormat.LEGACY;
            }
        }
    }

    public int maxLength() {
        return maxLength;
    }

    public int minLength() {
        return minLength;
    }

    // ------------------------------------------------------------------ ввод игрока

    /** Переводит &-коды в теги MiniMessage, вырезает §-коды. */
    static String legacyToMini(String in) {
        StringBuilder out = new StringBuilder(in.length() + 16);
        int len = in.length();
        int i = 0;
        while (i < len) {
            char c = in.charAt(i);
            if (c == '§') {
                i += 2; // мусорный §-код
                continue;
            }
            if (c == '&' && i + 1 < len) {
                char n = Character.toLowerCase(in.charAt(i + 1));
                // &#RRGGBB
                if (n == '#' && i + 8 <= len && isHex(in, i + 2, 6)) {
                    out.append("<reset><#").append(in, i + 2, i + 8).append('>');
                    i += 8;
                    continue;
                }
                // &x&R&R&G&G&B&B
                if (n == 'x' && i + 14 <= len) {
                    StringBuilder hex = new StringBuilder(6);
                    boolean okX = true;
                    for (int k = 0; k < 6; k++) {
                        int p = i + 2 + k * 2;
                        if (in.charAt(p) != '&' || Character.digit(in.charAt(p + 1), 16) < 0) {
                            okX = false;
                            break;
                        }
                        hex.append(in.charAt(p + 1));
                    }
                    if (okX) {
                        out.append("<reset><#").append(hex).append('>');
                        i += 14;
                        continue;
                    }
                }
                int color = Character.digit(n, 16);
                if (color >= 0) {
                    out.append("<reset><").append(COLOR_TAGS[color]).append('>');
                    i += 2;
                    continue;
                }
                String deco = switch (n) {
                    case 'l' -> "<bold>";
                    case 'o' -> "<italic>";
                    case 'n' -> "<underlined>";
                    case 'm' -> "<strikethrough>";
                    case 'k' -> "<obfuscated>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (deco != null) {
                    out.append(deco);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int count) {
        for (int k = from; k < from + count; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    static String normalizeWord(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            Character mapped = LOOKALIKES.get(c);
            if (mapped != null) {
                c = mapped;
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Проверяет ввод игрока и возвращает нормализованный титул. */
    public Result validate(String input) {
        String trimmed = input == null ? "" : input.strip();
        if (trimmed.isEmpty()) {
            return new Result(null, "empty");
        }
        String raw = legacyToMini(trimmed);
        if (raw.length() > maxRawLength) {
            return new Result(null, "raw-too-long");
        }
        if (!allowObfuscated && OBFUSCATED.matcher(raw).find()) {
            return new Result(null, "obfuscated");
        }
        String text;
        try {
            text = plain.serialize(restricted.deserialize(raw));
        } catch (RuntimeException e) {
            return new Result(null, "unknown-tags");
        }
        if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0 || text.indexOf('§') >= 0) {
            return new Result(null, "unknown-tags");
        }
        if (text.isBlank()) {
            return new Result(null, "empty");
        }
        int visible = text.codePointCount(0, text.length());
        if (visible < minLength) {
            return new Result(null, "too-short");
        }
        if (visible > maxLength) {
            return new Result(null, "too-long");
        }
        if (allowedChars != null && !allowedChars.matcher(text).matches()) {
            return new Result(null, "bad-chars");
        }
        String normalized = normalizeWord(text);
        String simple = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        for (String word : forbiddenWords) {
            if (normalized.contains(word) || simple.contains(word)) {
                return new Result(null, "forbidden-word");
            }
        }
        return new Result(raw, null);
    }

    // ------------------------------------------------------------------ отображение

    /** Титул как компонент (только безопасные теги). */
    public Component render(String raw) {
        try {
            return restricted.deserialize(raw);
        } catch (RuntimeException e) {
            return Component.text(raw);
        }
    }

    public String plainText(String raw) {
        return plain.serialize(render(raw));
    }

    /** Как суффикс будет выглядеть (для превью в меню). */
    public Component suffixComponent(String raw) {
        return full.deserialize(suffixTemplate, Placeholder.component("title", render(raw)));
    }

    /** Готовая строка суффикса для LuckPerms. */
    public String toSuffix(String raw) {
        TagResolver resolver = Placeholder.component("title", render(raw));
        if (suffixFormat == SuffixFormat.MINIMESSAGE) {
            // оставляем MiniMessage как есть, подставляя «сырой» титул
            String out = suffixTemplate.replace("<title>", raw);
            return appendReset ? out + "<reset>" : out;
        }
        Component component = full.deserialize(suffixTemplate, resolver);
        String out = (suffixFormat == SuffixFormat.LEGACY_HASH ? legacyHash : legacyX).serialize(component);
        return appendReset ? out + "&r" : out;
    }

    /** Экранирует произвольный текст для безопасной вставки в MiniMessage. */
    public String escape(String text) {
        return full.escapeTags(text);
    }
}
