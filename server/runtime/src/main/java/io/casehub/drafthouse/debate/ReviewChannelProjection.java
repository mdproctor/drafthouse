package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/**
 * Folds review Q&A channel messages into ReviewState.
 * Dispatch is on message.type() — review channels never carry artefactRefs.
 * Agent classification uses message.actorType(): HUMAN→REV, AGENT→IMP.
 *
 * Does NOT implement RenderableProjection — ReviewerChannelBackend calls
 * ReviewConversationRenderer.render() directly; projection.render() is never called.
 */
@ApplicationScoped
public class ReviewChannelProjection implements ChannelProjection<ReviewState> {

    private static final System.Logger LOG = System.getLogger(ReviewChannelProjection.class.getName());

    @Override
    public ReviewState identity() {
        return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
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

    private ReviewState handleRaise(ReviewState state, MessageView message) {
        String entryId = message.correlationId();
        if (entryId == null) return state;
        String artefacts = message.artefactRefs() != null ? message.artefactRefs() : "";
        Map<String, String> meta = parseArtefacts(artefacts);
        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope scope = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String location = meta.get("location");
        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(entryId, agentType(message), 0, EntryType.RAISE, message.content()));
        var point = new ReviewPoint(entryId, classification, thread, ReviewStatus.OPEN);
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ReviewState handleQualify(ReviewState state, MessageView message) {
        return appendToPoint(state, message, EntryType.QUALIFY, ReviewStatus.ACTIVE);
    }

    private ReviewState handleAgree(ReviewState state, MessageView message) {
        return appendToPoint(state, message, EntryType.AGREE, ReviewStatus.AGREED);
    }

    private ReviewState appendToPoint(ReviewState state, MessageView message,
                                       EntryType entryType, ReviewStatus newStatus) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Response references unknown point ID: {0} — discarded", targetId);
            return state;
        }
        ReviewPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(message), 0, entryType, message.content()));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ReviewState handleDecline(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Decline references unknown point ID: {0} — discarded", targetId);
            return state;
        }
        ReviewPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(message), 0, EntryType.DECLINED,
                Objects.requireNonNullElse(message.content(), "")));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, ReviewStatus.DECLINED);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ReviewState handleFlagHuman(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        String content = Objects.requireNonNullElse(message.content(), "");
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ReviewPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            thread.add(new ThreadEntry(null, agentType(message), 0, EntryType.FLAG_HUMAN, content));
            points.put(targetId, new ReviewPoint(p.id(), p.classification(), thread, ReviewStatus.PENDING_HUMAN));
        }
        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, 0, agentType(message), content));
        return new ReviewState(points, flags,
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AgentType agentType(MessageView message) {
        if (message.actorType() == null) {
            throw new IllegalArgumentException(
                    "MessageView.actorType() must not be null in ReviewChannelProjection");
        }
        return switch (message.actorType()) {
            case HUMAN -> AgentType.REV;
            case AGENT -> AgentType.IMP;
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
        try { return Priority.valueOf(s.toUpperCase()); } catch (Exception e) { return Priority.P3; }
    }

    private Scope parseScope(String s) {
        try { return Scope.valueOf(s.toUpperCase()); } catch (Exception e) { return Scope.ISOLATED; }
    }
}
