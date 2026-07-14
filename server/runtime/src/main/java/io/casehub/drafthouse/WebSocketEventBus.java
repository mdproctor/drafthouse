package io.casehub.drafthouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.pages.push.PushMessage;
import io.casehub.pages.push.TopicRegistry;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

@ApplicationScoped
public class WebSocketEventBus {

    private static final Logger LOG = Logger.getLogger(WebSocketEventBus.class.getName());

    private final TopicRegistry                                  topicRegistry = new TopicRegistry();
    private final ConcurrentHashMap<String, WebSocketConnection> connections   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocketConnection, String> connectionIds = new ConcurrentHashMap<>();
    private final AtomicLong                                     idCounter     = new AtomicLong();

    @Inject
    ObjectMapper mapper;

    public String register(WebSocketConnection conn) {
        String id = "conn-" + idCounter.incrementAndGet();
        connections.put(id, conn);
        connectionIds.put(conn, id);
        return id;
    }

    public void unregister(WebSocketConnection conn) {
        String id = connectionIds.remove(conn);
        if (id != null) {
            connections.remove(id);
            topicRegistry.removeConnection(id);
        }
    }

    public void watchSession(WebSocketConnection conn, UUID channelId) {
        String id = connectionIds.get(conn);
        if (id != null) {topicRegistry.listen(id, List.of("debate:" + channelId));}
    }

    public void unwatchSession(WebSocketConnection conn, UUID channelId) {
        String id = connectionIds.get(conn);
        if (id != null) {topicRegistry.unlisten(id, List.of("debate:" + channelId));}
    }

    public void watchFile(WebSocketConnection conn, String path) {
        String id = connectionIds.get(conn);
        if (id != null) {topicRegistry.listen(id, List.of("file:" + path));}
    }

    public void unwatchFile(WebSocketConnection conn, String path) {
        String id = connectionIds.get(conn);
        if (id != null) {topicRegistry.unlisten(id, List.of("file:" + path));}
    }


    public boolean hasFileWatchers(String path) {
        return !topicRegistry.connections("file:" + path).isEmpty();
    }


    public void watchBrainstorm(WebSocketConnection conn, String sessionId) {
        String id = connectionIds.get(conn);
        if (id != null) {topicRegistry.listen(id, List.of("brainstorm:" + sessionId));}
    }

    public void unwatchBrainstorm(WebSocketConnection conn, String sessionId) {
        String id = connectionIds.get(conn);
        if (id != null) {topicRegistry.unlisten(id, List.of("brainstorm:" + sessionId));}
    }

    public void pushBrainstormEvent(String sessionId, String topic, Object payload) {
        Set<String> connIds = topicRegistry.connections("brainstorm:" + sessionId);
        if (connIds.isEmpty()) {return;}
        String json = formatEvent(topic, payload);
        if (json == null) {return;}
        for (String connId : connIds) {
            WebSocketConnection conn = connections.get(connId);
            if (conn != null) {sendSafe(conn, json);}
        }
    }

    public void broadcast(String topic, Object payload) {
        String json = formatEvent(topic, payload);
        if (json == null) {return;}
        for (WebSocketConnection conn : connectionIds.keySet()) {
            sendSafe(conn, json);
        }
    }

    public void pushDebateEntries(UUID channelId, List<DebateStreamEntry> entries) {
        Set<String> connIds = topicRegistry.connections("debate:" + channelId);
        if (connIds.isEmpty()) {return;}
        String json = formatEvent("debate-entries", entries);
        if (json == null) {return;}
        for (String connId : connIds) {
            WebSocketConnection conn = connections.get(connId);
            if (conn != null) {sendSafe(conn, json);}
        }
    }

    public void pushMetadata(UUID channelId, String topic, Object payload) {
        Set<String> connIds = topicRegistry.connections("debate:" + channelId);
        if (connIds.isEmpty()) {return;}
        String json = formatEvent(topic, payload);
        if (json == null) {return;}
        for (String connId : connIds) {
            WebSocketConnection conn = connections.get(connId);
            if (conn != null) {sendSafe(conn, json);}
        }
    }

    public void pushFileChanged(String path) {
        Set<String> connIds = topicRegistry.connections("file:" + path);
        if (connIds.isEmpty()) {return;}
        String json = formatEvent("file-changed", java.util.Map.of("path", path));
        if (json == null) {return;}
        for (String connId : connIds) {
            WebSocketConnection conn = connections.get(connId);
            if (conn != null) {sendSafe(conn, json);}
        }
    }

    private String formatEvent(String topic, Object payload) {
        try {
            String payloadJson = mapper.writeValueAsString(payload);
            return PushMessage.event(topic, payloadJson);
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
        return topicRegistry.connections("debate:" + channelId).size();
    }

    int fileWatcherCount(String path) {
        return topicRegistry.connections("file:" + path).size();
    }

    int brainstormWatcherCount(String sessionId) {
        return topicRegistry.connections("brainstorm:" + sessionId).size();
    }


    int connectionCount() {
        return connections.size();
    }
}
