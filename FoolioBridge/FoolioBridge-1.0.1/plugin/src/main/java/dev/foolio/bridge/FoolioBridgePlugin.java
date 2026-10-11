// FoolioBridge - Development by DeJIoVoy
package dev.foolio.bridge;

import dev.foolio.bridge.api.ApiClient;
import dev.foolio.bridge.api.Order;
import dev.foolio.bridge.store.OrderStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class FoolioBridgePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String DEFAULT_SECRET = "CHANGE-ME-to-a-long-random-string";
    public static final String AUTHOR = "DeJIoVoy";
    public static final String SIGNATURE = "Development by " + AUTHOR;

    private OrderStore store;
    private ApiClient api;
    private CommandBuilder builder;

    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicBoolean acking = new AtomicBoolean(false);
    private volatile String lastPollStatus = "not polled yet";
    private volatile long lastPollAt;
    private volatile String lastError;
    private boolean listenerRegistered;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        printBanner();
        saveDefaultConfig();
        try {
            store = new OrderStore(getDataFolder().toPath());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Cannot open the order queue, plugin disabled", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        register("donate");
        register("foolio");
        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, this);
            listenerRegistered = true;
        }
        start();
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("FoolioBridge disabled. " + SIGNATURE);
    }

    private void printBanner() {
        String v = getDescription().getVersion();
        String[] lines = {
                "",
                "=========================================",
                "  FoolioBridge v" + v,
                "  Donate site <-> server bridge",
                "  " + SIGNATURE,
                "  Free & open source (MIT)",
                "=========================================",
                ""
        };
        for (String line : lines) getLogger().info(line);
    }

    private void register(String name) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    /** (Re)reads config and starts polling. */
    private void start() {
        Bukkit.getScheduler().cancelTasks(this);
        FileConfiguration c = getConfig();
        api = null;

        builder = new CommandBuilder(
                c.getString("security.player-name-pattern", "^[A-Za-z0-9_]{1,16}$"),
                c.getStringList("security.allowed-commands"));

        String endpoint = c.getString("site.endpoint", "");
        String secret = c.getString("site.secret", "");
        String serverId = c.getString("site.server-id", "default");
        if (endpoint.isEmpty() || endpoint.contains("example.com") || DEFAULT_SECRET.equals(secret)) {
            lastPollStatus = "not configured";
            getLogger().warning("Set site.endpoint and site.secret in config.yml, then run /foolio reload");
            return;
        }
        try {
            api = new ApiClient(endpoint, secret, serverId, getDescription().getVersion(),
                    c.getInt("site.timeout-seconds", 10));
        } catch (IllegalArgumentException e) {
            lastPollStatus = "bad config: " + e.getMessage();
            getLogger().severe("Bad site settings: " + e.getMessage());
            return;
        }
        if (api.isInsecureHttp()) {
            getLogger().warning("site.endpoint uses plain http. Requests are still signed, "
                    + "but order data is visible on the network - use https.");
        }

        long interval = Math.max(3, c.getInt("polling.interval-seconds", 10)) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::poll, 40L, interval);
        getLogger().info("Polling " + endpoint + " every " + interval / 20 + "s as server '" + serverId + "'");
    }

    // ------------------------------------------------------------------ polling

    private void poll() {
        ApiClient client = api;
        if (client == null || !polling.compareAndSet(false, true)) return;
        try {
            List<Order> orders = client.fetchPending(msg -> getLogger().warning(msg));
            int fresh = 0;
            for (Order o : orders) {
                if (store.offer(o)) fresh++;
            }
            lastPollAt = System.currentTimeMillis();
            lastPollStatus = "ok";
            if (lastError != null) {
                getLogger().info("Connection to the site restored");
                lastError = null;
            }
            if (fresh > 0) getLogger().info("Received " + fresh + " new order(s)");
            if (store.countPending() > 0) Bukkit.getScheduler().runTask(this, () -> deliver(null));
            ack();
        } catch (ApiClient.ApiException e) {
            lastPollAt = System.currentTimeMillis();
            lastPollStatus = "error";
            // Log each distinct error once, not every few seconds.
            if (!e.getMessage().equals(lastError)) getLogger().warning("Poll failed: " + e.getMessage());
            lastError = e.getMessage();
        } catch (IOException e) {
            lastPollStatus = "storage error";
            getLogger().log(Level.SEVERE, "Cannot save the order queue", e);
        } finally {
            polling.set(false);
        }
    }

    /** Sends unconfirmed results to the site. Runs async. */
    private void ack() {
        ApiClient client = api;
        if (client == null || !acking.compareAndSet(false, true)) return;
        try {
            List<Map<String, Object>> results = store.unackedResults();
            if (results.isEmpty()) return;
            List<String> accepted = client.ack(results);
            store.markAcked(accepted);
        } catch (ApiClient.ApiException e) {
            if (!e.getMessage().equals(lastError)) getLogger().warning("Ack failed (will retry): " + e.getMessage());
            lastError = e.getMessage();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Cannot save the order queue", e);
        } finally {
            acking.set(false);
        }
    }

    // ------------------------------------------------------------------ delivery (main thread)

    /** Delivers every pending order that can be delivered now; onlyPlayer limits it to one player. */
    private void deliver(String onlyPlayer) {
        int count = 0;
        for (Order order : store.pending()) {
            if (onlyPlayer != null && !order.player.equalsIgnoreCase(onlyPlayer)) continue;
            Player online = Bukkit.getPlayerExact(order.player);
            if (order.onlineOnly && online == null) continue;
            if (deliverOne(order, online)) count++;
        }
        if (count > 0) Bukkit.getScheduler().runTaskAsynchronously(this, this::ack);
    }

    private boolean deliverOne(Order order, Player online) {
        if (!store.isPending(order.id)) return false;
        String name = online != null ? online.getName() : order.player;
        String uuid = online != null ? online.getUniqueId().toString() : null;
        CommandBuilder.Result built = builder.build(order, name, uuid);
        String status = built.commands.isEmpty() ? OrderStore.FAILED : OrderStore.DELIVERED;

        // Persist first: if the server crashes mid-way the order is never given twice.
        try {
            store.markFinished(order.id, status, built.errors);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Cannot save order " + order.id + ", NOT delivering it", e);
            return false;
        }

        List<String> runErrors = new ArrayList<>();
        for (String cmd : built.commands) {
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
                    runErrors.add("unknown command: " + cmd.split(" ", 2)[0]);
                }
            } catch (Exception e) {
                runErrors.add("command failed: " + cmd.split(" ", 2)[0] + " (" + e.getClass().getSimpleName() + ")");
                getLogger().log(Level.WARNING, "Command failed for order " + order.id + ": " + cmd, e);
            }
        }
        try {
            store.addErrors(order.id, runErrors);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Cannot save errors for order " + order.id, e);
        }

        List<String> allErrors = new ArrayList<>(built.errors);
        allErrors.addAll(runErrors);
        if (OrderStore.FAILED.equals(status)) {
            getLogger().warning("Order " + order.id + " for " + name + " failed: " + String.join("; ", allErrors));
        } else {
            getLogger().info("Delivered order " + order.id + " to " + name
                    + (allErrors.isEmpty() ? "" : " with warnings: " + String.join("; ", allErrors)));
            if (online != null) {
                String msg = order.message != null ? order.message : getConfig().getString("messages.delivered", "");
                if (!msg.isEmpty()) online.sendMessage(color(msg
                        .replace("{title}", order.title != null ? order.title : "")
                        .replace("{order}", order.id)
                        .replace("{player}", name)));
            }
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String name = event.getPlayer().getName();
        long delay = Math.max(1, getConfig().getInt("delivery.join-delay-ticks", 40));
        Bukkit.getScheduler().runTaskLater(this, () -> deliver(name), delay);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("donate")) {
            return donateCommand(sender);
        }
        if (!sender.hasPermission("foolio.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "reload":
                reloadConfig();
                start();
                sender.sendMessage(msg("reloaded"));
                return true;
            case "poll":
                if (api == null) {
                    sender.sendMessage(color("&cНе настроено: " + lastPollStatus));
                    return true;
                }
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    poll();
                    Bukkit.getScheduler().runTask(this, () -> sendStatus(sender, null));
                });
                return true;
            case "status":
            default:
                if (api == null) {
                    sendStatus(sender, "&c" + lastPollStatus);
                    return true;
                }
                ApiClient client = api;
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    String ping;
                    try {
                        client.ping();
                        ping = "&aOK, подпись совпадает";
                    } catch (ApiClient.ApiException e) {
                        ping = "&c" + e.getMessage();
                    }
                    String p = ping;
                    Bukkit.getScheduler().runTask(this, () -> sendStatus(sender, p));
                });
                return true;
        }
    }

    private void sendStatus(CommandSender sender, String ping) {
        sender.sendMessage(color("&6FoolioBridge &7v" + getDescription().getVersion() + " &8| &7" + SIGNATURE));
        if (ping != null) sender.sendMessage(color("&7Сайт: " + ping));
        String when = lastPollAt == 0 ? "никогда" : new SimpleDateFormat("HH:mm:ss").format(new Date(lastPollAt));
        sender.sendMessage(color("&7Последний опрос: &f" + when + " &7(" + lastPollStatus + ")"));
        if (lastError != null) sender.sendMessage(color("&7Последняя ошибка: &c" + lastError));
        sender.sendMessage(color("&7Ждут выдачи: &f" + store.countPending()
                + " &7| Ждут подтверждения сайтом: &f" + store.countUnacked()));
    }

    private boolean donateCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command");
            return true;
        }
        String name = sender.getName();
        int limit = Math.max(1, getConfig().getInt("history.donate-limit", 10));
        String link = getConfig().getString("messages.donate-link", "");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<Map<String, Object>> rows;
            try {
                rows = store.history(name, limit);
            } catch (IOException e) {
                rows = Collections.emptyList();
            }
            List<Map<String, Object>> list = rows;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!link.isEmpty()) sender.sendMessage(color(link));
                if (list.isEmpty()) {
                    sender.sendMessage(msg("history-empty"));
                    sender.sendMessage(color("&8FoolioBridge • " + SIGNATURE));
                    return;
                }
                sender.sendMessage(msg("history-header"));
                SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                for (Map<String, Object> r : list) {
                    Object at = r.get("at");
                    String date = at instanceof Number ? f.format(new Date(((Number) at).longValue())) : "?";
                    Object title = r.get("title");
                    String line = getConfig().getString("messages.history-line", "&7{date} &f{title} &8#{order}")
                            .replace("{date}", date)
                            .replace("{title}", title != null ? title.toString() : "Покупка")
                            .replace("{order}", String.valueOf(r.get("id")))
                            .replace("{status}", OrderStore.FAILED.equals(r.get("status")) ? "&cошибка" : "&aвыдано");
                    sender.sendMessage(color(line));
                }
                sender.sendMessage(color("&8FoolioBridge • " + SIGNATURE));
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("foolio") && args.length == 1 && sender.hasPermission("foolio.admin")) {
            List<String> out = new ArrayList<>();
            for (String s : Arrays.asList("status", "poll", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------ helpers

    private String msg(String key) {
        return color(getConfig().getString("messages." + key, key));
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
