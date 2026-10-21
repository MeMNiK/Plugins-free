package dev.foolio.titles.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/** Настройки всех меню из menus.yml. */
public final class MenuConfig {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Показывать скины игроков на головах в меню (на пиратских серверах лучше выключить). */
    static boolean playerHeads = true;

    private YamlConfiguration yaml = new YamlConfiguration();
    private Logger logger;

    public void load(YamlConfiguration yaml, Logger logger, boolean heads) {
        playerHeads = heads;
        this.yaml = yaml;
        this.logger = logger;
    }

    public ConfigurationSection section(String menu) {
        ConfigurationSection s = yaml.getConfigurationSection(menu);
        return s != null ? s : new YamlConfiguration();
    }

    public String title(String menu) {
        return section(menu).getString("title", menu);
    }

    public int size(String menu) {
        int size = section(menu).getInt("size", 27);
        size = Math.max(9, Math.min(54, size));
        return size - size % 9;
    }

    /** Слоты для списков (титулы, заявки, причины). Формат: "10-16, 19-25, 28". */
    public List<Integer> contentSlots(String menu, int size) {
        String spec = section(menu).getString("content-slots", "");
        List<Integer> slots = parseSlots(spec, size);
        if (slots.isEmpty()) {
            for (int i = 0; i < size - 9; i++) {
                slots.add(i);
            }
        }
        return slots;
    }

    static List<Integer> parseSlots(String spec, int size) {
        List<Integer> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                int dash = p.indexOf('-');
                int from;
                int to;
                if (dash > 0) {
                    from = Integer.parseInt(p.substring(0, dash).trim());
                    to = Integer.parseInt(p.substring(dash + 1).trim());
                } else {
                    from = to = Integer.parseInt(p);
                }
                for (int s = Math.min(from, to); s <= Math.max(from, to); s++) {
                    if (s >= 0 && s < size && !out.contains(s)) {
                        out.add(s);
                    }
                }
            } catch (NumberFormatException ignored) {
                // неверный кусок — пропускаем
            }
        }
        return out;
    }

    public ItemTemplate item(String menu, String key) {
        ConfigurationSection s = section(menu).getConfigurationSection("items." + key);
        if (s == null) {
            logger.warning("menus.yml: не найден предмет " + menu + ".items." + key);
            return new ItemTemplate(-1, Material.BARRIER, "<red>" + menu + "." + key, Collections.emptyList(), 0);
        }
        return ItemTemplate.from(s, logger);
    }

    public ItemTemplate filler(String menu) {
        ConfigurationSection s = section(menu).getConfigurationSection("filler");
        if (s == null || !s.getBoolean("enabled", true)) {
            return null;
        }
        return ItemTemplate.from(s, logger);
    }

    /** Шаблон предмета меню. Название и описание — MiniMessage с плейсхолдерами. */
    public record ItemTemplate(int slot, Material material, String name, List<String> lore, int modelData) {

        static ItemTemplate from(ConfigurationSection s, Logger logger) {
            String matName = s.getString("material", "STONE");
            Material material = Material.matchMaterial(matName == null ? "STONE" : matName.toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.warning("menus.yml: неизвестный материал '" + matName + "' в " + s.getCurrentPath());
                material = Material.BARRIER;
            }
            return new ItemTemplate(s.getInt("slot", -1), material, s.getString("name", " "),
                    s.getStringList("lore"), s.getInt("custom-model-data", 0));
        }

        public ItemStack build(TagResolver resolver) {
            return build(resolver, null, null);
        }

        /**
         * @param materialOverride другой материал (например, для надетого титула), может быть null
         * @param headOwner        владелец головы, если материал PLAYER_HEAD, может быть null
         */
        @SuppressWarnings("deprecation")
        public ItemStack build(TagResolver resolver, Material materialOverride, UUID headOwner) {
            ItemStack item = new ItemStack(materialOverride != null ? materialOverride : material, 1);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return item;
            }
            meta.displayName(line(name, resolver));
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String l : lore) {
                    lines.add(line(l, resolver));
                }
                meta.lore(lines);
            }
            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            if (headOwner != null && playerHeads && meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(headOwner));
            }
            item.setItemMeta(meta);
            return item;
        }

        private static Component line(String text, TagResolver resolver) {
            return MM.deserialize("<!italic>" + text, resolver);
        }
    }
}
