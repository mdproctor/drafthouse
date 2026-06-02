package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SummaryProjector implements ChannelProjection<ReviewState> {

    @Override
    public ReviewState identity() {
        return new ReviewState(new LinkedHashMap<>(), new ArrayList<>());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        return switch (message.type()) {
            case QUERY             -> handleRaise(state, message);
            case RESPONSE, DECLINE -> handleResponse(state, message);
            case HANDOFF           -> handleFlagHuman(state, message);
            case EVENT             -> state; // AgentMemo — no-op
            case COMMAND, STATUS, DONE, FAILURE -> state;
        };
    }

    /** Fold a list of DebateEvents from scratch. */
    public ReviewState project(List<DebateEvent> events) {
        ReviewState state = identity();
        for (DebateEvent event : events) {
            state = apply(state, toMessageView(event));
        }
        return state;
    }

    /** Apply only events from lastFoldedCount onwards onto an existing state. */
    public ReviewState projectIncremental(ReviewState state, List<DebateEvent> allEvents, int lastFoldedCount) {
        for (int i = lastFoldedCount; i < allEvents.size(); i++) {
            state = apply(state, toMessageView(allEvents.get(i)));
        }
        return state;
    }

    // -------------------------------------------------------------------------
    // Private handlers
    // -------------------------------------------------------------------------

    private ReviewState handleRaise(ReviewState state, MessageView message) {
        String entryId   = message.correlationId();
        String artefacts = message.artefactRefs() != null ? message.artefactRefs() : "";
        Map<String, String> meta = parseArtefacts(artefacts);

        Priority priority = parsePriority(meta.getOrDefault("priority", "P3"));
        Scope    scope    = parseScope(meta.getOrDefault("scope", "ISOLATED"));
        String   location = meta.get("location");

        var classification = new PointClassification(priority, scope,
                location != null && !location.isBlank() ? location : null);

        var thread = new ArrayList<ThreadEntry>();
        // round=0: MessageView carries no round field in the synthetic v1 path; populated by #27 DebateChannel
        thread.add(new ThreadEntry(entryId, agentType(message.sender()), 0, EntryType.RAISE, message.content()));

        var point  = new ReviewPoint(entryId, classification, thread, ReviewStatus.OPEN);
        var points = new LinkedHashMap<>(state.points());
        points.put(entryId, point);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleResponse(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        if (targetId == null || !state.points().containsKey(targetId)) {
            // Log at warn level so agent output issues are diagnosable
            if (targetId != null) {
                System.Logger logger = System.getLogger(SummaryProjector.class.getName());
                logger.log(System.Logger.Level.WARNING,
                        "Response references unknown point ID: {0} — discarded", targetId);
            }
            return state;
        }

        boolean isQualify = message.content() != null
                && message.content().startsWith("[QUALIFY] ");
        String content = isQualify
                ? message.content().substring("[QUALIFY] ".length())
                : message.content();

        EntryType entryType = switch (message.type()) {
            case RESPONSE -> isQualify ? EntryType.QUALIFY : EntryType.AGREE;
            case DECLINE  -> EntryType.DISPUTE;
            default       -> EntryType.AGREE;
        };

        ReviewStatus newStatus = switch (entryType) {
            case AGREE            -> ReviewStatus.AGREED;
            case DISPUTE, QUALIFY -> ReviewStatus.ACTIVE;
            default               -> ReviewStatus.ACTIVE;
        };

        ReviewPoint existing = state.points().get(targetId);
        var thread = new ArrayList<>(existing.thread());
        // round=0: MessageView carries no round field in the synthetic v1 path; populated by #27 DebateChannel
        thread.add(new ThreadEntry(null, agentType(message.sender()), 0, entryType, content));
        var updated = new ReviewPoint(existing.id(), existing.classification(), thread, newStatus);

        var points = new LinkedHashMap<>(state.points());
        points.put(targetId, updated);
        return new ReviewState(points, new ArrayList<>(state.humanFlags()));
    }

    private ReviewState handleFlagHuman(ReviewState state, MessageView message) {
        String targetId = message.correlationId();
        var points = new LinkedHashMap<>(state.points());
        if (targetId != null && points.containsKey(targetId)) {
            ReviewPoint p = points.get(targetId);
            var thread = new ArrayList<>(p.thread());
            // round=0: MessageView carries no round field in the synthetic v1 path; populated by #27 DebateChannel
            thread.add(new ThreadEntry(null, agentType(message.sender()), 0, EntryType.FLAG_HUMAN, message.content()));
            points.put(targetId, new ReviewPoint(p.id(), p.classification(), thread, ReviewStatus.PENDING_HUMAN));
        }
        var flags = new ArrayList<>(state.humanFlags());
        flags.add(new FlagEntry(null, 0, agentType(message.sender()), message.content()));
        return new ReviewState(points, flags);
    }

    // -------------------------------------------------------------------------
    // DebateEvent → MessageView bridge
    // -------------------------------------------------------------------------

    private MessageView toMessageView(DebateEvent event) {
        return switch (event) {
            case DebateEvent.RaiseEvent r -> new MessageView(
                    null, null, r.agent().name(), MessageType.QUERY,
                    r.content(), r.entryId(), null, null,
                    "entryId=" + r.entryId() + "|priority=" + r.priority()
                            + "|scope=" + r.scope()
                            + "|location=" + (r.location() != null ? r.location() : ""),
                    null, null, null, 0);

            case DebateEvent.ResponseEvent r when r.type() == EntryType.QUALIFY ->
                    new MessageView(null, null, r.agent().name(), MessageType.RESPONSE,
                            "[QUALIFY] " + r.content(), r.targetId(), null, null,
                            null, null, null, null, 0);

            case DebateEvent.ResponseEvent r when r.type() == EntryType.AGREE ->
                    new MessageView(null, null, r.agent().name(), MessageType.RESPONSE,
                            r.content(), r.targetId(), null, null,
                            null, null, null, null, 0);

            case DebateEvent.ResponseEvent r ->
                    new MessageView(null, null, r.agent().name(), MessageType.DECLINE,
                            r.content(), r.targetId(), null, null,
                            null, null, null, null, 0);

            case DebateEvent.FlagHumanEvent f ->
                    new MessageView(null, null, f.agent().name(), MessageType.HANDOFF,
                            f.content(), f.targetId(), null, "human",
                            null, null, null, null, 0);

            case DebateEvent.AgentMemo m ->
                    new MessageView(null, null, m.agent().name(), MessageType.EVENT,
                            m.content(), null, null, null,
                            null, null, null, null, 0);
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentType agentType(String sender) {
        if (sender == null) return AgentType.REV;
        return switch (sender.toUpperCase()) {
            case "IMP" -> AgentType.IMP;
            default    -> AgentType.REV;
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
        try {
            return Priority.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Priority.P3;
        }
    }

    private Scope parseScope(String s) {
        try {
            return Scope.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Scope.ISOLATED;
        }
    }
}
