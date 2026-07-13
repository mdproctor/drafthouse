package io.casehub.drafthouse.debate;

import io.casehub.blocks.conversation.ConversationFold;
import io.casehub.blocks.conversation.ConversationState;
import io.casehub.blocks.conversation.PointClassification;
import io.casehub.blocks.conversation.Priority;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ChannelProjection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Folds review Q&A channel messages into ConversationState.
 * Dispatch is on message.type() — review channels never carry artefactRefs.
 * Agent classification uses message.actorType(): HUMAN→"REV", AGENT→"IMP".
 *
 * <p>Delegates fold operations to {@link ConversationFold} — this class only
 * handles typed-message dispatch and input extraction.
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
            case RESPONSE -> handleResponse(state, message, "QUALIFY", "ACTIVE");
            case DONE     -> handleResponse(state, message, "AGREE", "AGREED");
            case DECLINE  -> handleResponse(state, message, "DECLINED", "DECLINED");
            case HANDOFF  -> handleFlagHuman(state, message);
            default       -> state;
        };
    }

    // ── dispatch ─────────────────────────────────────────────────────────────

    private ConversationState handleRaise(ConversationState state, MessageView message) {
        String entryId = message.correlationId();
        if (entryId == null) {return state;}

        // Review channels never carry artefactRefs — classification comes from meta sentinel
        Map<String, String> meta           = Map.of();
        Priority            priority       = Priority.LOW;
        String              scope          = "ISOLATED";
        String              location       = null;
        var                 classification = new PointClassification(priority, scope, location);

        return ConversationFold.createPoint(state, entryId, classification,
                                            role(message), 0, "RAISE", message.content());}

    private ConversationState handleResponse(ConversationState state, MessageView message,
                                              String entryType, String newStatus) {
        String targetId = message.correlationId();
        if (targetId == null) return state;
        if (!state.points().containsKey(targetId)) {
            LOG.log(System.Logger.Level.WARNING,
                    "Response references unknown point ID: {0} — discarded", targetId);
            return state;
        }

        String content = Objects.requireNonNullElse(message.content(), "");
        return ConversationFold.respondToPoint(state, targetId, role(message), 0,
                entryType, content, newStatus);
    }

    private ConversationState handleFlagHuman(ConversationState state, MessageView message) {
        String content = Objects.requireNonNullElse(message.content(), "");
        return ConversationFold.flagHuman(state, message.correlationId(),
                role(message), 0, content);
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
        return s != null ? s.toUpperCase() : "ISOLATED";
    }
}
