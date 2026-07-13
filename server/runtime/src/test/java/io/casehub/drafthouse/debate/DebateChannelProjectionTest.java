package io.casehub.drafthouse.debate;

import io.casehub.blocks.conversation.ConversationState;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import org.junit.jupiter.api.Test;
import java.util.List;

import static io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests DraftHouse-specific hook mappings in {@link DebateChannelProjection}.
 * Bulk fold logic (infrastructure handlers, point initiation, immutability, etc.)
 * is tested in blocks {@code ConversationProjectionTest}.
 */
class DebateChannelProjectionTest {

    private final DebateChannelProjection proj = new DebateChannelProjection();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MessageView msg(MessageType type, String correlationId, String metaHeader, String bodyContent) {
        String encodedContent = META_SENTINEL + metaHeader + "\n\n" + bodyContent;
        return new MessageView(null, null, "test-sender", type,
                encodedContent, correlationId, null, null, null, List.of(), ActorType.AGENT, null, null, 0);
    }

    private static String ratefacts(String entryType, String role, int round) {
        return "entryType=" + entryType + "|role=" + role + "|round=" + round;
    }

    private static String ratefacts(String entryType, String role, int round, String priority, String scope) {
        return "entryType=" + entryType + "|role=" + role + "|round=" + round
                + "|priority=" + priority + "|scope=" + scope;
    }

    // ── projectionName ───────────────────────────────────────────────────────

    @Test
    void projectionName_returnsDebateSummary() {
        assertThat(proj.projectionName()).isEqualTo("debate-summary");
    }

    // ── sentinel ─────────────────────────────────────────────────────────────

