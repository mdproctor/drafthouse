package io.casehub.drafthouse;

import java.util.Collection;
import java.util.UUID;

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

@ApplicationScoped
@Path("/api/debate")
public class DebateEventResource {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DebateEventResource.class.getName());

    @Inject DebateSessionRegistry registry;
    @Inject ObjectMapper mapper;
    @Inject WebSocketEventBus eventBus;

    record SessionInfo(String debateSessionId, String channelName, String specPath, String agentId) {}
    record SelectionRequest(String side, int startLine, int endLine, String selectedText) {}
    record ComparisonRequest(String pathA, String pathB) {}

    public void pushContextSnapshot(UUID channelId, ContextSnapshot snapshot) {
        try {
            var map = new java.util.HashMap<String, Object>();
            map.put("serverContributionChars", snapshot.serverContributionChars());
            map.put("agentReportedPercent", snapshot.agentReportedPercent());
            map.put("effectivePercent", snapshot.effectivePercent());
            map.put("messageCount", snapshot.messageCount());
            map.put("thresholdExceeded", snapshot.thresholdExceeded());
            eventBus.pushMetadata(channelId, "context-usage", map);
        } catch (Exception e) {
            LOG.warning("Failed to push context snapshot: " + e.getMessage());
        }
    }

    public void pushDocumentsChanged(UUID channelId, DebateSession session) {
        try {
            String docsJson = DocumentSetJson.documentsToJson(session.documents());
            eventBus.pushMetadata(channelId, "documents-changed",
                    mapper.readTree("{\"documents\":" + docsJson + "}"));
        } catch (Exception e) {
            LOG.warning("Failed to push documents-changed: " + e.getMessage());
        }
    }

    public void pushComparisonChanged(UUID channelId, ComparisonPair cp) {
        try {
            if (cp != null) {
                eventBus.pushMetadata(channelId, "comparison-changed",
                        java.util.Map.of("pathA", cp.pathA(), "pathB", cp.pathB()));
            } else {
                java.util.HashMap<String, String> nullMap = new java.util.HashMap<>();
                nullMap.put("pathA", null);
                nullMap.put("pathB", null);
                eventBus.pushMetadata(channelId, "comparison-changed", nullMap);
            }
        } catch (Exception e) {
            LOG.warning("Failed to push comparison-changed: " + e.getMessage());
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
        try {
            eventBus.pushMetadata(channelId, "selection-scope", java.util.Map.of("cleared", true));
        } catch (Exception e) {
            LOG.warning("Failed to push selection cleared: " + e.getMessage());
        }
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

    @GET
    @Path("/{debateSessionId}/snapshot/{index}")
    @Produces(MediaType.TEXT_PLAIN)
    public jakarta.ws.rs.core.Response snapshot(
            @PathParam("debateSessionId") String debateSessionId,
            @PathParam("index") int index) {
        UUID channelId = parseSessionId(debateSessionId);
        DebateSession session = registry.find(channelId)
                .orElse(null);
        if (session == null) {
            return jakarta.ws.rs.core.Response.status(404)
                    .entity("Session not found").build();
        }
        String content = session.snapshotContentAt(index);
        if (content == null) {
            return jakarta.ws.rs.core.Response.status(404)
                    .entity("Snapshot not found").build();
        }
        return jakarta.ws.rs.core.Response.ok(content).build();
    }

    private void pushSelectionEvent(UUID channelId, SelectionScope scope) {
        try {
            eventBus.pushMetadata(channelId, "selection-scope", java.util.Map.of(
                    "side", scope.side().name(),
                    "startLine", scope.startLine(),
                    "endLine", scope.endLine(),
                    "selectedText", scope.selectedText()
            ));
        } catch (Exception e) {
            LOG.warning("Failed to push selection event: " + e.getMessage());
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
}
