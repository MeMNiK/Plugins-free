package dev.foolio.titles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import dev.foolio.titles.storage.Database;
import dev.foolio.titles.storage.PlayerData;
import dev.foolio.titles.storage.TitleRecord;
import dev.foolio.titles.storage.TitleStatus;
import dev.foolio.titles.text.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Вся логика титулов: БД работает в отдельном потоке, результаты возвращаются в основной поток. */
public final class TitleService {

    public static final String PERM_USE = "foolio.titles.use";
    public static final String PERM_ADMIN = "foolio.titles.admin";
    public static final String PERM_SLOTS = "foolio.titles.slots.";

    /** Найденный по нику игрок. */
    public record Target(UUID uuid, String name) { }

    /** Данные игрока + список его титулов (для меню). */
    public record Snapshot(PlayerData player, List<TitleRecord> titles) { }

    private record JoinResult(Database.JoinData data, int pending) { }

    private final FoolioTitles plugin;
    private final Database database;
    private final ExecutorService dbThread;
    private final Executor mainThread;
    /** Время последней отправки заявки игроком (защита от спама заявками/уведомлениями). */
    private final java.util.Map<UUID, Long> lastSubmit = new java.util.concurrent.ConcurrentHashMap<>();

    public TitleService(FoolioTitles plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.dbThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FoolioTitles-DB");
            t.setDaemon(true);
            return t;
        });
        this.mainThread = task -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        };
    }

    @FunctionalInterface
    private interface DbCall<T> extends Callable<T> { }

    /** Выполнить запрос в потоке БД. */
    public <T> CompletableFuture<T> db(DbCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, dbThread);
    }

    /** Выполнить запрос в БД и обработать результат в основном потоке; ошибки логируются. */
    public <T> void run(DbCall<T> call, Consumer<T> then, CommandSender errorTo) {
        db(call).whenComplete((result, error) -> mainThread.execute(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "Ошибка базы данных", cause);
                if (errorTo != null) {
                    plugin.lang().send(errorTo, "error.database");
                }
                return;
            }
            then.accept(result);
        }));
    }

    public void open() throws Exception {
        try {
            db(() -> {
                database.open();
                return null;
            }).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    public void shutdown() {
        dbThread.shutdown();
        try {
            if (!dbThread.awaitTermination(10, TimeUnit.SECONDS)) {
                dbThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        database.close();
    }

    // ------------------------------------------------------------------ helpers

    public Database database() {
        return database;
    }

    private Lang lang() {
        return plugin.lang();
    }

    public Component render(String raw) {
        return plugin.formatter().render(raw);
    }

    /** Лимит слотов хранилища: значение по умолчанию или больше по праву foolio.titles.slots.N. */
    public int slotLimit(Player player) {
        int limit = plugin.getConfig().getInt("limits.default-slots", 5);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission().toLowerCase(Locale.ROOT);
            if (info.getValue() && perm.startsWith(PERM_SLOTS)) {
                try {
                    limit = Math.max(limit, Integer.parseInt(perm.substring(PERM_SLOTS.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return limit;
    }

    /** Сколько секунд игроку ждать до следующей заявки (0 — можно отправлять). */
    public long submitCooldownLeft(Player player) {
        long cooldown = Math.max(0, plugin.getConfig().getInt("limits.submit-cooldown-seconds", 30)) * 1000L;
        Long last = lastSubmit.get(player.getUniqueId());
        if (last == null || cooldown == 0) {
            return 0;
        }
        long left = last + cooldown - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission(PERM_ADMIN)) {
            return true;
        }
        lang().send(sender, "error.no-permission");
        return false;
    }

    public int maxPending() {
        return plugin.getConfig().getInt("limits.max-pending", 1);
    }

    public int freeRejections() {
        return Math.max(0, plugin.getConfig().getInt("rejections.free-rejections", 1));
    }

    /** Найти игрока по нику: онлайн → наша БД → кеш сервера. */
    public void resolve(String name, Consumer<Target> then, CommandSender errorTo) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(new Target(online.getUniqueId(), online.getName()));
            return;
        }
        run(() -> database.findByName(name), data -> {
            if (data != null) {
                then.accept(new Target(data.uuid(), data.name() != null ? data.name() : name));
                return;
            }
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
            if (cached != null) {
                then.accept(new Target(cached.getUniqueId(), cached.getName() != null ? cached.getName() : name));
            } else {
                then.accept(null);
            }
        }, errorTo);
    }

    private void notifyAdmins(String key, TagResolver... resolvers) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM_ADMIN)) {
                lang().send(p, key, resolvers);
                plugin.sounds().play(p, "notify");
            }
        }
    }

    // ------------------------------------------------------------------ вход игрока

    public void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        boolean isAdmin = player.hasPermission(PERM_ADMIN);
        run(() -> {
            Database.JoinData data = database.handleJoin(uuid, name);
            int pending = isAdmin ? database.countPending() : 0;
            return new JoinResult(data, pending);
        }, result -> {
            Database.JoinData data = result.data();
            int pending = result.pending();
            // сверяем суффикс LuckPerms с надетым титулом
            syncSuffix(uuid, data.activeRaw());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (data.claimedTokens() > 0) {
                    lang().send(player, "tokens.received", Lang.num("amount", data.claimedTokens()));
                }
                List<Integer> shown = new ArrayList<>();
                for (TitleRecord t : data.unnotified()) {
                    sendDecision(player, t, t.refunded());
                    shown.add(t.id());
                }
                if (!shown.isEmpty()) {
                    run(() -> {
                        database.markNotified(shown);
                        return null;
                    }, ignored -> { }, null);
                }
                if (pending > 0 && plugin.getConfig().getBoolean("notify.admins-on-join", true)) {
                    lang().send(player, "admin.pending-on-join", Lang.num("count", pending));
                }
            }, 40L);
        }, null);
    }

    private void syncSuffix(UUID uuid, String activeRaw) {
        String expected = activeRaw == null ? null : plugin.formatter().toSuffix(activeRaw);
        String current = plugin.luckPerms().currentSuffix(uuid);
        if (expected == null ? current != null : !expected.equals(current)) {
            plugin.luckPerms().setSuffix(uuid, expected);
        }
    }

    private void sendDecision(Player player, TitleRecord t, boolean refunded) {
        TagResolver r = TagResolver.resolver(
                Lang.comp("title", render(t.raw())),
                Lang.text("reason", t.reason() == null ? "-" : t.reason()),
                Lang.text("admin", t.reviewedBy() == null ? "-" : t.reviewedBy()));
        switch (t.status()) {
            case APPROVED -> {
                lang().send(player, "player.approved", r);
                plugin.sounds().play(player, "success");
            }
            case REJECTED -> {
                lang().send(player, refunded ? "player.rejected-refund" : "player.rejected-no-refund", r);
                plugin.sounds().play(player, "error");
            }
            case REVOKED -> {
                lang().send(player, "player.revoked", r);
                plugin.sounds().play(player, "error");
            }
            default -> { }
        }
    }

    /** Сообщить владельцу о решении сразу, если он онлайн (иначе — при входе). */
    private void deliverDecision(TitleRecord t, boolean refunded) {
        Player owner = Bukkit.getPlayer(t.owner());
        if (owner == null) {
            return;
        }
        sendDecision(owner, t, refunded);
        run(() -> {
            database.markNotified(List.of(t.id()));
            return null;
        }, ignored -> { }, null);
    }

    // ------------------------------------------------------------------ игрок

    public void submit(Player player, String raw, Runnable onDone) {
        long wait = submitCooldownLeft(player);
        if (wait > 0) {
            lang().send(player, "player.cooldown", Lang.num("seconds", wait));
            onDone.run();
            return;
        }
        int limit = slotLimit(player);
        int maxPending = maxPending();
        run(() -> database.submit(player.getUniqueId(), player.getName(), raw, limit, maxPending), result -> {
            switch (result) {
                case OK -> {
                    lastSubmit.put(player.getUniqueId(), System.currentTimeMillis());
                    lang().send(player, "player.submitted", Lang.comp("title", render(raw)));
                    plugin.sounds().play(player, "success");
                    if (plugin.getConfig().getBoolean("notify.admins-on-new-request", true)) {
                        notifyAdmins("admin.new-request", Lang.text("player", player.getName()),
                                Lang.comp("title", render(raw)));
                    }
                    plugin.menuListener().refreshReviewMenus();
                }
                case NO_TOKENS -> {
                    lang().send(player, "player.no-tokens");
                    plugin.sounds().play(player, "error");
                }
                case SLOTS_FULL -> {
                    lang().send(player, "player.slots-full", Lang.num("limit", limit));
                    plugin.sounds().play(player, "error");
                }
                case TOO_MANY_PENDING -> {
                    lang().send(player, "player.too-many-pending", Lang.num("limit", maxPending));
                    plugin.sounds().play(player, "error");
                }
            }
            onDone.run();
        }, player);
    }

    public void cancel(Player player, int id, Runnable onDone) {
        run(() -> database.cancel(id, player.getUniqueId()), ok -> {
            if (ok) {
                lang().send(player, "player.cancelled");
                plugin.sounds().play(player, "success");
                plugin.menuListener().refreshReviewMenus();
            } else {
                lang().send(player, "error.already-processed");
            }
            onDone.run();
        }, player);
    }

    public void equip(Player player, int id, Runnable onDone) {
        run(() -> database.setActive(player.getUniqueId(), id), title -> {
            if (title == null) {
                lang().send(player, "error.already-processed");
                onDone.run();
                return;
            }
            plugin.luckPerms().setSuffix(player.getUniqueId(), plugin.formatter().toSuffix(title.raw()))
                    .whenComplete((v, e) -> mainThread.execute(() -> {
                        if (e != null) {
                            plugin.getLogger().log(Level.SEVERE, "Не удалось поставить суффикс в LuckPerms", e);
                            lang().send(player, "error.luckperms");
                        } else {
                            lang().send(player, "player.equipped", Lang.comp("title", render(title.raw())));
                            plugin.sounds().play(player, "success");
                        }
                        onDone.run();
                    }));
        }, player);
    }

    public void unequip(Player player, Runnable onDone) {
        run(() -> database.setActive(player.getUniqueId(), null), ignored -> {
            plugin.luckPerms().setSuffix(player.getUniqueId(), null)
                    .whenComplete((v, e) -> mainThread.execute(() -> {
                        if (e != null) {
                            plugin.getLogger().log(Level.SEVERE, "Не удалось убрать суффикс в LuckPerms", e);
                            lang().send(player, "error.luckperms");
                        } else {
                            lang().send(player, "player.unequipped");
                            plugin.sounds().play(player, "click");
                        }
                        onDone.run();
                    }));
        }, player);
    }

    public void deleteOwn(Player player, int id, Runnable onDone) {
        run(() -> database.remove(id, player.getUniqueId(), TitleStatus.DELETED, null, null), result -> {
            if (result == null) {
                lang().send(player, "error.already-processed");
            } else {
                if (result.wasActive()) {
                    plugin.luckPerms().setSuffix(player.getUniqueId(), null);
                }
                lang().send(player, "player.deleted", Lang.comp("title", render(result.title().raw())));
                plugin.sounds().play(player, "click");
            }
            onDone.run();
        }, player);
    }

    // ------------------------------------------------------------------ администрация

    public void approve(CommandSender admin, int id, Runnable onDone) {
        if (!checkAdmin(admin)) {
            onDone.run();
            return;
        }
        boolean reset = plugin.getConfig().getBoolean("rejections.reset-strikes-on-approve", false);
        run(() -> database.approve(id, admin.getName(), reset), title -> {
            if (title == null) {
                lang().send(admin, "error.already-processed");
            } else {
                lang().send(admin, "admin.approved", Lang.text("player", title.ownerName()),
                        Lang.comp("title", render(title.raw())));
                if (admin instanceof Player p) {
                    plugin.sounds().play(p, "success");
                }
                deliverDecision(title, false);
                plugin.menuListener().refreshReviewMenus();
            }
            onDone.run();
        }, admin);
    }

    public void reject(CommandSender admin, int id, String reason, Runnable onDone) {
        if (!checkAdmin(admin)) {
            onDone.run();
            return;
        }
        run(() -> database.reject(id, admin.getName(), reason, freeRejections()), result -> {
            if (result == null) {
                lang().send(admin, "error.already-processed");
            } else {
                TitleRecord t = result.title();
                lang().send(admin, result.refunded() ? "admin.rejected-refund" : "admin.rejected-no-refund",
                        Lang.text("player", t.ownerName()), Lang.comp("title", render(t.raw())),
                        Lang.text("reason", reason), Lang.num("strikes", result.strikes()));
                if (admin instanceof Player p) {
                    plugin.sounds().play(p, "click");
                }
                deliverDecision(t, result.refunded());
                plugin.menuListener().refreshReviewMenus();
            }
            onDone.run();
        }, admin);
    }

    public void revoke(CommandSender admin, int id, String reason, Runnable onDone) {
        if (!checkAdmin(admin)) {
            onDone.run();
            return;
        }
        run(() -> database.remove(id, null, TitleStatus.REVOKED, admin.getName(), reason), result -> {
            if (result == null) {
                lang().send(admin, "error.already-processed");
            } else {
                TitleRecord t = result.title();
                if (result.wasActive()) {
                    plugin.luckPerms().setSuffix(t.owner(), null);
                }
                lang().send(admin, "admin.revoked", Lang.text("player", t.ownerName()),
                        Lang.comp("title", render(t.raw())));
                deliverDecision(t, false);
            }
            onDone.run();
        }, admin);
    }

    public void tokens(CommandSender sender, String action, String name, int amount) {
        resolve(name, target -> {
            if (target == null) {
                if (action.equals("give")) {
                    // игрок ещё не заходил — токены выдадутся при первом входе
                    run(() -> database.addPendingTokens(name, amount), total ->
                            lang().send(sender, "tokens.pending", Lang.text("player", name),
                                    Lang.num("amount", amount), Lang.num("total", total)), sender);
                } else {
                    lang().send(sender, "error.player-not-found", Lang.text("player", name));
                }
                return;
            }
            run(() -> switch (action) {
                case "give" -> database.addTokens(target.uuid(), target.name(), amount);
                case "take" -> database.addTokens(target.uuid(), target.name(), -amount);
                case "set" -> database.setTokens(target.uuid(), target.name(), amount);
                default -> database.getPlayer(target.uuid()).tokens();
            }, balance -> {
                TagResolver r = TagResolver.resolver(Lang.text("player", target.name()),
                        Lang.num("amount", amount), Lang.num("balance", balance));
                lang().send(sender, "tokens." + action, r);
                Player online = Bukkit.getPlayer(target.uuid());
                if (online != null && action.equals("give") && online != sender) {
                    lang().send(online, "tokens.received", Lang.num("amount", amount));
                    plugin.sounds().play(online, "success");
                }
            }, sender);
        }, sender);
    }

    public void strikes(CommandSender sender, String action, String name) {
        resolve(name, target -> {
            if (target == null) {
                lang().send(sender, "error.player-not-found", Lang.text("player", name));
                return;
            }
            run(() -> {
                if (action.equals("reset")) {
                    database.setStrikes(target.uuid(), target.name(), 0);
                }
                return database.getPlayer(target.uuid()).strikes();
            }, strikes -> lang().send(sender, action.equals("reset") ? "strikes.reset" : "strikes.check",
                    Lang.text("player", target.name()), Lang.num("strikes", strikes),
                    Lang.num("free", freeRejections())), sender);
        }, sender);
    }

    /** Загрузить данные игрока для меню. */
    public void loadPlayer(UUID uuid, Consumer<PlayerData> then, CommandSender errorTo) {
        run(() -> database.getPlayer(uuid), then, errorTo);
    }

    public void loadStorage(UUID uuid, Consumer<Snapshot> then, CommandSender errorTo) {
        run(() -> new Snapshot(database.getPlayer(uuid), database.getStorage(uuid)), then, errorTo);
    }

    public void loadQueue(Consumer<List<Database.QueueEntry>> then, CommandSender errorTo) {
        run(database::getPendingQueue, then, errorTo);
    }

    public void loadApproved(UUID uuid, Consumer<Snapshot> then, CommandSender errorTo) {
        run(() -> new Snapshot(database.getPlayer(uuid), database.getApproved(uuid)), then, errorTo);
    }
}
