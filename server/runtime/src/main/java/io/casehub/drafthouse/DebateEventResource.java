package io.casehub.drafthouse;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.blocks.channel.ContextSnapshot;
import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
@Path("/api/debate")
public class DebateEventResource {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DebateEventResource.class.getName());

    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;
    @Inject DraftHouseConfig config;
    @Inject ObjectMapper mapper;

    private final ConcurrentHashMap<UUID, String> pendingContextSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> pendingSelections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> pendingDocuments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> pendingComparisons = new ConcurrentHashMap<>();

    record SessionInfo(String debateSessionId, String channelName, String specPath, String agentId) {}
    record SelectionRequest(String side, int startLine, int endLine, String selectedText) {}
    record ComparisonRequest(String pathA, String pathB) {}

    public void pushContextSnapshot(UUID channelId, ContextSnapshot snapshot) {
        try {
            String json = serializeContextSnapshot(snapshot, false);
            if (json != null) {
                pendingContextSnapshots.put(channelId, json);
            }
        } catch (Exception e) {
            LOG.warning("Failed to serialize context snapshot: " + e.getMessage());
        }
    }

    public void pushDocumentsChanged(UUID channelId, DebateSession session) {
        try {
            String json = "{\"type\":\"documents-changed\",\"documents\":"
                    + DocumentSetJson.documentsToJson(session.documents()) + "}";
            pendingDocuments.put(channelId, json);
        } catch (Exception e) {
            LOG.warning("Failed to build documents-changed JSON: " + e.getMessage());
        }
    }

    public void pushComparisonChanged(UUID channelId, ComparisonPair cp) {
        try {
            String json;
            if (cp != null) {
                json = "{\"type\":\"comparison-changed\",\"pathA\":\"" + escapeJson(cp.pathA())
                        + "\",\"pathB\":\"" + escapeJson(cp.pathB()) + "\"}";
            } else {
                json = "{\"type\":\"comparison-changed\",\"pathA\":null,\"pathB\":null}";
            }
            pendingComparisons.put(channelId, json);
        } catch (Exception e) {
            LOG.warning("Failed to build comparison-changed JSON: " + e.getMessage());
        }
    }

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<SessionInfo> activeSessions() {
        return registry.activeSessions().stream()
                .map(s -> new SessionInfo(s.debateSessionId(), s.channelName(), s.primaryPath(), s.agentId()))
                .toList();
    }

    @GET
    @Path("/{debateSessionId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @io.smallrye.common.annotation.Blocking
    public Multi<String> events(@PathParam("debateSessionId") String debateSessionId) {
        UUID channelId = parseSessionId(debateSessionId);
        DebateSession session = registry.find(channelId)
                .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

        AtomicLong lastSentId = new AtomicLong(0L);

        Multi<String> initialContext = Multi.createFrom().item(
                () -> serializeContextSnapshot(
                        session.contextTracker().snapshot(
                                config.context().windowSizeChars(),
                                config.context().thresholdPercent()),
                        true)
        ).filter(Objects::nonNull);

        Multi<String> catchUp = Multi.createFrom().uni(
                Uni.createFrom().item(() -> {
                    List<Message> messages = messageService.pollAfter(channelId, 0L, 500);
                    return serializeMessages(messages, lastSentId);
                })
        ).filter(Objects::nonNull);

        Multi<String> live = Multi.createFrom().ticks().every(Duration.ofMillis(500))
                .onItem().transformToMultiAndConcatenate(tick -> {
                    java.util.ArrayList<String> items = new java.util.ArrayList<>();

                    String pendingCtx = pendingContextSnapshots.remove(channelId);
                    if (pendingCtx != null) items.add(pendingCtx);

                    String pendingSel = pendingSelections.remove(channelId);
                    if (pendingSel != null) items.add(pendingSel);

                    String pendingDoc = pendingDocuments.remove(channelId);
                    if (pendingDoc != null) items.add(pendingDoc);

                    String pendingComp = pendingComparisons.remove(channelId);
                    if (pendingComp != null) items.add(pendingComp);

                    try {
                        List<Message> messages = messageService.pollAfter(
                                channelId, lastSentId.get(), 50);
                        String entries = messages.isEmpty() ? null
                                : serializeMessages(messages, lastSentId);
                        if (entries != null) items.add(entries);
                    } catch (Exception e) {
                        LOG.warning("SSE tick failed for " + debateSessionId + ": " + e.getMessage());
                    }

                    if (items.isEmpty()) items.add("{\"type\":\"heartbeat\"}");
                    return Multi.createFrom().iterable(items);
                });

        return Multi.createBy().concatenating().streams(initialContext, catchUp, live);
    }

    private String serializeMessages(List<Message> messages, AtomicLong lastSentId) {
        List<DebateStreamEntry> entries = messages.stream()
                .map(DebateStreamEntry::from)
                .filter(Objects::nonNull)
                .toList();

        if (!messages.isEmpty()) {
            long maxId = messages.stream()
                    .mapToLong(m -> m.id())
                    .max()
                    .orElse(lastSentId.get());
            lastSentId.set(maxId);
        }

        if (entries.isEmpty()) return null;

        try {
            return mapper.writeValueAsString(entries);
        } catch (Exception e) {
            LOG.warning("Failed to serialize debate events: " + e.getMessage());
            return null;
        }
    }

    @POST
    @Path("/{debateSessionId}/selection")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response postSelection(
            @PathParam("debateSessionId") String debateSessionId,
            SelectionRequest request) {
        UUID channelId = parseSessionId(debateSessionId);
        DebateSession session = registry.find(channelId)
                .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

        DocumentSide side;
        try {
            side = DocumentSide.valueOf(request.side());
        } catch (IllegalArgumentException | NullPointerException e) {
            return jakarta.ws.rs.core.Response.status(400)
                    .entity("{\"error\":\"invalid side: " + request.side() + "\"}").build();
        }

        SelectionScope scope;
        try {
            scope = new SelectionScope(side, request.startLine(), request.endLine(),
                    request.selectedText());
        } catch (IllegalArgumentException e) {
            return jakarta.ws.rs.core.Response.status(400)
                    .entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}").build();
        }
        session.updateSelection(scope);
        pushSelectionEvent(channelId, scope);
        return jakarta.ws.rs.core.Response.ok("{\"status\":\"ok\"}").build();
    }

    @DELETE
    @Path("/{debateSessionId}/selection")
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response deleteSelection(
            @PathParam("debateSessionId") String debateSessionId) {
        UUID channelId = parseSessionId(debateSessionId);
        DebateSession session = registry.find(channelId)
                .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

        session.updateSelection(null);
        pendingSelections.put(channelId, "{\"type\":\"selection-scope\",\"cleared\":true}");
        return jakarta.ws.rs.core.Response.ok("{\"status\":\"ok\"}").build();
    }

    @GET
    @Path("/{debateSessionId}/documents")
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response getDocuments(
            @PathParam("debateSessionId") String debateSessionId) {
        UUID channelId = parseSessionId(debateSessionId);
        DebateSession session = registry.find(channelId)
                .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

        return jakarta.ws.rs.core.Response.ok(
                DocumentSetJson.documentsAndComparisonToJson(session)).build();
    }

    @POST
    @Path("/{debateSessionId}/comparison")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response postComparison(
            @PathParam("debateSessionId") String debateSessionId,
            ComparisonRequest request) {
        UUID channelId = parseSessionId(debateSessionId);
        DebateSession session = registry.find(channelId)
                .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

        try {
            session.setComparison(request.pathA(), request.pathB());
            registry.persist(session);
        } catch (IllegalArgumentException e) {
            return jakarta.ws.rs.core.Response.status(400)
                    .entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}").build();
        }
        pushComparisonChanged(channelId, session.currentComparison());
        return jakarta.ws.rs.core.Response.ok("{\"status\":\"ok\"}").build();
    }

    private void pushSelectionEvent(UUID channelId, SelectionScope scope) {
        try {
            String json = "{\"type\":\"selection-scope\""
                    + ",\"side\":\"" + scope.side().name() + "\""
                    + ",\"startLine\":" + scope.startLine()
                    + ",\"endLine\":" + scope.endLine()
                    + ",\"selectedText\":\"" + escapeJson(scope.selectedText()) + "\""
                    + "}";
            pendingSelections.put(channelId, json);
        } catch (Exception e) {
            LOG.warning("Failed to build selection event JSON: " + e.getMessage());
        }
    }

    private UUID parseSessionId(String debateSessionId) {
        try {
            return UUID.fromString(debateSessionId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Invalid session id: " + debateSessionId);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String serializeContextSnapshot(ContextSnapshot snapshot, boolean includeWindowSize) {
        try {
            StringBuilder sb = new StringBuilder("{\"type\":\"context-usage\"");
            sb.append(",\"serverContributionChars\":").append(snapshot.serverContributionChars());
            if (includeWindowSize) {
                sb.append(",\"windowSizeChars\":").append(snapshot.windowSizeChars());
            }
            if (snapshot.agentReportedPercent() != null) {
                sb.append(",\"agentReportedPercent\":").append(snapshot.agentReportedPercent());
            } else {
                sb.append(",\"agentReportedPercent\":null");
            }
            sb.append(",\"effectivePercent\":").append(snapshot.effectivePercent());
            sb.append(",\"messageCount\":").append(snapshot.messageCount());
            sb.append(",\"thresholdExceeded\":").append(snapshot.thresholdExceeded());
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            LOG.warning("Failed to build context snapshot JSON: " + e.getMessage());
            return null;
        }
    }
}
