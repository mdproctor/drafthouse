package io.casehub.drafthouse.debate;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewChannelProjectionTest {

    private final ReviewChannelProjection proj = new ReviewChannelProjection();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MessageView query(String correlationId, ActorType actorType,
                                     Priority p, Scope s, String loc, String content) {
        String artefactRefs = "entryId=" + correlationId + "|priority=" + p
                + "|scope=" + s + "|location=" + (loc != null ? loc : "");
        return new MessageView(null, null, "test", MessageType.QUERY,
                content, correlationId, null, null, artefactRefs, actorType, null, null, 0);
    }

    private static MessageView respond(String correlationId, ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.RESPONSE,
                content, correlationId, null, null, null, actorType, null, null, 0);
    }

    private static MessageView done(String correlationId, ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.DONE,
                content, correlationId, null, null, null, actorType, null, null, 0);
    }

    private static MessageView decline(String correlationId, ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.DECLINE,
                content, correlationId, null, null, null, actorType, null, null, 0);
    }

    private static MessageView handoff(String correlationId, ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.HANDOFF,
                content, correlationId, null, "human", null, actorType, null, null, 0);
    }

    private static MessageView event(ActorType actorType, String content) {
        return new MessageView(null, null, "test", MessageType.EVENT,
                content, null, null, null, null, actorType, null, null, 0);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void identity_returnsEmptyState_andFreshInstanceEachCall() {
        ReviewState s1 = proj.identity();
        ReviewState s2 = proj.identity();
        assertThat(s1.points()).isEmpty();
        assertThat(s1.humanFlags()).isEmpty();
        assertThat(s1).isNotSameAs(s2);
    }

    @Test
    void apply_query_createsOpenPoint_humanMapsToRev() {
        ReviewState s = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, "§3.2", "Point A."));
        assertThat(s.points()).containsKey("R1");
        ReviewPoint p = s.points().get("R1");
        assertThat(p.currentStatus()).isEqualTo(ReviewStatus.OPEN);
        assertThat(p.classification().priority()).isEqualTo(Priority.P1);
        assertThat(p.thread()).hasSize(1);
        assertThat(p.thread().get(0).type()).isEqualTo(EntryType.RAISE);
        assertThat(p.thread().get(0).agent()).isEqualTo(AgentType.REV);
    }

    @Test
    void apply_done_agrees_point_agentMapsToImp() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, done("R1", ActorType.AGENT, "Answer."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(s1.points().get("R1").thread().get(1).agent()).isEqualTo(AgentType.IMP);
        assertThat(s1.points().get("R1").thread().get(1).type()).isEqualTo(EntryType.AGREE);
        assertThat(s1.points().get("R1").thread().get(1).content()).isEqualTo("Answer.");
    }

    @Test
    void apply_response_qualifies_point() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, respond("R1", ActorType.AGENT, "Partial."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("R1").thread().get(1).type()).isEqualTo(EntryType.QUALIFY);
        assertThat(s1.points().get("R1").thread().get(1).content()).isEqualTo("Partial.");
    }

    @Test
    void apply_response_noLongerAgreesViaPrefix() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        // RESPONSE always means QUALIFY now — the old "[QUALIFY] " prefix is dead
        ReviewState s1 = proj.apply(s0, respond("R1", ActorType.AGENT, "[QUALIFY] Old prefix."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("R1").thread().get(1).type()).isEqualTo(EntryType.QUALIFY);
        // Content is passed through verbatim — no prefix stripping
        assertThat(s1.points().get("R1").thread().get(1).content()).isEqualTo("[QUALIFY] Old prefix.");
    }

    @Test
    void apply_decline_transitionsToDeclined() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, decline("R1", ActorType.AGENT, "Out of scope."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.DECLINED);
        assertThat(s1.points().get("R1").thread().get(1).type()).isEqualTo(EntryType.DECLINED);
    }

    @Test
    void apply_decline_nullCorrelationId_stateUnchanged_noLog() {
        ReviewState s0 = proj.identity();
        ReviewState s1 = proj.apply(s0, decline(null, ActorType.AGENT, "Out of scope."));
        assertThat(s1).isSameAs(s0);
    }

    @Test
    void apply_decline_unknownCorrelationId_stateUnchanged() {
        ReviewState s0 = proj.identity();
        ReviewState s1 = proj.apply(s0, decline("UNKNOWN", ActorType.AGENT, "Out of scope."));
        assertThat(s1.points()).isEmpty();
    }

    @Test
    void apply_decline_nullContent_threadEntryIsEmptyString() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, new MessageView(null, null, "test", MessageType.DECLINE,
                null, "R1", null, null, null, ActorType.AGENT, null, null, 0));
        assertThat(s1.points().get("R1").thread().get(1).content()).isNotNull().isEqualTo("");
    }

    @Test
    void apply_handoff_nullContent_doesNotProduceNullInThread() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, new MessageView(null, null, "test", MessageType.HANDOFF,
                null, "R1", null, "human", null, ActorType.AGENT, null, null, 0));
        assertThat(s1.points().get("R1").thread().get(1).content()).isNotNull();
        assertThat(s1.humanFlags().get(0).content()).isNotNull();
    }

    @Test
    void apply_handoff_transitionsToPendingHuman() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        ReviewState s1 = proj.apply(s0, handoff("R1", ActorType.AGENT, "Needs human."));
        assertThat(s1.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.PENDING_HUMAN);
        assertThat(s1.humanFlags()).hasSize(1);
    }

    @Test
    void apply_event_isNoOp() {
        ReviewState s0 = proj.identity();
        assertThat(proj.apply(s0, event(ActorType.AGENT, "Internal.")).points()).isEmpty();
    }

    @Test
    void apply_doesNotMutateInputState() {
        ReviewState initial = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        proj.apply(initial, done("R1", ActorType.AGENT, "A."));
        assertThat(initial.points().get("R1").currentStatus()).isEqualTo(ReviewStatus.OPEN);
    }

    @Test
    void agentType_nullActorType_throwsIAE() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        assertThatThrownBy(() -> proj.apply(s0, new MessageView(null, null, "test",
                MessageType.RESPONSE, "A.", "R1", null, null, null, null, null, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentType_systemActorType_throwsIAE() {
        ReviewState s0 = proj.apply(proj.identity(),
                query("R1", ActorType.HUMAN, Priority.P1, Scope.ISOLATED, null, "Q?"));
        assertThatThrownBy(() -> proj.apply(s0, new MessageView(null, null, "test",
                MessageType.RESPONSE, "A.", "R1", null, null, null, ActorType.SYSTEM, null, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
