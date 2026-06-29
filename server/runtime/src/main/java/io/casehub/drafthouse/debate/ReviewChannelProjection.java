package io.casehub.drafthouse.debate;

import io.casehub.blocks.conversation.*;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/**
 * Folds review Q&A channel messages into ConversationState.
 * Dispatch is on message.type() — review channels never carry artefactRefs.
 * Agent classification uses message.actorType(): HUMAN→"REV", AGENT→"IMP".
 *
 * Does NOT implement RenderableProjection — ReviewerChannelBackend calls
 * ReviewConversationRenderer.render() directly; projection.render() is never called.
 */
@ApplicationScoped
public class ReviewChannelProjection implements ChannelProjection<ConversationState> {

    private static final System.Logger LOG = System.getLogger(ReviewChannelProjection.class.getName());

    @Override
    public ConversationState identity() {
        return new ConversationState(Map.of(), List.of(), List.of(), Map.of());
    }

    @Override
    public ConversationState apply(ConversationState state, MessageView message) {
        return switch (message.type()) {
            case QUERY    -> handleRaise(state, message);
            case RESPONSE -> handleQualify(state, message);
            case DONE     -> handleAgree(state, message);
            case DECLINE  -> handleDecline(state, message);
            case HANDOFF  -> handleFlagHuman(state, message);
            default       -> state;
        };
    }

    // ── fold handlers ─────────────────────────────────────────────────────────

    private ConversationState handleRaise(ConversationState state, MessageView message) {
        String entryId = message.correlationId();
        if (entryId == null) return state;
        String artefacts = message.artefactRefs() != null ? message.artefactRefs() : "";
        Map<String, String> meta = parseArtefacts(artefacts);
        Priority priority = parsePriority(meta.getOrDefault("priority", "LOW"));
        String scope = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String location = meta.get("location");
        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(entryId, role(message), 0, "RAISE", message.content()));
        var point = new ConversationPoint(entryId, classification, thread, "OPEN");
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ConversationState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ConversationState handleQualify(ConversationState state, MessageView message) {
        return appendToPoint(state, message, "QUALIFY", "ACTIVE");
    }

    private ConversationState handleAgree(ConversationState state, MessageView message) {
        return appendToPoint(state, message, "AGREE", "AGREED");
    }

    private ConversationState appendToPoint(ConversationState state, MessageView message,
                                       String entryType, String newStatus) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Response references unknown point ID: {0} — discarded", targetId);
            return state;
        }
        ConversationPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, role(message), 0, entryType, message.content()));
        var updated = new ConversationPoint(existing.id(), existing.classification(), thread, newStatus);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ConversationState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ConversationState handleDecline(ConversationState state, MessageView message) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Decline references unknown point ID: {0} — discarded", targetId);
            return state;
        }
        ConversationPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, role(message), 0, "DECLINED",
                Objects.requireNonNullElse(message.content(), "")));
        var updated = new ConversationPoint(existing.id(), existing.classification(), thread, "DECLINED");
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ConversationState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ConversationState handleFlagHuman(ConversationState state, MessageView message) {
        String targetId = message.correlationId();
        String content = Objects.requireNonNullElse(message.content(), "");
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ConversationPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            thread.add(new ThreadEntry(null, role(message), 0, "FLAG_HUMAN", content));
            points.put(targetId, new ConversationPoint(p.id(), p.classification(), thread, ConversationProtocol.STATUS_ESCALATED));
        }
        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, 0, role(message), content));
        return new ConversationState(points, flags,
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String role(MessageView message) {
        if (message.actorType() == null) {
            throw new IllegalArgumentException(
                    "MessageView.actorType() must not be null in ReviewChannelProjection");
        }
        return switch (message.actorType()) {
            case HUMAN -> "REV";
            case AGENT -> "IMP";
            default    -> throw new IllegalArgumentException(
                    "Unsupported actorType in review projection: " + message.actorType());
        };
    }

    private Map<String, String> parseArtefacts(String artefacts) {
        Map<String, String> map = new HashMap<>();
        for (String part : artefacts.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        return map;
    }

    private Priority parsePriority(String s) {
        try { return Priority.valueOf(s.toUpperCase()); } catch (Exception e) { return Priority.LOW; }
    }

    private String parseScope(String s) {
        // Accept any string — return as-is (uppercase normalized)
        return s != null ? s.toUpperCase() : "ISOLATED";
    }
}
