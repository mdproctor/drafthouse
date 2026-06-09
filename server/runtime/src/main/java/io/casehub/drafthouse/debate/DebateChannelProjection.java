package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/**
 * Folds debate channel messages into ReviewState.
 * Dispatch is on content META header — debate channels encode metadata as:
 *   META:entryType=raise|agent=REV|round=1|priority=P1|scope=ISOLATED|location=§3.2\n\n<body>
 * Agent classification uses meta.agent (REV/IMP) — NOT actorType.
 *
 * Both REV and IMP agents are ActorType.AGENT; actorType cannot distinguish them.
 * artefactRefs is NOT used — Qhorus ArtefactRefParser validates artefactRefs as CSV UUIDs,
 * so free-form metadata cannot be stored there. See PP-20260607-508f7b.
 */
@ApplicationScoped
public class DebateChannelProjection implements RenderableProjection<ReviewState> {

    private static final System.Logger LOG = System.getLogger(DebateChannelProjection.class.getName());

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Override public String projectionName() { return "debate-summary"; }

    @Override
    public ReviewState identity() {
        return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        Map<String, String> meta = DebateProtocol.parseMeta(message.content());
        String entryType = meta.get("entryType");
        if (entryType == null) return state;
        return switch (entryType) {
            case "raise"      -> handleRaise(state, message, meta);
            case "agree"      -> handleAgree(state, message, meta);
            case "dispute"    -> handleDispute(state, message, meta);
            case "qualify"    -> handleQualify(state, message, meta);
            case "counter"    -> handleCounter(state, message, meta);
            case "flag-human" -> handleFlagHuman(state, message, meta);
            default           -> state;
        };
    }

    @Override
    public String render(ProjectionResult<ReviewState> result) {
        return result.isEmpty() ? "No debate activity yet." : renderer.render(result.state());
    }

    // ── fold handlers ─────────────────────────────────────────────────────────

    private ReviewState handleRaise(ReviewState state, MessageView message, Map<String, String> meta) {
        String entryId = message.correlationId();
        if (entryId == null) return state;
        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope scope = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String location = meta.get("location");
        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        int round = parseRound(meta);
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(entryId, agentType(meta), round, EntryType.RAISE, DebateProtocol.bodyContent(message.content())));
        var point = new ReviewPoint(entryId, classification, thread, ReviewStatus.OPEN);
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ReviewState handleAgree(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.AGREE, ReviewStatus.AGREED);
    }

    private ReviewState handleDispute(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.DISPUTE, ReviewStatus.DISPUTED);
    }

    private ReviewState handleQualify(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.QUALIFY, ReviewStatus.ACTIVE);
    }

    private ReviewState handleCounter(ReviewState state, MessageView message, Map<String, String> meta) {
        return appendToPoint(state, message, meta, EntryType.COUNTER, ReviewStatus.ACTIVE);
    }

    private ReviewState handleFlagHuman(ReviewState state, MessageView message, Map<String, String> meta) {
        String targetId = message.correlationId();
        String content = DebateProtocol.bodyContent(Objects.requireNonNullElse(message.content(), ""));
        int round = parseRound(meta);
        AgentType agent = agentType(meta);
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ReviewPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            thread.add(new ThreadEntry(null, agent, round, EntryType.FLAG_HUMAN, content));
            points.put(targetId, new ReviewPoint(p.id(), p.classification(), thread, ReviewStatus.PENDING_HUMAN));
        }
        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, round, agent, content));
        return new ReviewState(points, flags,
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ReviewState appendToPoint(ReviewState state, MessageView message,
                                       Map<String, String> meta, EntryType type, ReviewStatus newStatus) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Debate entry ({0}) references unknown point: {1} — discarded", type, targetId);
            return state;
        }
        ReviewPoint existing = state.points().get(targetId);
        int round = parseRound(meta);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(meta), round, type, DebateProtocol.bodyContent(message.content())));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    private AgentType agentType(Map<String, String> meta) {
        String agent = meta.get("agent");
        if (agent == null) throw new IllegalArgumentException("debate message content missing META.agent field");
        return switch (agent) {
            case "REV" -> AgentType.REV;
            case "IMP" -> AgentType.IMP;
            default    -> throw new IllegalArgumentException("Unknown agent in debate META header: " + agent);
        };
    }

    private int parseRound(Map<String, String> meta) {
        String r = meta.get("round");
        if (r == null) return 0;
        try { return Integer.parseInt(r); } catch (NumberFormatException e) { return 0; }
    }

    private Priority parsePriority(String s) {
        try { return Priority.valueOf(s.toUpperCase()); } catch (Exception e) { return Priority.P3; }
    }

    private Scope parseScope(String s) {
        try { return Scope.valueOf(s.toUpperCase()); } catch (Exception e) { return Scope.ISOLATED; }
    }
}
