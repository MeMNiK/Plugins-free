package dev.foolio.titles;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import dev.foolio.titles.hook.LuckPermsHook;
import dev.foolio.titles.input.ChatInput;
import dev.foolio.titles.menu.MenuConfig;
import dev.foolio.titles.menu.MenuListener;
import dev.foolio.titles.storage.Database;
import dev.foolio.titles.text.Lang;
import dev.foolio.titles.text.Sounds;
import dev.foolio.titles.text.TitleFormatter;

import java.io.File;
import java.util.logging.Level;

/**
 * FoolioTitles — личные титулы игроков за токены с проверкой администрацией.
 * Титул ставится суффиксом LuckPerms.
 */
public final class FoolioTitles extends JavaPlugin implements Listener {

    private final Lang lang = new Lang();
    private final Sounds sounds = new Sounds();
    private final MenuConfig menus = new MenuConfig();
    private final TitleFormatter formatter = new TitleFormatter();
    private LuckPermsHook luckPerms;
    private TitleService service;
    private ChatInput chatInput;
    private MenuListener menuListener;

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().severe("LuckPerms не найден — FoolioTitles отключён.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        luckPerms = new LuckPermsHook();
        chatInput = new ChatInput(this);
        menuListener = new MenuListener(this);
        reloadAll();

        service = new TitleService(this, new Database(new File(getDataFolder(), "data.db")));
        try {
            service.open();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Не удалось открыть базу данных — FoolioTitles отключён.", e);
            service.shutdown();
            service = null;
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(menuListener, this);
        Bukkit.getPluginManager().registerEvents(chatInput, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("titles");
        if (command != null) {
            TitlesCommand executor = new TitlesCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // на случай /reload — подхватываем уже зашедших игроков
        for (Player p : Bukkit.getOnlinePlayers()) {
            service.handleJoin(p);
        }
        getLogger().info("FoolioTitles " + getDescription().getVersion() + " включён.");
    }

    @Override
    public void onDisable() {
        if (menuListener != null) {
            menuListener.closeAll();
        }
        if (chatInput != null) {
            chatInput.stopAll();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    /** Перечитать config.yml и menus.yml. */
    public void reloadAll() {
        saveDefaultConfig();
        reloadConfig();
        if (!new File(getDataFolder(), "menus.yml").exists()) {
            saveResource("menus.yml", false);
        }
        YamlConfiguration menusYaml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "menus.yml"));
        menus.load(menusYaml, getLogger());
        lang.load(getConfig().getConfigurationSection("messages"), getConfig().getString("date-format"));
        sounds.load(getConfig().getConfigurationSection("sounds"));
        formatter.load(getConfig().getConfigurationSection("title"), getConfig().getConfigurationSection("luckperms"),
                getLogger());
        luckPerms.load(getConfig().getConfigurationSection("luckperms"));
        chatInput.load(getConfig().getConfigurationSection("input"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.handleJoin(event.getPlayer());
    }

    // ------------------------------------------------------------------ доступ к частям плагина

    public Lang lang() {
        return lang;
    }

    public Sounds sounds() {
        return sounds;
    }

    public MenuConfig menus() {
        return menus;
    }

    public TitleFormatter formatter() {
        return formatter;
    }

    public LuckPermsHook luckPerms() {
        return luckPerms;
    }

    public TitleService service() {
        return service;
    }

    public ChatInput chatInput() {
        return chatInput;
    }

    public MenuListener menuListener() {
        return menuListener;
    }
}
