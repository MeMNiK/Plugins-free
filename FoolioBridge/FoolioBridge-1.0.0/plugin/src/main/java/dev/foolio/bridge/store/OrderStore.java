package dev.foolio.bridge.store;

import dev.foolio.bridge.api.Json;
import dev.foolio.bridge.api.Order;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local, crash-safe order queue.
 *
 * queue.json   - orders that are waiting for delivery or whose result hasn't been confirmed by the site yet,
 *                plus ids of recently finished orders (to never deliver the same order twice).
 * history.jsonl - append-only log of deliveries, used by /donate.
 *
 * The queue file is written atomically BEFORE commands run, so a crash can never cause a double delivery.
 */
public final class OrderStore {

    public static final String PENDING = "pending";
    public static final String DELIVERED = "delivered";
    public static final String FAILED = "failed";

    private static final long DONE_KEEP_MS = 30L * 24 * 3600 * 1000;

    private final Path queueFile;
    private final Path historyFile;
    private final Map<String, Record> active = new LinkedHashMap<>();
    private final Map<String, Long> done = new LinkedHashMap<>();

    public OrderStore(Path dataFolder) throws IOException {
        Files.createDirectories(dataFolder);
        this.queueFile = dataFolder.resolve("queue.json");
        this.historyFile = dataFolder.resolve("history.jsonl");
        load();
    }

    public static final class Record {
        public final Order order;
        public String status;
        public final long receivedAt;
        public long deliveredAt;
        public List<String> errors = new ArrayList<>();
        public boolean acked;

        Record(Order order, String status, long receivedAt) {
            this.order = order;
            this.status = status;
            this.receivedAt = receivedAt;
        }
    }

    // ---------------------------------------------------------------- queries

    /**
     * Adds an order fetched from the site. Returns true if it is new.
     * If it is already finished locally, its result is queued for re-sending to the site.
     */
    public synchronized boolean offer(Order order) throws IOException {
        Record r = active.get(order.id);
        if (r != null) {
            if (!PENDING.equals(r.status) && r.acked) {
                r.acked = false;
                save();
            }
            return false;
        }
        if (done.containsKey(order.id)) {
            // Site keeps sending an order we finished and it confirmed - remind it again.
            Record again = new Record(order, DELIVERED, System.currentTimeMillis());
            again.deliveredAt = done.get(order.id);
            again.errors.add("duplicate: already delivered earlier");
            active.put(order.id, again);
            done.remove(order.id);
            save();
            return false;
        }
        active.put(order.id, new Record(order, PENDING, System.currentTimeMillis()));
        save();
        return true;
    }

    public synchronized List<Order> pending() {
        List<Order> out = new ArrayList<>();
        for (Record r : active.values()) if (PENDING.equals(r.status)) out.add(r.order);
        return out;
    }

    public synchronized boolean isPending(String id) {
        Record r = active.get(id);
        return r != null && PENDING.equals(r.status);
    }

    /** Persists the final status. Must be called BEFORE the commands are executed. */
    public synchronized void markFinished(String id, String status, List<String> errors) throws IOException {
        Record r = active.get(id);
        if (r == null) return;
        r.status = status;
        r.deliveredAt = System.currentTimeMillis();
        r.errors = new ArrayList<>(errors);
        r.acked = false;
        save();
        appendHistory(r);
    }

    /** Adds errors discovered while executing commands (after markFinished). */
    public synchronized void addErrors(String id, List<String> errors) throws IOException {
        if (errors.isEmpty()) return;
        Record r = active.get(id);
        if (r == null) return;
        r.errors.addAll(errors);
        save();
    }

    /** Results the site hasn't confirmed yet. */
    public synchronized List<Map<String, Object>> unackedResults() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Record r : active.values()) {
            if (PENDING.equals(r.status) || r.acked) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.order.id);
            m.put("player", r.order.player);
            m.put("status", r.status);
            m.put("delivered_at", r.deliveredAt);
            m.put("errors", r.errors);
            out.add(m);
        }
        return out;
    }

    public synchronized void markAcked(List<String> ids) throws IOException {
        boolean changed = false;
        for (String id : ids) {
            Record r = active.get(id);
            if (r == null || PENDING.equals(r.status)) continue;
            active.remove(id);
            done.put(id, r.deliveredAt);
            changed = true;
        }
        if (changed) save();
    }

    public synchronized int countPending() {
        int n = 0;
        for (Record r : active.values()) if (PENDING.equals(r.status)) n++;
        return n;
    }

    public synchronized int countUnacked() {
        int n = 0;
        for (Record r : active.values()) if (!PENDING.equals(r.status) && !r.acked) n++;
        return n;
    }

    /** Last deliveries for a player, newest first. Reads the history file; call it off the main thread. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> history(String player, int limit) throws IOException {
        String lc = player.toLowerCase(Locale.ROOT);
        Deque<Map<String, Object>> last = new ArrayDeque<>();
        if (!Files.exists(historyFile)) return new ArrayList<>();
        synchronized (this) {
            try (BufferedReader in = Files.newBufferedReader(historyFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Object o;
                    try { o = Json.parse(line); } catch (IllegalArgumentException e) { continue; }
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) o;
                    if (!lc.equals(m.get("player_lc"))) continue;
                    last.addFirst(m);
                    if (last.size() > limit) last.removeLast();
                }
            }
        }
        return new ArrayList<>(last);
    }

    // ---------------------------------------------------------------- persistence

    private void appendHistory(Record r) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.order.id);
        m.put("player", r.order.player);
        m.put("player_lc", r.order.player.toLowerCase(Locale.ROOT));
        m.put("title", r.order.title);
        m.put("status", r.status);
        m.put("at", r.deliveredAt);
        try (BufferedWriter w = Files.newBufferedWriter(historyFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(Json.write(m));
            w.write('\n');
        }
    }

    private void save() throws IOException {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Long>> it = done.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > DONE_KEEP_MS) it.remove();
        }
        List<Object> list = new ArrayList<>();
        for (Record r : active.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order", r.order.toJson());
            m.put("status", r.status);
            m.put("received_at", r.receivedAt);
            m.put("delivered_at", r.deliveredAt);
            m.put("errors", r.errors);
            m.put("acked", r.acked);
            list.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("active", list);
        root.put("done", done);

        Path tmp = queueFile.resolveSibling("queue.json.tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, queueFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, queueFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        if (!Files.exists(queueFile)) return;
        Object parsed = Json.parse(Files.readString(queueFile, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map)) throw new IOException("queue.json is corrupted");
        Map<String, Object> root = (Map<String, Object>) parsed;
        Object act = root.get("active");
        if (act instanceof List) {
            for (Object o : (List<Object>) act) {
                Map<String, Object> m = (Map<String, Object>) o;
                Order order = Order.fromJson(m.get("order"));
                Record r = new Record(order, String.valueOf(m.get("status")), num(m.get("received_at")));
                r.deliveredAt = num(m.get("delivered_at"));
                Object errs = m.get("errors");
                if (errs instanceof List) for (Object e : (List<Object>) errs) r.errors.add(String.valueOf(e));
                r.acked = Boolean.TRUE.equals(m.get("acked"));
                active.put(order.id, r);
            }
        }
        Object d = root.get("done");
        if (d instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) d).entrySet()) {
                done.put(e.getKey(), num(e.getValue()));
            }
        }
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }
}
