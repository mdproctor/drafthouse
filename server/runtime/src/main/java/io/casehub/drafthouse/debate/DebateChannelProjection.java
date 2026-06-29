package io.casehub.drafthouse.debate;

import io.casehub.blocks.channel.BoundedProjectionDecorator;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

/**
 * Folds debate channel messages into ReviewState.
 * Dispatch is on content META header — debate channels encode metadata as:
 *   META:entryType=RAISE|agent=REV|round=1|priority=P1|scope=ISOLATED|location=§3.2\n\n<body>
 * entryType values must match EntryType enum names exactly (uppercase, underscores).
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
        String entryTypeStr = meta.get("entryType");
        if (entryTypeStr == null) return state;

        EntryType entryType;
        try {
            entryType = EntryType.valueOf(entryTypeStr);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Unknown entryType ''{0}'' — discarded", entryTypeStr);
            return state;
        }

        return switch (entryType) {
            case RAISE            -> handleRaise(state, message, meta);
            case AGREE            -> handleAgree(state, message, meta);
            case COUNTER          -> handleCounter(state, message, meta);
            case DISPUTE          -> handleDispute(state, message, meta);
            case QUALIFY          -> handleQualify(state, message, meta);
            case FLAG_HUMAN       -> handleFlagHuman(state, message, meta);
            case DECLINED         -> appendToPoint(state, message, meta, EntryType.DECLINED, ReviewStatus.DECLINED);
            case MEMO             -> handleMemo(state, message, meta);
            case SUB_TASK_REQUEST -> handleSubTaskRequest(state, message, meta);
            case SUB_TASK_FINDING -> handleSubTaskFinding(state, message, meta);
            case SUB_TASK_ERROR   -> handleSubTaskError(state, message, meta);
            case RESTART_CONTEXT  -> state;
        };
    }

    @Override
    public String render(ProjectionResult<ReviewState> result) {
        return result.isEmpty() ? "No debate activity yet." : renderer.render(result.state());
    }

    // ── RoundBoundedProjection ────────────────────────────────────────────────

    /**
     * Debate-specific bounded projection — delegates to {@link BoundedProjectionDecorator}
     * with round extraction via {@link DebateProtocol}.
     */
    public static class RoundBoundedProjection extends BoundedProjectionDecorator<ReviewState> {

        public RoundBoundedProjection(final int maxRound, final DebateChannelProjection delegate) {
            super(maxRound, delegate,
                    msg -> DebateProtocol.parseRound(DebateProtocol.parseMeta(msg.content())));
        }
    }

    // ── fold handlers ─────────────────────────────────────────────────────────

    private ReviewState handleRaise(ReviewState state, MessageView message, Map<String, String> meta) {
        String entryId = message.correlationId();
        if (entryId == null) return state;
        AgentType agent = agentType(meta);
        if (agent == null) return state;
        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope scope = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String location = meta.get("location");
        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);
        int round = DebateProtocol.parseRound(meta);
        var thread = new ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry(entryId, agent, round, EntryType.RAISE, DebateProtocol.bodyContent(message.content())));
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
        int round = DebateProtocol.parseRound(meta);
        AgentType agent = agentType(meta);
        if (agent == null) return state;
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
        AgentType agent = agentType(meta);
        if (agent == null) return state;
        ReviewPoint existing = state.points().get(targetId);
        int round = DebateProtocol.parseRound(meta);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agent, round, type, DebateProtocol.bodyContent(message.content())));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), new LinkedHashMap<>(state.subTaskFindings()));
    }

    /**
     * Resolves agent string to AgentType. Returns null (with log) instead of throwing —
     * fold() has no exception handling; throwing would crash the fold and break the session.
     * ERROR severity for null (protocol violation by our code); WARNING for unknown (future role).
     */
    private AgentType agentType(Map<String, String> meta) {
        String agent = meta.get("agent");
        if (agent == null) {
            LOG.log(System.Logger.Level.ERROR,
                    "Debate message missing META.agent field — protocol violation, message discarded");
            return null;
        }
        try {
            return AgentType.valueOf(agent);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Unknown agent ''{0}'' in debate META header — message discarded", agent);
            return null;
        }
    }

    private Priority parsePriority(String s) {
        try { return Priority.valueOf(s.toUpperCase()); } catch (Exception e) { return Priority.P3; }
    }

    private Scope parseScope(String s) {
        try { return Scope.valueOf(s.toUpperCase()); } catch (Exception e) { return Scope.ISOLATED; }
    }

    private ReviewState handleMemo(ReviewState state, MessageView message, Map<String, String> meta) {
        String agent = meta.getOrDefault("agent", "UNKNOWN");
        int round = DebateProtocol.parseRound(meta);
        String content = DebateProtocol.bodyContent(message.content());
        var memos = new ArrayList<>(state.memos());
        memos.add(new RoundMemo(agent, round, content));
        return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
                memos, new LinkedHashMap<>(state.subTaskFindings()));
    }

    private ReviewState handleSubTaskRequest(ReviewState state, MessageView message, Map<String, String> meta) {
        String subTaskId = meta.getOrDefault("subTaskId",
                message.correlationId() != null ? message.correlationId() : "unknown");
        SubTaskType taskType = parseSubTaskType(meta.getOrDefault("taskType", "CUSTOM"));
        String requestingAgent = meta.getOrDefault("agent", "UNKNOWN");
        String pointId = meta.get("pointId");
        var findings = new LinkedHashMap<>(state.subTaskFindings());
        findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, requestingAgent,
                pointId, null, null, SubTaskStatus.PENDING));
        return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), findings);
    }

    private ReviewState handleSubTaskFinding(ReviewState state, MessageView message, Map<String, String> meta) {
        String subTaskId = meta.getOrDefault("subTaskId",
                message.correlationId() != null ? message.correlationId() : "unknown");
        SubTaskType taskType = parseSubTaskType(meta.getOrDefault("taskType", "CUSTOM"));
        String agent = meta.getOrDefault("agent", "UNKNOWN");
        String pointId = meta.get("pointId");
        String finding = DebateProtocol.bodyContent(message.content());
        var findings = new LinkedHashMap<>(state.subTaskFindings());
        SubTaskFinding existing = findings.get(subTaskId);
        String resolvedPointId = existing != null && existing.pointId() != null ? existing.pointId() : pointId;
        findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, agent,
                resolvedPointId, finding, null, SubTaskStatus.COMPLETE));
        return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), findings);
    }

    private ReviewState handleSubTaskError(ReviewState state, MessageView message, Map<String, String> meta) {
        String subTaskId = meta.getOrDefault("subTaskId",
                message.correlationId() != null ? message.correlationId() : "unknown");
        SubTaskType taskType = parseSubTaskType(meta.getOrDefault("taskType", "CUSTOM"));
        String agent = meta.getOrDefault("agent", "UNKNOWN");
        String reason = DebateProtocol.bodyContent(message.content());
        var findings = new LinkedHashMap<>(state.subTaskFindings());
        SubTaskFinding existing = findings.get(subTaskId);
        String resolvedPointId = existing != null ? existing.pointId() : null;
        findings.put(subTaskId, new SubTaskFinding(subTaskId, taskType, agent,
                resolvedPointId, null, reason, SubTaskStatus.ERROR));
        return new ReviewState(state.points(), new ArrayList<>(state.humanFlags()),
                new ArrayList<>(state.memos()), findings);
    }

    private SubTaskType parseSubTaskType(String s) {
        try { return SubTaskType.valueOf(s); } catch (IllegalArgumentException e) { return SubTaskType.CUSTOM; }
    }
}
