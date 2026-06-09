package io.casehub.drafthouse.debate;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import io.casehub.qhorus.api.spi.ProjectionResult;
import org.junit.jupiter.api.Test;
import static io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL;
import static org.assertj.core.api.Assertions.*;

class DebateChannelProjectionTest {

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

    // ── raise ─────────────────────────────────────────────────────────────────

    @Test
    void raise_createsOpenPoint_revAgent_roundPopulated() {
        ReviewState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1",
                        ratefacts("raise", "REV", 1, "P1", "ISOLATED"),
                        "This is the raise content."));
        assertThat(s.points()).containsKey("pt-1");
        ReviewPoint p = s.points().get("pt-1");
        assertThat(p.currentStatus()).isEqualTo(ReviewStatus.OPEN);
        assertThat(p.thread()).hasSize(1);
        assertThat(p.thread().get(0).type()).isEqualTo(EntryType.RAISE);
        assertThat(p.thread().get(0).agent()).isEqualTo(AgentType.REV);
        assertThat(p.thread().get(0).round()).isEqualTo(1);
        assertThat(p.thread().get(0).content()).isEqualTo("This is the raise content.");
    }

    @Test
    void raise_impAgent_mapsToIMP() {
        ReviewState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-2", ratefacts("raise", "IMP", 2, "P2", "SYSTEMIC"), "IMP point."));
        assertThat(s.points().get("pt-2").thread().get(0).agent()).isEqualTo(AgentType.IMP);
    }

    // ── agree ─────────────────────────────────────────────────────────────────

    @Test
    void agree_transitionsToAgreed() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DONE, "pt-1", ratefacts("agree", "IMP", 2), "Agreed."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.AGREE);
        assertThat(s1.points().get("pt-1").thread().get(1).agent()).isEqualTo(AgentType.IMP);
    }

    // ── dispute ───────────────────────────────────────────────────────────────

    @Test
    void dispute_transitionsToDisputed_notDeclined() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.DECLINE, "pt-1", ratefacts("dispute", "IMP", 2), "I disagree."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.DISPUTED);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.DISPUTE);
    }

    // ── qualify ───────────────────────────────────────────────────────────────

    @Test
    void qualify_transitionsToActive_entryTypeQualify() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.RESPONSE, "pt-1", ratefacts("qualify", "IMP", 2), "Partially."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.QUALIFY);
    }

    // ── counter ───────────────────────────────────────────────────────────────

    @Test
    void counter_transitionsToActive_entryTypeCounter_distinctFromQualify() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.RESPONSE, "pt-1", ratefacts("counter", "IMP", 2), "My counter."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("pt-1").thread().get(1).type()).isEqualTo(EntryType.COUNTER);
    }

    // ── flag-human ────────────────────────────────────────────────────────────

    @Test
    void flagHuman_transitionsToPendingHuman_flagEntryRoundPopulated() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.HANDOFF, "pt-1", ratefacts("flag-human", "REV", 3), "Human needed."));
        assertThat(s1.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.PENDING_HUMAN);
        assertThat(s1.humanFlags()).hasSize(1);
        assertThat(s1.humanFlags().get(0).round()).isEqualTo(3);
        assertThat(s1.humanFlags().get(0).content()).isEqualTo("Human needed.");
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    void unknownEntryType_stateUnchanged() {
        ReviewState s0 = proj.identity();
        ReviewState s1 = proj.apply(s0,
                msg(MessageType.QUERY, "pt-1", "entryType=unknown|agent=REV|round=1", "?"));
        assertThat(s1.points()).isEmpty();
    }

    @Test
    void missingAgent_throwsIAE() {
        ReviewState s0 = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        assertThatThrownBy(() -> proj.apply(s0,
                msg(MessageType.DONE, "pt-1", "entryType=agree|round=2", "Agreed.")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullActorType_doesNotThrow() {
        // DebateChannelProjection uses content META header for agent — actorType is never read
        ReviewState s = proj.apply(proj.identity(),
                new MessageView(null, null, "test", MessageType.QUERY,
                        META_SENTINEL + "entryType=raise|agent=REV|round=1|priority=P1|scope=ISOLATED\n\nContent.",
                        "pt-1", null, null, null, null, null, null, 0));
        assertThat(s.points()).containsKey("pt-1");
    }

    @Test
    void apply_doesNotMutateInputState() {
        ReviewState after_raise = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-1", ratefacts("raise", "REV", 1, "P1", "ISOLATED"), "Issue."));
        proj.apply(after_raise,
                msg(MessageType.DONE, "pt-1", ratefacts("agree", "IMP", 2), "Done."));
        assertThat(after_raise.points().get("pt-1").currentStatus()).isEqualTo(ReviewStatus.OPEN);
    }

    @Test
    void apply_oldMetaSentinel_treatedAsPlainContent_notParsed() {
        // "META:" without the SOH prefix is no longer recognised as structured content
        ReviewState s = proj.apply(proj.identity(),
                new MessageView(null, null, "test", MessageType.QUERY,
                        "META:entryType=raise|agent=REV|round=1|priority=P1|scope=ISOLATED\n\nBody.",
                        "pt-old", null, null, null, ActorType.AGENT, null, null, 0));
        assertThat(s.points()).isEmpty();
    }

    @Test
    void apply_newSentinelWithUnknownEntryType_stateUnchanged() {
        // Sentinel present but entryType value is unknown → state unchanged
        ReviewState s = proj.apply(proj.identity(),
                msg(MessageType.QUERY, "pt-y", "entryType=unknown|agent=REV|round=1", "?"));
        assertThat(s.points()).isEmpty();
    }

    @Test
    void identity_hasEmptyMemosAndSubTaskFindings() {
        ReviewState s = new DebateChannelProjection().identity();
        assertThat(s.memos()).isEmpty();
        assertThat(s.subTaskFindings()).isEmpty();
    }

    @Test
    void projectionName_returnsDebateSummary() {
        assertThat(proj.projectionName()).isEqualTo("debate-summary");
    }

    @Test
    void render_emptyResult_returnsNonBlankSentinel() {
        ProjectionResult<ReviewState> empty = new ProjectionResult<>(proj.identity(), null);
        assertThat(proj.render(empty)).isNotBlank();
    }
}
