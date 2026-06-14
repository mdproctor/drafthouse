package io.casehub.drafthouse.debate;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.Test;
import static io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests DECLINED entry type folds correctly into ReviewState.
 * DECLINED must call appendToPoint() and set status to DECLINED — same pattern as
 * AGREE/DISPUTE/COUNTER/QUALIFY.
 */
class DebateChannelProjectionDeclinedTest {

    private final DebateChannelProjection proj = new DebateChannelProjection();

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a MessageView with metadata encoded in content as META header.
     * The projection reads metadata from content (not artefactRefs), because
     * Qhorus ArtefactRefParser validates artefactRefs as CSV UUIDs.
     */
    private static MessageView msg(MessageType type, String correlationId, String metaHeader, String bodyContent) {
        String encodedContent = META_SENTINEL + metaHeader + "\n\n" + bodyContent;
        return new MessageView(null, null, "test-sender", type,
                encodedContent, correlationId, null, null, null, ActorType.AGENT, null, null, 0);
    }

    private static String ratefacts(String entryType, String agent, int round) {
        return "entryType=" + entryType + "|agent=" + agent + "|round=" + round;
    }

    private static String ratefacts(String entryType, String agent, int round, String priority, String scope) {
        return "entryType=" + entryType + "|agent=" + agent + "|round=" + round
                + "|priority=" + priority + "|scope=" + scope;
    }

    // ── DECLINED fold behaviour ───────────────────────────────────────────────

    @Test
    void declined_transitionsToDeclined_appendsToThread() {
        // RAISE a point in round 1
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "P1", "ISOLATED"), "Issue."));

        // DECLINED in round 2
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DECLINED", "IMP", 2), "Declined."));

        // Verify status changed to DECLINED
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.DECLINED);

        // Verify thread has two entries: RAISE + DECLINED
        assertThat(s1.points().get("pt-1").thread()).hasSize(2);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.DECLINED);
        assertThat(s1.points().get("pt-1").thread().get(1).agent()).isEqualTo(AgentType.IMP);
        assertThat(s1.points().get("pt-1").thread().get(1).round()).isEqualTo(2);
        assertThat(s1.points().get("pt-1").thread().get(1).content()).isEqualTo("Declined.");
    }

    @Test
    void declined_onNonExistentPoint_stateUnchanged_loggedWarning() {
        // DECLINED targeting a point that doesn't exist should be discarded
        ReviewState s0 = proj.identity();
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-nonexistent", ratefacts("DECLINED", "IMP", 1), "Declined."));

        // State unchanged
        assertThat(s1.points()).isEmpty();
    }

    @Test
    void declined_withoutCorrelationId_stateUnchanged() {
        // DECLINED without correlationId cannot target a point
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "P1", "ISOLATED"), "Issue."));

        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, null, ratefacts("DECLINED", "IMP", 2), "Declined."));

        // Point should remain OPEN
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.OPEN);
    }

    @Test
    void declined_missingAgent_stateUnchanged() {
        // Missing agent= is protocol violation — message discarded
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "P1", "ISOLATED"), "Issue."));

        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", "entryType=DECLINED|round=2", "Declined."));

        // Point should remain OPEN
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.OPEN);
    }

    @Test
    void declined_afterAgree_transitionsFromAgreedToDeclined() {
        // RAISE → AGREE → DECLINED should end at DECLINED
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DONE, "pt-1", ratefacts("AGREE", "IMP", 2), "Agreed."));
        ReviewState s2 = proj.apply(s1,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DECLINED", "REV", 3), "Actually, declined."));

        assertThat(s2.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.DECLINED);
        assertThat(s2.points().get("pt-1").thread()).hasSize(3);
    }

    @Test
    void declined_byRevAgent_foldsCorrectly() {
        // Test REV agent can DECLINE (not just IMP)
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "IMP", 1, "P2", "SYSTEMIC"), "IMP issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DECLINED", "REV", 2), "REV declines."));

        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.DECLINED);
        assertThat(s1.points().get("pt-1").thread().get(1).agent()).isEqualTo(AgentType.REV);
    }
}
