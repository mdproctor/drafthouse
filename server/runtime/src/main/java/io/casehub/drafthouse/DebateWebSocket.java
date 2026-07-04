package io.casehub.drafthouse;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.blocks.channel.ContextSnapshot;
import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.websockets.next.*;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;

@WebSocket(path = "/api/ws")
public class DebateWebSocket {

    private static final Logger LOG = Logger.getLogger(DebateWebSocket.class.getName());

    @Inject WebSocketEventBus eventBus;
    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;
    @Inject DraftHouseConfig config;
    @Inject ObjectMapper mapper;

    private final ConcurrentHashMap<String, FileWatchHandle> activeFileWatches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocketConnection, Set<UUID>> connectionSessions = new ConcurrentHashMap<>();

    record FileWatchHandle(WatchService watchService, Thread watchThread) {}

    @OnOpen
    void onOpen(WebSocketConnection connection) {
        eventBus.register(connection);
        sendEvent(connection, "reconnected", Map.of());
        Collection<DebateEventResource.SessionInfo> sessions = registry.activeSessions().stream()
                .map(s -> new DebateEventResource.SessionInfo(
                        s.debateSessionId(), s.channelName(), s.primaryPath(), s.agentId()))
                .toList();
        sendEvent(connection, "sessions", sessions);
    }

    @OnTextMessage
    @RunOnVirtualThread
    void onMessage(WebSocketConnection connection, String message) {
        JsonNode node;
        try {
            node = mapper.readTree(message);
        } catch (Exception e) {
            LOG.warning("Malformed JSON from WebSocket client: " + e.getMessage());
            return;
        }

        String op = node.has("op") ? node.get("op").asText() : null;
        if (op == null) {
            LOG.warning("WebSocket message missing 'op' field");
            return;
        }

        String dataset = node.has("dataset") ? node.get("dataset").asText() : null;

        switch (op) {
            case "subscribe" -> handleSubscribe(connection, dataset);
            case "unsubscribe" -> handleUnsubscribe(connection, dataset);
            default -> LOG.warning("Unknown WebSocket op: " + op);
        }
    }

    @OnClose
    void onClose(WebSocketConnection connection) {
        cleanup(connection);
    }

    @OnError
    void onError(WebSocketConnection connection, Throwable error) {
        LOG.fine("WebSocket error: " + error.getMessage());
        cleanup(connection);
    }

    private void handleSubscribe(WebSocketConnection connection, String dataset) {
        if (dataset == null) return;

        if (dataset.startsWith("debate:")) {
            String sessionIdStr = dataset.substring("debate:".length());
            UUID channelId;
            try {
                channelId = UUID.fromString(sessionIdStr);
            } catch (IllegalArgumentException e) {
                LOG.warning("Invalid debate session UUID: " + sessionIdStr);
                return;
            }
            DebateSession session = registry.find(channelId).orElse(null);
            if (session == null) return;

            eventBus.watchSession(connection, channelId);
            connectionSessions.computeIfAbsent(connection, k -> ConcurrentHashMap.newKeySet()).add(channelId);
            sendCatchUp(connection, session, channelId);

        } else if (dataset.startsWith("file:")) {
            String path = dataset.substring("file:".length());
            if (!isPathAllowed(connection, path)) {
                LOG.warning("File watch rejected — path not in any watched session's document set: " + path);
                return;
            }
            eventBus.watchFile(connection, path);
            startFileWatch(path);
        }
        // Unrecognized dataset patterns are silently ignored (e.g. "_events" dummy subscription)
    }

    private void handleUnsubscribe(WebSocketConnection connection, String dataset) {
        if (dataset == null) return;

        if (dataset.startsWith("debate:")) {
            String sessionIdStr = dataset.substring("debate:".length());
            try {
                UUID channelId = UUID.fromString(sessionIdStr);
                eventBus.unwatchSession(connection, channelId);
                Set<UUID> sessions = connectionSessions.get(connection);
                if (sessions != null) sessions.remove(channelId);
            } catch (IllegalArgumentException e) {
                // ignore
            }
        } else if (dataset.startsWith("file:")) {
            String path = dataset.substring("file:".length());
            eventBus.unwatchFile(connection, path);
            stopFileWatchIfUnused(path);
        }
    }

