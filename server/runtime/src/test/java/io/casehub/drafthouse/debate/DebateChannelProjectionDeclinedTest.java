package io.casehub.drafthouse.debate;

import io.casehub.blocks.conversation.ConversationState;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.Test;
import java.util.List;

import static io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests DECLINED entry type folds correctly via {@link DebateChannelProjection}.
 * DECLINED maps to status "DECLINED" via statusAfter() — same pattern as
 * AGREE/DISPUTE/COUNTER/QUALIFY.
 */
class DebateChannelProjectionDeclinedTest {

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

    // ── DECLINED fold behaviour ───────────────────────────────────────────────

    @Test
    void declined_transitionsToDeclined_appendsToThread() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));

        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DECLINED", "IMP", 2), "Declined."));

        assertThat(s1.points().get("pt-1").status()).isEqualTo("DECLINED");
        assertThat(s1.points().get("pt-1").thread()).hasSize(2);
        assertThat(s1.points().get("pt-1").thread().get(1).entryType()).isEqualTo("DECLINED");
        assertThat(s1.points().get("pt-1").thread().get(1).role()).isEqualTo("IMP");
        assertThat(s1.points().get("pt-1").thread().get(1).round()).isEqualTo(2);
        assertThat(s1.points().get("pt-1").thread().get(1).content()).isEqualTo("Declined.");
    }

    @Test
    void declined_onNonExistentPoint_stateUnchanged() {
        ConversationState s0 = proj.identity();
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-nonexistent", ratefacts("DECLINED", "IMP", 1), "Declined."));
        assertThat(s1.points()).isEmpty();
    }

    @Test
    void declined_withoutCorrelationId_stateUnchanged() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));

        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, null, ratefacts("DECLINED", "IMP", 2), "Declined."));

        assertThat(s1.points().get("pt-1").status()).isEqualTo("OPEN");
    }

    @Test
    void declined_missingRole_stateUnchanged() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));

        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", "entryType=DECLINED|round=2", "Declined."));

        assertThat(s1.points().get("pt-1").status()).isEqualTo("OPEN");
    }

    @Test
    void declined_afterAgree_transitionsFromAgreedToDeclined() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "REV", 1, "HIGH", "ISOLATED"), "Issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DONE, "pt-1", ratefacts("AGREE", "IMP", 2), "Agreed."));
        ConversationState s2 = proj.apply(s1,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DECLINED", "REV", 3), "Actually, declined."));

        assertThat(s2.points().get("pt-1").status()).isEqualTo("DECLINED");
        assertThat(s2.points().get("pt-1").thread()).hasSize(3);
    }

    @Test
    void declined_byRevAgent_foldsCorrectly() {
        ConversationState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("RAISE", "IMP", 1, "MEDIUM", "SYSTEMIC"), "IMP issue."));
        ConversationState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("DECLINED", "REV", 2), "REV declines."));

        assertThat(s1.points().get("pt-1").status()).isEqualTo("DECLINED");
        assertThat(s1.points().get("pt-1").thread().get(1).role()).isEqualTo("REV");
    }
}
