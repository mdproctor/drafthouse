package io.casehub.drafthouse.debate;

import io.casehub.agency.communication.dispatch.BoundedProjectionDecorator;
import io.casehub.agency.communication.dispatch.ChannelMessageMeta;
import io.casehub.agency.deliberation.conversation.ConversationFold;
import io.casehub.agency.deliberation.conversation.ConversationProjection;
import io.casehub.agency.deliberation.conversation.ConversationProtocol;
import io.casehub.agency.deliberation.conversation.ConversationRenderer;
import io.casehub.agency.deliberation.conversation.ConversationRendererConfig;
import io.casehub.agency.deliberation.conversation.ConversationState;
import io.casehub.agency.deliberation.conversation.Priority;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DraftHouse debate projection — extends {@link ConversationProjection} with
 * domain-specific hook mappings for debate entry types (RAISE, AGREE, COUNTER, etc).
 *
 * <p>Infrastructure entry types (MEMO, SUB_TASK_*, FLAG_HUMAN, RESTART_CONTEXT) are
 * handled by the base class. This subclass only maps the three hooks:
 * {@link #sentinel()}, {@link #isPointInitiator(String)}, {@link #statusAfter(String)}.
 */
@ApplicationScoped
public class DebateChannelProjection extends ConversationProjection
        implements RenderableProjection<ConversationState> {

    private static final Logger LOG = Logger.getLogger(DebateChannelProjection.class.getName());

    private static final ConversationRendererConfig DEBATE_CONFIG =
            ConversationRendererConfig.builder()
                    .statusEmoji(Map.ofEntries(
                            Map.entry("OPEN", "🔴"),       // red circle
                            Map.entry("ACTIVE", "🟡"),     // yellow circle
                            Map.entry("AGREED", "✅"),            // check mark
                            Map.entry("ESCALATED", "🔵"),  // blue circle
                            Map.entry("DECLINED", "🚫"),   // prohibited
                            Map.entry("DISPUTED", "⚡"),         // lightning
                            Map.entry("VERIFIED", "✅"),
                            Map.entry("DEFERRED", "⏸"),
                            Map.entry("HUMAN_OVERRIDE", "👤")))
                    .resolvedStatuses(Set.of("AGREED", "DECLINED", "VERIFIED", "DEFERRED", "HUMAN_OVERRIDE"))
                    .escalatedStatuses(Set.of("ESCALATED"))
                    .priorityLabel(Map.of(
                            Priority.HIGH, "P1",
                            Priority.MEDIUM, "P2",
                            Priority.LOW, "P3"))
                    .entryTypeLabel(Map.ofEntries(
                            Map.entry("RAISE", "raised"),
                            Map.entry("AGREE", "agreed"),
                            Map.entry("COUNTER", "countered"),
                            Map.entry("DISPUTE", "disputed"),
                            Map.entry("QUALIFY", "qualified"),
                            Map.entry("FLAG_HUMAN", "flag"),
                            Map.entry("DECLINED", "declined"),
                            Map.entry("VERIFIED", "verified"),
                            Map.entry("DEFERRED", "deferred"),
                            Map.entry("COMMENT", "commented"),
                            Map.entry("HUMAN_OVERRIDE", "overrode"),
                            Map.entry("REPRIORITISE", "reprioritised")))
                    .roleLabel(Map.of("REV", "REV", "IMP", "IMP", "HUMAN", "HUM"))
                    .build();

    private final ConversationRenderer renderer = new ConversationRenderer(DEBATE_CONFIG);

    @Override
    public String projectionName() { return "debate-summary"; }

    @Override
    public String render(ProjectionResult<ConversationState> result) {
        return result.isEmpty() ? "No debate activity yet." : renderer.render(result.state());
    }

    /**
     * Renders a {@link ConversationState} directly — used by {@code DebateMcpTools.renderBounded()}
     * for bounded projection rendering outside the full {@link ProjectionResult} wrapper.
     */
    public String renderState(ConversationState state) {
        return renderer.render(state);
    }

    @Override
    protected String sentinel() { return DebateProtocol.META_SENTINEL; }

    @Override
    protected boolean isPointInitiator(String entryType) {
        return "RAISE".equals(entryType);
    }

    @Override
    protected String statusAfter(String entryType) {
        return switch (entryType) {
            case "AGREE" -> "AGREED";
            case "COUNTER", "QUALIFY" -> "ACTIVE";
            case "DISPUTE" -> "DISPUTED";
            case "DECLINED" -> "DECLINED";
            case "VERIFIED" -> "VERIFIED";
            case "DEFERRED" -> "DEFERRED";
            case "HUMAN_OVERRIDE" -> "HUMAN_OVERRIDE";
            case "COMMENT" -> null;
            default -> null;
        };
    }

    @Override
    public ConversationState apply(ConversationState state, MessageView message) {
        try {
            Map<String, String> meta      = ChannelMessageMeta.parseMeta(sentinel(), message.content());
            if (meta.containsKey("threadId")) {
                return state;
            }
            String              entryType = meta.get(ConversationProtocol.ENTRY_TYPE);
            if ("ROUND_SNAPSHOT".equals(entryType)) {
                return state;
            }
            if ("REPRIORITISE".equals(entryType)) {
                String priorityStr = meta.get(ConversationProtocol.PRIORITY);
                if (priorityStr == null) {
                    LOG.log(Level.WARNING, "REPRIORITISE missing priority — discarded");
                    return state;
                }
                Priority newPriority;
                try {
                    newPriority = Priority.valueOf(priorityStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    LOG.log(Level.WARNING, "REPRIORITISE invalid priority: " + priorityStr + " — discarded");
                    return state;
                }
                String role  = meta.get(ConversationProtocol.ROLE);
                int    round = ChannelMessageMeta.parseInt(meta, ConversationProtocol.ROUND);
                String body  = ChannelMessageMeta.bodyContent(sentinel(), message.content());
                return ConversationFold.reprioritisePoint(state,
                                                          message.correlationId(), message.id(), message.type(),
                                                          message.sender(), message.createdAt(),
                                                          role, round, newPriority, body);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Pre-base-class check failed — delegating to base", e);
        }
        return super.apply(state, message);
    }

    // ── RoundBoundedProjection ────────────────────────────────────────────────

    /**
     * Debate-specific bounded projection — delegates to {@link BoundedProjectionDecorator}
     * with round extraction via {@link DebateProtocol}.
     */
    public static class RoundBoundedProjection extends BoundedProjectionDecorator<ConversationState> {

        public RoundBoundedProjection(final int maxRound, final DebateChannelProjection delegate) {
            super(maxRound, delegate,
                    msg -> DebateProtocol.parseRound(DebateProtocol.parseMeta(msg.content())));
        }
    }
}