    private void sendCatchUp(WebSocketConnection connection, DebateSession session, UUID channelId) {
        List<Message> messages = messageService.pollAfter(channelId, 0L, config.debate().catchUpLimit());
        List<DebateStreamEntry> entries = messages.stream()
                .map(DebateStreamEntry::from)
                .filter(Objects::nonNull)
                .toList();
        if (!entries.isEmpty()) {
            sendEvent(connection, "debate-entries", entries);
        }

        ContextSnapshot ctxSnapshot = session.contextTracker().snapshot(
                config.context().windowSizeChars(), config.context().thresholdPercent());
        var ctxMap = new HashMap<String, Object>();
        ctxMap.put("serverContributionChars", ctxSnapshot.serverContributionChars());
        ctxMap.put("windowSizeChars", ctxSnapshot.windowSizeChars());
        ctxMap.put("agentReportedPercent", ctxSnapshot.agentReportedPercent()); // null → JSON null via Jackson
        ctxMap.put("effectivePercent", ctxSnapshot.effectivePercent());
        ctxMap.put("messageCount", ctxSnapshot.messageCount());
        ctxMap.put("thresholdExceeded", ctxSnapshot.thresholdExceeded());
        sendEvent(connection, "context-usage", ctxMap);

        String docsJson = DocumentSetJson.documentsToJson(session.documents());
        sendEventRaw(connection, "documents-changed", "{\"documents\":" + docsJson + "}");

        ComparisonPair cp = session.currentComparison();
        if (cp != null) {
            sendEvent(connection, "comparison-changed", Map.of("pathA", cp.pathA(), "pathB", cp.pathB()));
        }
    }

    private boolean isPathAllowed(WebSocketConnection connection, String path) {
        // Allow if path is in any session this connection is watching
        Set<UUID> sessions = connectionSessions.get(connection);
        if (sessions == null || sessions.isEmpty()) return false;
        for (UUID channelId : sessions) {
            DebateSession session = registry.find(channelId).orElse(null);
            if (session != null) {
                for (DocumentEntry doc : session.documents()) {
                    if (path.equals(doc.path())) return true;
                }
            }
        }
        return false;
    }

    private void startFileWatch(String path) {
        try {
            Path target = Path.of(path);
            Path dir = target.getParent();
            String name = target.getFileName().toString();
            WatchService ws = FileSystems.getDefault().newWatchService();
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);

            Thread watchThread = Thread.ofVirtual().start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        WatchKey key = ws.poll(200, TimeUnit.MILLISECONDS);
                        if (key == null) continue;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (name.equals(event.context().toString())) {
                                eventBus.pushFileChanged(path);
                            }
                        }
                        if (!key.reset()) break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ClosedWatchServiceException ignored) {
                } finally {
                    try { ws.close(); } catch (IOException ignored) {}
                }
            });
            FileWatchHandle newHandle = new FileWatchHandle(ws, watchThread);
            FileWatchHandle existing = activeFileWatches.putIfAbsent(path, newHandle);
            if (existing != null) {
                // Another thread won the race — clean up our resources
                watchThread.interrupt();
                try { ws.close(); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            LOG.warning("Failed to start file watch for " + path + ": " + e.getMessage());
        }
    }

    private void stopFileWatchIfUnused(String path) {
        if (eventBus.hasFileWatchers(path)) return;
        FileWatchHandle handle = activeFileWatches.remove(path);
        if (handle != null) {
            handle.watchThread().interrupt();
            try { handle.watchService().close(); } catch (IOException ignored) {}
        }
    }

    private void cleanup(WebSocketConnection connection) {
        eventBus.unregister(connection);
        connectionSessions.remove(connection);
        // Check all file watches for cleanup
        for (String path : new ArrayList<>(activeFileWatches.keySet())) {
            stopFileWatchIfUnused(path);
        }
    }

    private void sendEvent(WebSocketConnection connection, String topic, Object payload) {
        try {
            String payloadJson = mapper.writeValueAsString(payload);
            String json = "{\"op\":\"event\",\"topic\":\"" + topic + "\",\"payload\":" + payloadJson + "}";
            connection.sendText(json).subscribe().with(v -> {}, err -> {
                LOG.fine("sendEvent failed: " + err.getMessage());
                eventBus.unregister(connection);
            });
        } catch (Exception e) {
            LOG.warning("Failed to serialize event '" + topic + "': " + e.getMessage());
        }
    }

    private void sendEventRaw(WebSocketConnection connection, String topic, String payloadJson) {
        String json = "{\"op\":\"event\",\"topic\":\"" + topic + "\",\"payload\":" + payloadJson + "}";
        connection.sendText(json).subscribe().with(v -> {}, err -> {
            LOG.fine("sendEventRaw failed: " + err.getMessage());
            eventBus.unregister(connection);
        });
    }
}
