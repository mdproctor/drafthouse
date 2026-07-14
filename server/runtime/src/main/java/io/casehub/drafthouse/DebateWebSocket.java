package io.casehub.drafthouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.blocks.channel.ContextSnapshot;
import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.pages.push.PushMessage;
import io.casehub.pages.push.PushRequest;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@WebSocket(path = "/api/ws")
public class DebateWebSocket {

    private static final Logger LOG = Logger.getLogger(DebateWebSocket.class.getName());
    private final ConcurrentHashMap<String, FileWatchHandle>        activeFileWatches  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocketConnection, Set<UUID>> connectionSessions = new ConcurrentHashMap<>();
    @Inject
    WebSocketEventBus     eventBus;
    @Inject
    DebateSessionRegistry registry;
    @Inject
    MessageService        messageService;
    @Inject
    DraftHouseConfig      config;
    @Inject
    ObjectMapper          mapper;
    @Inject
    BrainstormSessionRegistry brainstormRegistry;

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
        PushRequest request;
        try {
            request = PushRequest.parse(message);
        } catch (IllegalArgumentException e) {
            LOG.warning("Malformed push request: " + e.getMessage());
            return;
        }

        switch (request) {
            case PushRequest.Subscribe sub -> handleSubscribe(connection, sub.id(), sub.dataset());
            case PushRequest.Unsubscribe unsub -> handleUnsubscribe(connection, unsub.id(), unsub.dataset());
            case PushRequest.Listen listen -> handleListen(connection, listen.id(), listen.topics());
            case PushRequest.Unlisten unlisten -> handleUnlisten(connection, unlisten.id(), unlisten.topics());
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

    private void handleSubscribe(WebSocketConnection connection, String requestId, String dataset) {
        if (dataset == null) {
            sendSafe(connection, PushMessage.error(requestId, "missing dataset"));
            return;
        }

        if (dataset.startsWith("debate:")) {
            String sessionIdStr = dataset.substring("debate:".length());
            UUID   channelId;
            try {
                channelId = UUID.fromString(sessionIdStr);
            } catch (IllegalArgumentException e) {
                sendSafe(connection, PushMessage.error(requestId, "invalid session UUID"));
                return;
            }
            DebateSession session = registry.find(channelId).orElse(null);
            if (session == null) {
                sendSafe(connection, PushMessage.error(requestId, "session not found"));
                return;
            }

            eventBus.watchSession(connection, channelId);
            connectionSessions.computeIfAbsent(connection, k -> ConcurrentHashMap.newKeySet()).add(channelId);
            sendSafe(connection, PushMessage.ack(requestId));
            sendCatchUp(connection, session, channelId);

        } else if (dataset.startsWith("brainstorm:")) {
            String            sessionId = dataset.substring("brainstorm:".length());
            BrainstormSession session   = brainstormRegistry.find(sessionId).orElse(null);
            if (session == null) {
                sendSafe(connection, PushMessage.error(requestId, "brainstorm session not found"));
                return;
            }
            eventBus.watchBrainstorm(connection, sessionId);
            sendSafe(connection, PushMessage.ack(requestId));
            sendBrainstormCatchUp(connection, session);

        } else if (dataset.startsWith("file:")) {
            String path = dataset.substring("file:".length());
            if (!isPathAllowed(connection, path)) {
                LOG.warning("File watch rejected — path not in any watched session's document set: " + path);
                sendSafe(connection, PushMessage.error(requestId, "path not allowed"));
                return;
            }
            eventBus.watchFile(connection, path);
            startFileWatch(path);
            sendSafe(connection, PushMessage.ack(requestId));
        } else {
            sendSafe(connection, PushMessage.ack(requestId));
        }
    }

    private void handleUnsubscribe(WebSocketConnection connection, String requestId, String dataset) {
        if (dataset == null) {return;}

        if (dataset.startsWith("debate:")) {
            String sessionIdStr = dataset.substring("debate:".length());
            try {
                UUID channelId = UUID.fromString(sessionIdStr);
                eventBus.unwatchSession(connection, channelId);
                Set<UUID> sessions = connectionSessions.get(connection);
                if (sessions != null) {sessions.remove(channelId);}
            } catch (IllegalArgumentException e) {
                // ignore
            }
        } else if (dataset.startsWith("brainstorm:")) {
            String sessionId = dataset.substring("brainstorm:".length());
            eventBus.unwatchBrainstorm(connection, sessionId);
        } else if (dataset.startsWith("file:")) {
            String path = dataset.substring("file:".length());
            eventBus.unwatchFile(connection, path);
            stopFileWatchIfUnused(path);
        }
        sendSafe(connection, PushMessage.ack(requestId));
    }

    private void handleListen(WebSocketConnection connection, String requestId, List<String> topics) {
        for (String topic : topics) {
            if (topic.startsWith("debate:")) {
                String sessionIdStr = topic.substring("debate:".length());
                try {
                    UUID channelId = UUID.fromString(sessionIdStr);
                    eventBus.watchSession(connection, channelId);
                    connectionSessions.computeIfAbsent(connection, k -> ConcurrentHashMap.newKeySet()).add(channelId);
                    DebateSession session = registry.find(channelId).orElse(null);
                    if (session != null) {sendCatchUp(connection, session, channelId);}
                } catch (IllegalArgumentException ignored) {}
            } else if (topic.startsWith("brainstorm:")) {
                String sessionId = topic.substring("brainstorm:".length());
                eventBus.watchBrainstorm(connection, sessionId);
                BrainstormSession session = brainstormRegistry.find(sessionId).orElse(null);
                if (session != null) {sendBrainstormCatchUp(connection, session);}
            } else if (topic.startsWith("file:")) {
                String path = topic.substring("file:".length());
                if (isPathAllowed(connection, path)) {
                    eventBus.watchFile(connection, path);
                    startFileWatch(path);
                }
            }
        }
        sendSafe(connection, PushMessage.ack(requestId, topics));
    }

    private void handleUnlisten(WebSocketConnection connection, String requestId, List<String> topics) {
        for (String topic : topics) {
            if (topic.startsWith("debate:")) {
                String sessionIdStr = topic.substring("debate:".length());
                try {
                    UUID channelId = UUID.fromString(sessionIdStr);
                    eventBus.unwatchSession(connection, channelId);
                    Set<UUID> sessions = connectionSessions.get(connection);
                    if (sessions != null) {sessions.remove(channelId);}
                } catch (IllegalArgumentException ignored) {}
            } else if (topic.startsWith("brainstorm:")) {
                String sessionId = topic.substring("brainstorm:".length());
                eventBus.unwatchBrainstorm(connection, sessionId);
            } else if (topic.startsWith("file:")) {
                String path = topic.substring("file:".length());
                eventBus.unwatchFile(connection, path);
                stopFileWatchIfUnused(path);
            }
        }
        sendSafe(connection, PushMessage.ack(requestId));
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
        ctxMap.put("agentReportedPercent", ctxSnapshot.agentReportedPercent());
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

    private void sendBrainstormCatchUp(WebSocketConnection connection, BrainstormSession session) {
        if (!session.options().isEmpty()) {
            var optionMaps = session.options().stream().map(o -> Map.of(
                    "id", o.id(),
                    "title", o.title(),
                    "description", o.description(),
                    "tradeoffs", o.tradeoffs(),
                    "status", o.status().name()
                                                                       )).toList();
            sendEvent(connection, "brainstorm-options", Map.of(
                    "sessionId", session.sessionId(),
                    "options", optionMaps,
                    "state", session.state().name()));
        }
    }

    private boolean isPathAllowed(WebSocketConnection connection, String path) {
        Set<UUID> sessions = connectionSessions.get(connection);
        if (sessions == null || sessions.isEmpty()) {return false;}
        for (UUID channelId : sessions) {
            DebateSession session = registry.find(channelId).orElse(null);
            if (session != null) {
                for (DocumentEntry doc : session.documents()) {
                    if (path.equals(doc.path())) {return true;}
                }
            }
        }
        return false;
    }

    private void startFileWatch(String path) {
        try {
            Path         target = Path.of(path);
            Path         dir    = target.getParent();
            String       name   = target.getFileName().toString();
            WatchService ws     = FileSystems.getDefault().newWatchService();
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);

            Thread watchThread = Thread.ofVirtual().start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        WatchKey key = ws.poll(200, TimeUnit.MILLISECONDS);
                        if (key == null) {continue;}
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (name.equals(event.context().toString())) {
                                eventBus.pushFileChanged(path);
                            }
                        }
                        if (!key.reset()) {break;}
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ClosedWatchServiceException ignored) {
                } finally {
                    try {ws.close();} catch (IOException ignored) {}
                }
            });
            FileWatchHandle newHandle = new FileWatchHandle(ws, watchThread);
            FileWatchHandle existing  = activeFileWatches.putIfAbsent(path, newHandle);
            if (existing != null) {
                watchThread.interrupt();
                try {ws.close();} catch (IOException ignored) {}
            }
        } catch (IOException e) {
            LOG.warning("Failed to start file watch for " + path + ": " + e.getMessage());
        }
    }

    private void stopFileWatchIfUnused(String path) {
        if (eventBus.hasFileWatchers(path)) {return;}
        FileWatchHandle handle = activeFileWatches.remove(path);
        if (handle != null) {
            handle.watchThread().interrupt();
            try {handle.watchService().close();} catch (IOException ignored) {}
        }
    }

    private void cleanup(WebSocketConnection connection) {
        eventBus.unregister(connection);
        connectionSessions.remove(connection);
        for (String path : new ArrayList<>(activeFileWatches.keySet())) {
            stopFileWatchIfUnused(path);
        }
    }

    private void sendEvent(WebSocketConnection connection, String topic, Object payload) {
        try {
            String payloadJson = mapper.writeValueAsString(payload);
            sendSafe(connection, PushMessage.event(topic, payloadJson));
        } catch (Exception e) {
            LOG.warning("Failed to serialize event '" + topic + "': " + e.getMessage());
        }
    }

    private void sendEventRaw(WebSocketConnection connection, String topic, String payloadJson) {
        sendSafe(connection, PushMessage.event(topic, payloadJson));
    }

    private void sendSafe(WebSocketConnection connection, String json) {
        connection.sendText(json).subscribe().with(v -> {}, err -> {
            LOG.fine("sendText failed: " + err.getMessage());
            eventBus.unregister(connection);
        });
    }

    record FileWatchHandle(WatchService watchService, Thread watchThread) {}
}
