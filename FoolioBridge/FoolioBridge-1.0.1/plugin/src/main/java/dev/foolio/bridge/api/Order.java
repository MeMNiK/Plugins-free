// FoolioBridge - Development by DeJIoVoy
package dev.foolio.bridge.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One purchase the site wants delivered on the server. */
public final class Order {

    public final String id;
    public final String player;
    public final String uuid;        // optional
    public final boolean onlineOnly;  // deliver only while the player is online
    public final List<String> commands;
    public final String title;       // optional, shown in /donate
    public final String message;     // optional, sent to the player on delivery
    public final Map<String, String> vars;

    public Order(String id, String player, String uuid, boolean onlineOnly, List<String> commands,
                 String title, String message, Map<String, String> vars) {
        this.id = id;
        this.player = player;
        this.uuid = uuid;
        this.onlineOnly = onlineOnly;
        this.commands = Collections.unmodifiableList(commands);
        this.title = title;
        this.message = message;
        this.vars = Collections.unmodifiableMap(vars);
    }

    @SuppressWarnings("unchecked")
    public static Order fromJson(Object raw) {
        if (!(raw instanceof Map)) throw new IllegalArgumentException("order is not an object");
        Map<String, Object> m = (Map<String, Object>) raw;
        String id = str(m.get("id"));
        String player = str(m.get("player"));
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("order without id");
        if (id.length() > 64) throw new IllegalArgumentException("order id longer than 64 chars");
        if (player == null || player.isEmpty()) throw new IllegalArgumentException("order " + id + " without player");

        List<String> commands = new ArrayList<>();
        Object cmds = m.get("commands");
        if (cmds instanceof List) {
            for (Object c : (List<Object>) cmds) {
                String s = str(c);
                if (s != null && !s.trim().isEmpty()) commands.add(s.trim());
            }
        }
        if (commands.isEmpty()) throw new IllegalArgumentException("order " + id + " has no commands");

        Map<String, String> vars = new LinkedHashMap<>();
        Object v = m.get("vars");
        if (v instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                String val = str(e.getValue());
                if (val != null) vars.put(e.getKey(), val);
            }
        }

        Object oo = m.get("online_only");
        boolean onlineOnly = !(oo instanceof Boolean) || (Boolean) oo;

        return new Order(id, player, str(m.get("uuid")), onlineOnly, commands,
                str(m.get("title")), str(m.get("message")), vars);
    }

    /** Re-serialise for local storage. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("player", player);
        if (uuid != null) m.put("uuid", uuid);
        m.put("online_only", onlineOnly);
        m.put("commands", commands);
        if (title != null) m.put("title", title);
        if (message != null) m.put("message", message);
        if (!vars.isEmpty()) m.put("vars", vars);
        return m;
    }

    private static String str(Object o) {
        if (o == null) return null;
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
        }
        return o.toString();
    }
}
