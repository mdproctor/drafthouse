package io.casehub.drafthouse;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WebSocketEventBus {

    private static final Logger LOG = Logger.getLogger(WebSocketEventBus.class.getName());

    private final Set<WebSocketConnection> allConnections = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<WebSocketConnection>> sessionWatchers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocketConnection>> fileWatchers = new ConcurrentHashMap<>();

    @Inject ObjectMapper mapper;

    public void register(WebSocketConnection conn) {
        allConnections.add(conn);
    }

    public void unregister(WebSocketConnection conn) {
        allConnections.remove(conn);
        sessionWatchers.values().forEach(set -> set.remove(conn));
        fileWatchers.values().forEach(set -> set.remove(conn));
    }

    public void watchSession(WebSocketConnection conn, UUID channelId) {
        sessionWatchers.computeIfAbsent(channelId, k -> new CopyOnWriteArraySet<>()).add(conn);
    }

    public void unwatchSession(WebSocketConnection conn, UUID channelId) {
        CopyOnWriteArraySet<WebSocketConnection> watchers = sessionWatchers.get(channelId);
        if (watchers != null) {
            watchers.remove(conn);
            if (watchers.isEmpty()) sessionWatchers.remove(channelId);
        }
    }

    public void watchFile(WebSocketConnection conn, String path) {
        fileWatchers.computeIfAbsent(path, k -> new CopyOnWriteArraySet<>()).add(conn);
    }

    public void unwatchFile(WebSocketConnection conn, String path) {
        CopyOnWriteArraySet<WebSocketConnection> watchers = fileWatchers.get(path);
        if (watchers != null) {
            watchers.remove(conn);
            if (watchers.isEmpty()) fileWatchers.remove(path);
        }
    }

    public boolean hasFileWatchers(String path) {
        CopyOnWriteArraySet<WebSocketConnection> watchers = fileWatchers.get(path);
        return watchers != null && !watchers.isEmpty();
    }

    public void broadcast(String topic, Object payload) {
        String json = formatEvent(topic, payload);
        if (json == null) return;
        for (WebSocketConnection conn : allConnections) {
            sendSafe(conn, json);
        }
    }

    public void pushDebateEntries(UUID channelId, java.util.List<DebateStreamEntry> entries) {
        CopyOnWriteArraySet<WebSocketConnection> watchers = sessionWatchers.get(channelId);
        if (watchers == null || watchers.isEmpty()) return;
        String json = formatEvent("debate-entries", entries);
        if (json == null) return;
        for (WebSocketConnection conn : watchers) {
            sendSafe(conn, json);
        }
    }

    public void pushMetadata(UUID channelId, String topic, Object payload) {
        CopyOnWriteArraySet<WebSocketConnection> watchers = sessionWatchers.get(channelId);
        if (watchers == null || watchers.isEmpty()) return;
        String json = formatEvent(topic, payload);
        if (json == null) return;
        for (WebSocketConnection conn : watchers) {
            sendSafe(conn, json);
        }
    }

    public void pushFileChanged(String path) {
        CopyOnWriteArraySet<WebSocketConnection> watchers = fileWatchers.get(path);
        if (watchers == null || watchers.isEmpty()) return;
        String json = formatEvent("file-changed", java.util.Map.of("path", path));
        if (json == null) return;
        for (WebSocketConnection conn : watchers) {
            sendSafe(conn, json);
        }
    }

    private String formatEvent(String topic, Object payload) {
        try {
            String payloadJson = mapper.writeValueAsString(payload);
            return "{\"op\":\"event\",\"topic\":\"" + topic + "\",\"payload\":" + payloadJson + "}";
        } catch (Exception e) {
            LOG.warning("Failed to format event '" + topic + "': " + e.getMessage());
            return null;
        }
    }

    private void sendSafe(WebSocketConnection conn, String json) {
        conn.sendText(json).subscribe().with(v -> {}, err -> {
            LOG.fine("sendText failed — removing dead connection: " + err.getMessage());
            unregister(conn);
        });
    }

    // Test visibility
    int sessionWatcherCount(UUID channelId) {
        CopyOnWriteArraySet<WebSocketConnection> w = sessionWatchers.get(channelId);
        return w == null ? 0 : w.size();
    }

    int fileWatcherCount(String path) {
        CopyOnWriteArraySet<WebSocketConnection> w = fileWatchers.get(path);
        return w == null ? 0 : w.size();
    }

    int connectionCount() {
        return allConnections.size();
    }
}
