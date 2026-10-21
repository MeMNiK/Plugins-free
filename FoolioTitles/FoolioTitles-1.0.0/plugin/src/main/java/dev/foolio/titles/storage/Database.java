package dev.foolio.titles.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * SQLite-хранилище. Все методы синхронные и вызываются ТОЛЬКО из одного
 * потока БД (см. TitleService), поэтому отдельной синхронизации не нужно.
 */
public final class Database {

    public enum SubmitResult { OK, NO_TOKENS, SLOTS_FULL, TOO_MANY_PENDING }

    public record RejectResult(TitleRecord title, boolean refunded, int strikes) { }

    public record RemoveResult(TitleRecord title, boolean wasActive) { }

    public record JoinData(int claimedTokens, List<TitleRecord> unnotified, Integer activeTitleId, String activeRaw) { }

    private final File file;
    private Connection connection;

    public Database(File file) {
        this.file = file;
    }

    // ------------------------------------------------------------------ lifecycle

    public void open() throws SQLException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // драйвер может зарегистрироваться сам
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ft_players ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "name TEXT NOT NULL,"
                    + "name_lower TEXT NOT NULL,"
                    + "tokens INTEGER NOT NULL DEFAULT 0,"
                    + "strikes INTEGER NOT NULL DEFAULT 0,"
                    + "active_title INTEGER)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ft_players_name ON ft_players(name_lower)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ft_titles ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "owner TEXT NOT NULL,"
                    + "owner_name TEXT NOT NULL,"
                    + "raw TEXT NOT NULL,"
                    + "status TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "reviewed_by TEXT,"
                    + "reviewed_at INTEGER,"
                    + "reason TEXT,"
                    + "refunded INTEGER NOT NULL DEFAULT 0,"
                    + "notified INTEGER NOT NULL DEFAULT 1)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ft_titles_owner ON ft_titles(owner, status)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS ft_titles_status ON ft_titles(status)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ft_pending_tokens ("
                    + "name_lower TEXT PRIMARY KEY,"
                    + "amount INTEGER NOT NULL)");
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ players

    private void ensureRow(UUID uuid, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ft_players(uuid, name, name_lower) VALUES(?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase(Locale.ROOT));
            ps.executeUpdate();
        }
    }

    /** Вызывается при входе игрока: обновляет ник, выдаёт отложенные токены, собирает уведомления. */
    public JoinData handleJoin(UUID uuid, String name) throws SQLException {
        String lower = name.toLowerCase(Locale.ROOT);
        int claimed = 0;
        connection.setAutoCommit(false);
        try {
            // если этот ник раньше принадлежал другому UUID — освобождаем его
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ft_players SET name_lower = '' WHERE name_lower = ? AND uuid <> ?")) {
                ps.setString(1, lower);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            ensureRow(uuid, name);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT amount FROM ft_pending_tokens WHERE name_lower = ?")) {
                ps.setString(1, lower);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        claimed = rs.getInt(1);
                    }
                }
            }
            if (claimed > 0) {
                addTokensRaw(uuid, claimed);
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM ft_pending_tokens WHERE name_lower = ?")) {
                    ps.setString(1, lower);
                    ps.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }

        List<TitleRecord> unnotified = queryTitles(
                "SELECT * FROM ft_titles WHERE owner = ? AND notified = 0 ORDER BY reviewed_at", uuid.toString());

        PlayerData data = getPlayer(uuid);
        String activeRaw = null;
        if (data.activeTitle() != null) {
            TitleRecord t = getTitle(data.activeTitle());
            if (t != null && t.status() == TitleStatus.APPROVED) {
                activeRaw = t.raw();
            }
        }
        return new JoinData(claimed, unnotified, data.activeTitle(), activeRaw);
    }

    public PlayerData getPlayer(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, tokens, strikes, active_title FROM ft_players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int active = rs.getInt(4);
                    Integer activeId = rs.wasNull() ? null : active;
                    return new PlayerData(uuid, rs.getString(1), rs.getInt(2), rs.getInt(3), activeId);
                }
            }
        }
        return new PlayerData(uuid, null, 0, 0, null);
    }

    public PlayerData findByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid FROM ft_players WHERE name_lower = ? LIMIT 1")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return getPlayer(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return null;
    }

    private void addTokensRaw(UUID uuid, int delta) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE ft_players SET tokens = MAX(0, tokens + ?) WHERE uuid = ?")) {
            ps.setInt(1, delta);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Изменяет баланс (не уходит в минус) и возвращает новый баланс. */
    public int addTokens(UUID uuid, String name, int delta) throws SQLException {
        ensureRow(uuid, name);
        addTokensRaw(uuid, delta);
        return getPlayer(uuid).tokens();
    }

    public int setTokens(UUID uuid, String name, int amount) throws SQLException {
        ensureRow(uuid, name);
        try (PreparedStatement ps = connection.prepareStatement("UPDATE ft_players SET tokens = ? WHERE uuid = ?")) {
            ps.setInt(1, Math.max(0, amount));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
        return getPlayer(uuid).tokens();
    }

    /** Токены для игрока, который ещё ни разу не заходил: выдадутся при первом входе. */
    public int addPendingTokens(String name, int amount) throws SQLException {
        String lower = name.toLowerCase(Locale.ROOT);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ft_pending_tokens(name_lower, amount) VALUES(?, ?) "
                        + "ON CONFLICT(name_lower) DO UPDATE SET amount = amount + excluded.amount")) {
            ps.setString(1, lower);
            ps.setInt(2, amount);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT amount FROM ft_pending_tokens WHERE name_lower = ?")) {
            ps.setString(1, lower);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : amount;
            }
        }
    }

    public void setStrikes(UUID uuid, String name, int strikes) throws SQLException {
        ensureRow(uuid, name);
        try (PreparedStatement ps = connection.prepareStatement("UPDATE ft_players SET strikes = ? WHERE uuid = ?")) {
            ps.setInt(1, Math.max(0, strikes));
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ titles

    private static TitleRecord read(ResultSet rs) throws SQLException {
        long reviewedAt = rs.getLong("reviewed_at");
        boolean reviewedNull = rs.wasNull();
        return new TitleRecord(
                rs.getInt("id"),
                UUID.fromString(rs.getString("owner")),
                rs.getString("owner_name"),
                rs.getString("raw"),
                TitleStatus.parse(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getString("reviewed_by"),
                reviewedNull ? 0L : reviewedAt,
                rs.getString("reason"),
                rs.getInt("refunded") != 0);
    }

    private List<TitleRecord> queryTitles(String sql, Object... args) throws SQLException {
        List<TitleRecord> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(read(rs));
                }
            }
        }
        return list;
    }

    public TitleRecord getTitle(int id) throws SQLException {
        List<TitleRecord> list = queryTitles("SELECT * FROM ft_titles WHERE id = ?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Титулы игрока, которые занимают слоты хранилища: одобренные и на проверке. */
    public List<TitleRecord> getStorage(UUID owner) throws SQLException {
        return queryTitles("SELECT * FROM ft_titles WHERE owner = ? AND status IN ('APPROVED','PENDING') ORDER BY id",
                owner.toString());
    }

    public List<TitleRecord> getApproved(UUID owner) throws SQLException {
        return queryTitles("SELECT * FROM ft_titles WHERE owner = ? AND status = 'APPROVED' ORDER BY id",
                owner.toString());
    }

    /** Заявка в очереди проверки + сколько отказов уже было у её автора. */
    public record QueueEntry(TitleRecord title, int strikes) { }

    public List<QueueEntry> getPendingQueue() throws SQLException {
        List<QueueEntry> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT t.*, COALESCE(p.strikes, 0) AS owner_strikes FROM ft_titles t "
                        + "LEFT JOIN ft_players p ON p.uuid = t.owner "
                        + "WHERE t.status = 'PENDING' ORDER BY t.created_at, t.id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new QueueEntry(read(rs), rs.getInt("owner_strikes")));
            }
        }
        return list;
    }

    public int countPending() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ft_titles WHERE status = 'PENDING'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private int count(String sql, String owner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Атомарно: проверка лимитов, списание токена, создание заявки. */
    public SubmitResult submit(UUID uuid, String name, String raw, int slotLimit, int maxPending) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ensureRow(uuid, name);
            String owner = uuid.toString();
            int used = count("SELECT COUNT(*) FROM ft_titles WHERE owner = ? AND status IN ('APPROVED','PENDING')", owner);
            if (used >= slotLimit) {
                connection.rollback();
                return SubmitResult.SLOTS_FULL;
            }
            int pending = count("SELECT COUNT(*) FROM ft_titles WHERE owner = ? AND status = 'PENDING'", owner);
            if (maxPending > 0 && pending >= maxPending) {
                connection.rollback();
                return SubmitResult.TOO_MANY_PENDING;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ft_players SET tokens = tokens - 1 WHERE uuid = ? AND tokens >= 1")) {
                ps.setString(1, owner);
                if (ps.executeUpdate() == 0) {
                    connection.rollback();
                    return SubmitResult.NO_TOKENS;
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ft_titles(owner, owner_name, raw, status, created_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, owner);
                ps.setString(2, name);
                ps.setString(3, raw);
                ps.setString(4, TitleStatus.PENDING.name());
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            connection.commit();
            return SubmitResult.OK;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private int changeStatus(int id, TitleStatus from, TitleStatus to, String reviewer, String reason,
                             boolean refunded, boolean notifyOwner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE ft_titles SET status = ?, reviewed_by = ?, reviewed_at = ?, reason = ?, refunded = ?, notified = ? "
                        + "WHERE id = ? AND status = ?")) {
            ps.setString(1, to.name());
            if (reviewer == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, reviewer);
            }
            ps.setLong(3, System.currentTimeMillis());
            if (reason == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, reason);
            }
            ps.setInt(5, refunded ? 1 : 0);
            ps.setInt(6, notifyOwner ? 0 : 1);
            ps.setInt(7, id);
            ps.setString(8, from.name());
            return ps.executeUpdate();
        }
    }

    /** @return одобренный титул или null, если заявка уже обработана. */
    public TitleRecord approve(int id, String reviewer, boolean resetStrikes) throws SQLException {
        connection.setAutoCommit(false);
        try {
            TitleRecord t = getTitle(id);
            if (t == null || t.status() != TitleStatus.PENDING
                    || changeStatus(id, TitleStatus.PENDING, TitleStatus.APPROVED, reviewer, null, false, true) == 0) {
                connection.rollback();
                return null;
            }
            if (resetStrikes) {
                try (PreparedStatement ps = connection.prepareStatement("UPDATE ft_players SET strikes = 0 WHERE uuid = ?")) {
                    ps.setString(1, t.owner().toString());
                    ps.executeUpdate();
                }
            }
            connection.commit();
            return getTitle(id);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Отклонение: пока у игрока меньше {@code freeRejections} страйков — токен возвращается.
     * @return результат или null, если заявка уже обработана.
     */
    public RejectResult reject(int id, String reviewer, String reason, int freeRejections) throws SQLException {
        connection.setAutoCommit(false);
        try {
            TitleRecord t = getTitle(id);
            if (t == null || t.status() != TitleStatus.PENDING) {
                connection.rollback();
                return null;
            }
            PlayerData owner = getPlayer(t.owner());
            boolean refund = owner.strikes() < freeRejections;
            if (changeStatus(id, TitleStatus.PENDING, TitleStatus.REJECTED, reviewer, reason, refund, true) == 0) {
                connection.rollback();
                return null;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ft_players SET strikes = strikes + 1, tokens = tokens + ? WHERE uuid = ?")) {
                ps.setInt(1, refund ? 1 : 0);
                ps.setString(2, t.owner().toString());
                ps.executeUpdate();
            }
            connection.commit();
            return new RejectResult(getTitle(id), refund, owner.strikes() + 1);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Игрок отзывает свою заявку до проверки — токен возвращается. */
    public boolean cancel(int id, UUID owner) throws SQLException {
        connection.setAutoCommit(false);
        try {
            TitleRecord t = getTitle(id);
            if (t == null || !t.owner().equals(owner) || t.status() != TitleStatus.PENDING
                    || changeStatus(id, TitleStatus.PENDING, TitleStatus.CANCELLED, null, null, true, false) == 0) {
                connection.rollback();
                return false;
            }
            addTokensRaw(owner, 1);
            connection.commit();
            return true;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Снятие одобренного титула: администрацией (REVOKED) или самим игроком (DELETED). */
    public RemoveResult remove(int id, UUID requiredOwner, TitleStatus newStatus, String reviewer, String reason)
            throws SQLException {
        connection.setAutoCommit(false);
        try {
            TitleRecord t = getTitle(id);
            if (t == null || t.status() != TitleStatus.APPROVED
                    || (requiredOwner != null && !t.owner().equals(requiredOwner))
                    || changeStatus(id, TitleStatus.APPROVED, newStatus, reviewer, reason, false,
                    newStatus == TitleStatus.REVOKED) == 0) {
                connection.rollback();
                return null;
            }
            boolean wasActive;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE ft_players SET active_title = NULL WHERE uuid = ? AND active_title = ?")) {
                ps.setString(1, t.owner().toString());
                ps.setInt(2, id);
                wasActive = ps.executeUpdate() > 0;
            }
            connection.commit();
            return new RemoveResult(getTitle(id), wasActive);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Надеть титул (id) или снять текущий (null). Возвращает надетый титул или null при ошибке. */
    public TitleRecord setActive(UUID uuid, Integer id) throws SQLException {
        TitleRecord t = null;
        if (id != null) {
            t = getTitle(id);
            if (t == null || !t.owner().equals(uuid) || t.status() != TitleStatus.APPROVED) {
                return null;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE ft_players SET active_title = ? WHERE uuid = ?")) {
            if (id == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setInt(1, id);
            }
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
        return t;
    }

    public void markNotified(List<Integer> ids) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE ft_titles SET notified = 1 WHERE id = ?")) {
            for (int id : ids) {
                ps.setInt(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