    @Test
    void sentinel_returnsDhmetaPrefix() {
        // Verified indirectly: messages with DHMETA: sentinel are parsed
        ConversationState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1",
                        ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"),
                        "Content."));
        assertThat(s.points()).containsKey("pt-1");
    }

    @Test
    void nonDhmetaSentinel_treatedAsPlainContent() {
        // "META:" without DHMETA: prefix is not recognised
        ConversationState s = proj.apply(proj.identity(),
                new MessageView(null, null, "test", MessageType.QUERY,
                        "META:entryType=RAISE|role=REV|round=1|priority=HIGH|scope=ISOLATED\n\nBody.",
                        "pt-old", null, null, null, List.of(), ActorType.AGENT, null, null, 0));
        assertThat(s.points()).isEmpty();
    }

    // ── isPointInitiator ─────────────────────────────────────────────────────

    @Test
    void raise_isPointInitiator_createsOpenPoint() {
        ConversationState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1",
                        ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"),
                        "Issue."));
        assertThat(s.points()).containsKey("pt-1");
        assertThat(s.points().get("pt-1").status()).isEqualTo("OPEN");
    }

    @Test
    void agree_isNotPointInitiator_appendsToExisting() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1",
                        ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DONE, "pt-1", ratefacts("AGREE", "IMP", 2), "Agreed."));
        assertThat(s1.points().get("pt-1").thread()).hasSize(2);
    }

    // ── statusAfter ──────────────────────────────────────────────────────────

    @Test
    void agree_transitionsToAgreed() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DONE, "pt-1", ratefacts("AGREE", "IMP", 2), "Agreed."));
        assertThat(s1.points().get("pt-1").status()).isEqualTo("AGREED");
    }

    @Test
    void counter_transitionsToActive() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.RESPONSE, "pt-1", ratefacts("COUNTER", "IMP", 2), "Counter."));
        assertThat(s1.points().get("pt-1").status()).isEqualTo("ACTIVE");
    }

    @Test
    void qualify_transitionsToActive() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.RESPONSE, "pt-1", ratefacts("QUALIFY", "IMP", 2), "Partially."));
        assertThat(s1.points().get("pt-1").status()).isEqualTo("ACTIVE");
    }

    @Test
    void dispute_transitionsToDisputed() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DISPUTE", "IMP", 2), "Disagree."));
        assertThat(s1.points().get("pt-1").status()).isEqualTo("DISPUTED");
    }

    @Test
    void declined_transitionsToDeclined() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DECLINED", "IMP", 2), "Declined."));
        assertThat(s1.points().get("pt-1").status()).isEqualTo("DECLINED");
    }

    @Test
    void verified_transitionsToVerified() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DONE, "pt-1", ratefacts("VERIFIED", "IMP", 2), "Verified."));
        assertThat(s1.points().get("pt-1").status()).isEqualTo("VERIFIED");
    }

    @Test
    void deferred_transitionsToDeferred() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.RESPONSE, "pt-1", ratefacts("DEFERRED", "IMP", 2), "Deferred."));
        assertThat(s1.points().get("pt-1").status()).isEqualTo("DEFERRED");
    }

    @Test
    void unknownEntryType_statusUnchanged() {
        // Unknown domain entry type → base class discards (no statusAfter match)
        ConversationState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", "entryType=FUTURE_TYPE|role=REV|round=1", "?"));
        // Unknown entry type is not RAISE (isPointInitiator=false), and there's no existing
        // point to append to, so state is unchanged
        assertThat(s.points()).isEmpty();
    }

    // ── render ────────────────────────────────────────────────────────────────

    @Test
    void render_emptyResult_returnsNonBlankSentinel() {
        ProjectionResult<ConversationState> empty = new ProjectionResult<>(proj.identity(), null);
        assertThat(proj.render(empty)).isNotBlank();
        assertThat(proj.render(empty)).isEqualTo("No debate activity yet.");
    }

    @Test
    void render_nonEmptyResult_delegatesToRenderer() {
        ConversationState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1",
                        ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ProjectionResult<ConversationState> result = new ProjectionResult<>(s, 1L);
        String rendered = proj.render(result);
        assertThat(rendered).contains("Conversation Summary");
        assertThat(rendered).contains("Issue.");
    }

    @Test
    void renderState_delegatesToRenderer() {
        ConversationState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1",
                        ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        String rendered = proj.renderState(s);
        assertThat(rendered).contains("Conversation Summary");
        assertThat(rendered).contains("Issue.");
    }

    // ── identity ─────────────────────────────────────────────────────────────

    @Test
    void identity_hasEmptyCollections() {
        ConversationState s = proj.identity();
        assertThat(s.points()).isEmpty();
        assertThat(s.humanFlags()).isEmpty();
        assertThat(s.memos()).isEmpty();
        assertThat(s.subTaskFindings()).isEmpty();
    }

    // ── RoundBoundedProjection ────────────────────────────────────────────────

    @Test
    void roundBounded_excludesAboveMaxRound() {
        var bounded = new DebateChannelProjection.RoundBoundedProjection(1, proj);
        ConversationState s0 = bounded.apply(bounded.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Round 1."));
        ConversationState s1 = bounded.apply(s0,
                msg(MessageType.QUERY, "pt-2", ratefacts("RAISE", "IMP", 2, "MEDIUM", "ISOLATED"), "Round 2."));
        assertThat(s1.points()).containsKey("pt-1");
        assertThat(s1.points()).doesNotContainKey("pt-2");
    }

    @Test
    void roundBounded_includesAtExactlyMaxRound() {
        var bounded = new DebateChannelProjection.RoundBoundedProjection(2, proj);
        ConversationState s = bounded.apply(bounded.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 2, "HIGH", "ISOLATED"), "Exactly round 2."));
        assertThat(s.points()).containsKey("pt-1");
    }

    @Test
    void roundBounded_delegatesIdentityToDelegate() {
        var bounded = new DebateChannelProjection.RoundBoundedProjection(3, proj);
        assertThat(bounded.identity().points()).isEmpty();
        assertThat(bounded.identity().memos()).isEmpty();
    }

    // ── ROUND_SNAPSHOT interception ──────────────────────────────────────────

    @Test
    void roundSnapshot_entry_returns_state_unchanged() {
        ConversationState initial = proj.identity();

        String metaHeader = "entryType=ROUND_SNAPSHOT|role=SYS|round=2|commitHash=abc123|documentPath=spec.md";
        MessageView message = msg(MessageType.QUERY, null, metaHeader,
                "Round 2 snapshot — 3 raised, 2 fixed");

        ConversationState result = proj.apply(initial, message);

        assertThat(result).isSameAs(initial);
    }
}
