package io.casehub.drafthouse.debate;

import io.casehub.blocks.channel.BoundedProjectionDecorator;
import io.casehub.blocks.conversation.*;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.spi.RenderableProjection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

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
                            Map.entry("DEFERRED", "⏸")))
                    .resolvedStatuses(Set.of("AGREED", "DECLINED", "VERIFIED", "DEFERRED"))
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
                            Map.entry("DEFERRED", "deferred")))
                    .roleLabel(Map.of("REV", "REV", "IMP", "IMP"))
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
            default -> null;
        };
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
