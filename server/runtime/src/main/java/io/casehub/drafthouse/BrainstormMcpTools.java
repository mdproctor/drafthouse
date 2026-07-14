package io.casehub.drafthouse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

public class BrainstormMcpTools {

    private static final Logger LOG = Logger.getLogger(BrainstormMcpTools.class.getName());

    @Inject BrainstormSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;
    @Inject ObjectMapper mapper;

    @Tool(name = "start_brainstorm",
          description = "Start a brainstorming session. Returns JSON with sessionId.")
    public String startBrainstorm() {
        String sessionId = "bs-" + UUID.randomUUID();
        BrainstormSession session = new BrainstormSession(sessionId);
        registry.put(session);

        eventBus.broadcast("brainstorm-session-created", Map.of("sessionId", sessionId));

        return "{\"sessionId\":\"" + sessionId + "\"}";
    }

    @Tool(name = "present_options",
          description = "Present 2-4 brainstorming options. Options is a JSON array with objects containing id, title, description, tradeoffs.")
    public String presentOptions(
            @ToolArg(description = "Brainstorm session ID from start_brainstorm") String sessionId,
            @ToolArg(description = "JSON array of options: [{id, title, description, tradeoffs}, ...]") String optionsJson) {

        BrainstormSession session = resolveSession(sessionId);
        if (session == null) return sessionError(sessionId);

        List<Map<String, String>> optionMaps;
        try {
            optionMaps = mapper.readValue(optionsJson, new TypeReference<>() {});
        } catch (Exception e) {
            return "error: invalid options JSON — " + e.getMessage();
        }

        try {
            for (Map<String, String> om : optionMaps) {
                String id = om.get("id");
                String title = om.get("title");
                String description = om.get("description");
                String tradeoffs = om.get("tradeoffs");
                if (id == null || title == null) {
                    return "error: each option must have at least 'id' and 'title'";
                }
                session.addOption(new BrainstormOption(id, title,
                        description != null ? description : "",
                        tradeoffs != null ? tradeoffs : ""));
            }
        } catch (IllegalStateException e) {
            return "error: " + e.getMessage();
        }

        session.touch();
        pushOptionsEvent(session);
        return "presented " + optionMaps.size() + " option(s)";
    }

    @Tool(name = "update_option",
          description = "Enrich an option with exploration results. Sets status to EXPLORED.")
    public String updateOption(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID to update") String optionId,
            @ToolArg(description = "Updated description with exploration findings") String description,
            @ToolArg(description = "Updated tradeoffs") String tradeoffs) {

        BrainstormSession session = resolveSession(sessionId);
        if (session == null) return sessionError(sessionId);

        BrainstormOption option = session.findOption(optionId).orElse(null);
        if (option == null) return "error: unknown option '" + optionId + "'";

        option.setDescription(description);
        option.setTradeoffs(tradeoffs);
        option.setStatus(BrainstormOption.Status.EXPLORED);
        session.touch();
        pushOptionsEvent(session);
        return "updated option '" + optionId + "'";
    }

    @Tool(name = "set_recommendation",
          description = "Mark one option as the recommended choice.")
    public String setRecommendation(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID to recommend") String optionId) {

        BrainstormSession session = resolveSession(sessionId);
        if (session == null) return sessionError(sessionId);

        BrainstormOption option = session.findOption(optionId).orElse(null);
        if (option == null) return "error: unknown option '" + optionId + "'";

        option.setStatus(BrainstormOption.Status.RECOMMENDED);
        session.touch();
        pushOptionsEvent(session);
        return "recommended option '" + optionId + "'";
    }

    @Tool(name = "mark_eliminated",
          description = "Mark an option as eliminated (e.g. after a challenge reveals a fatal flaw).")
    public String markEliminated(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID to eliminate") String optionId) {

        BrainstormSession session = resolveSession(sessionId);
        if (session == null) return sessionError(sessionId);

        BrainstormOption option = session.findOption(optionId).orElse(null);
        if (option == null) return "error: unknown option '" + optionId + "'";

        option.setStatus(BrainstormOption.Status.ELIMINATED);
        session.touch();
        pushOptionsEvent(session);
        return "eliminated option '" + optionId + "'";
    }

    @Tool(name = "mark_selected",
          description = "Mark the final option selection. Converges the session.")
    public String markSelected(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID selected") String optionId) {

        BrainstormSession session = resolveSession(sessionId);
        if (session == null) return sessionError(sessionId);

        try {
            session.markSelected(optionId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }

        pushConvergedEvent(session);
        return "selected option '" + optionId + "'";
    }

    @Tool(name = "end_brainstorm",
          description = "End a brainstorming session.")
    public String endBrainstorm(
            @ToolArg(description = "Brainstorm session ID") String sessionId) {

        BrainstormSession session = resolveSession(sessionId);
        if (session == null) return sessionError(sessionId);

        if (session.state() == BrainstormSession.State.ACTIVE) {
            session.abandon();
        }

        eventBus.pushBrainstormEvent(sessionId, "brainstorm-ended",
                Map.of("sessionId", sessionId, "state", session.state().name()));
        registry.remove(sessionId);
        return "ended brainstorm session '" + sessionId + "'";
    }

    private BrainstormSession resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return registry.find(sessionId).orElse(null);
    }

    private String sessionError(String sessionId) {
        return "error: brainstorm session not found — "
                + (sessionId == null ? "null" : "'" + sessionId + "'")
                + ". Use start_brainstorm to create a session.";
    }

    private void pushOptionsEvent(BrainstormSession session) {
        var optionMaps = session.options().stream().map(o -> Map.of(
                "id", o.id(),
                "title", o.title(),
                "description", o.description(),
                "tradeoffs", o.tradeoffs(),
                "status", o.status().name()
        )).toList();
        eventBus.pushBrainstormEvent(session.sessionId(), "brainstorm-options",
                Map.of("sessionId", session.sessionId(),
                       "options", optionMaps,
                       "state", session.state().name()));
    }

    private void pushConvergedEvent(BrainstormSession session) {
        var optionMaps = session.options().stream().map(o -> Map.of(
                "id", o.id(),
                "title", o.title(),
                "description", o.description(),
                "tradeoffs", o.tradeoffs(),
                "status", o.status().name()
        )).toList();
        eventBus.pushBrainstormEvent(session.sessionId(), "brainstorm-converged",
                Map.of("sessionId", session.sessionId(),
                       "options", optionMaps,
                       "state", session.state().name(),
                       "selectedOptionId", session.options().stream()
                               .filter(o -> o.status() == BrainstormOption.Status.SELECTED)
                               .map(BrainstormOption::id)
                               .findFirst().orElse("")));
    }
}
