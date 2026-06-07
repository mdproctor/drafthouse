package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class DebateChannelProjection implements RenderableProjection<ReviewState> {

    private static final System.Logger LOG = System.getLogger(DebateChannelProjection.class.getName());

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Override public String projectionName() { return "debate-summary"; }

    @Override
    public ReviewState identity() {
        return new ReviewState(Map.of(), List.of());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        return switch (message.type()) {
            case QUERY    -> handleRaise(state, message);
            case RESPONSE -> handleResponse(state, message);
            case DECLINE  -> handleDecline(state, message);
            case HANDOFF  -> handleFlagHuman(state, message);
            default       -> state;
        };
    }

    @Override
    public String render(ProjectionResult<ReviewState> result) {
        return result.isEmpty() ? "No review activity yet." : renderer.render(result.state());
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
        // round=0: MessageView carries no round field in v1; populated by #27 DebateChannel
        thread.add(new ThreadEntry(entryId, agentType(message), 0, EntryType.RAISE, message.content()));
        var point = new ReviewPoint(entryId, classification, thread, ReviewStatus.OPEN);
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleResponse(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Response references unknown point ID: {0} — discarded", targetId);
            return state;
        }
        boolean isQualify = message.content() != null
                && message.content().startsWith("[QUALIFY] ");
        String content = isQualify
                ? message.content().substring("[QUALIFY] ".length())
                : message.content();
        EntryType entryType = isQualify ? EntryType.QUALIFY : EntryType.AGREE;
        ReviewStatus newStatus = isQualify ? ReviewStatus.ACTIVE : ReviewStatus.AGREED;
        ReviewPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        thread.add(new ThreadEntry(null, agentType(message), 0, entryType, content));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);
        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
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
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
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
        return new ReviewState(points, flags);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AgentType agentType(MessageView message) {
        if (message.actorType() == null) {
            throw new IllegalArgumentException(
                    "MessageView.actorType() must not be null in DebateChannelProjection");
        }
        return switch (message.actorType()) {
            case HUMAN -> AgentType.REV;
            case AGENT -> AgentType.IMP;
            default    -> throw new IllegalArgumentException(
                    "Unsupported actorType in debate projection: " + message.actorType());
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
