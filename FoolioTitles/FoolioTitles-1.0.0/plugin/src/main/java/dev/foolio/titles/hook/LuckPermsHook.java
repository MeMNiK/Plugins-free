package dev.foolio.titles.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.SuffixNode;
import org.bukkit.configuration.ConfigurationSection;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Титул ставится игроку как суффикс LuckPerms с отдельным приоритетом.
 * Этот приоритет считается «принадлежащим» плагину: суффиксы с ним плагин
 * заменяет и удаляет сам, поэтому вручную его лучше не использовать.
 */
public final class LuckPermsHook {

    private final LuckPerms api;
    private int priority = 500;
    private String serverContext = "";

    public LuckPermsHook() {
        this.api = LuckPermsProvider.get();
    }

    public void load(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        priority = section.getInt("priority", 500);
        serverContext = section.getString("server-context", "");
        if (serverContext == null) {
            serverContext = "";
        }
    }

    public int priority() {
        return priority;
    }

    /** Поставить суффикс-титул (или убрать, если suffix == null). Работает и для офлайн-игроков. */
    public CompletableFuture<Void> setSuffix(UUID uuid, String suffix) {
        final int prio = priority;
        final String server = serverContext;
        return api.getUserManager().modifyUser(uuid, user -> {
            user.data().clear(NodeType.SUFFIX.predicate(node -> node.getPriority() == prio));
            if (suffix != null) {
                SuffixNode.Builder builder = SuffixNode.builder(suffix, prio);
                if (!server.isEmpty()) {
                    builder.withContext("server", server);
                }
                user.data().add(builder.build());
            }
        });
    }

    /** Текущий суффикс плагина у загруженного (онлайн) игрока; null — нет или игрок не загружен. */
    public String currentSuffix(UUID uuid) {
        User user = api.getUserManager().getUser(uuid);
        if (user == null) {
            return null;
        }
        final int prio = priority;
        return user.getNodes(NodeType.SUFFIX).stream()
                .filter(node -> node.getPriority() == prio)
                .map(SuffixNode::getMetaValue)
                .findFirst()
                .orElse(null);
    }

    public boolean isLoaded(UUID uuid) {
        return api.getUserManager().getUser(uuid) != null;
    }
}
