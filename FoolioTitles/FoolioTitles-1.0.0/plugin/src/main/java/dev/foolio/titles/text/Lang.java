// FoolioTitles - Development by DeJIoVoy
package dev.foolio.titles.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Сообщения плагина из config.yml (раздел messages), формат MiniMessage. */
public final class Lang {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<String, String> messages = new HashMap<>();
    private String prefix = "";
    private String dateFormat = "dd.MM.yyyy HH:mm";

    public void load(ConfigurationSection section, String dateFormat) {
        messages.clear();
        if (dateFormat != null && !dateFormat.isEmpty()) {
            this.dateFormat = dateFormat;
        }
        if (section == null) {
            return;
        }
        prefix = section.getString("prefix", "");
        for (String key : section.getKeys(true)) {
            if (section.isList(key)) {
                messages.put(key, String.join("<newline>", section.getStringList(key)));
            } else if (section.isString(key)) {
                messages.put(key, section.getString(key));
            }
        }
    }

    public String raw(String key) {
        return messages.getOrDefault(key, "<red>[FoolioTitles] нет сообщения: " + key + "</red>");
    }

    public boolean has(String key) {
        String v = messages.get(key);
        return v != null && !v.isEmpty();
    }

    public Component component(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    public Component parse(String text, TagResolver... resolvers) {
        TagResolver all = TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", prefix))
                .resolvers(resolvers)
                .build();
        return mm.deserialize(text, all);
    }

    /** Отправить сообщение по ключу. Пустое сообщение в конфиге = не отправлять. */
    public void send(CommandSender to, String key, TagResolver... resolvers) {
        if (to == null) {
            return;
        }
        String text = messages.get(key);
        if (text != null && text.isEmpty()) {
            return;
        }
        to.sendMessage(component(key, resolvers));
    }

    public String date(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new SimpleDateFormat(dateFormat).format(new Date(millis));
    }

    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public static TagResolver num(String key, long value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver all(List<TagResolver> list) {
        return TagResolver.resolver(list);
    }
}
