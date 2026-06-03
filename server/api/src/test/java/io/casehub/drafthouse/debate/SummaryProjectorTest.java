package io.casehub.drafthouse.debate;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SummaryProjectorTest {

    private final SummaryProjector projector = new SummaryProjector();

    private MessageView raise(String entryId, AgentType agent, Priority p, Scope s, String location, String content) {
        String artefactRefs = "entryId=" + entryId + "|priority=" + p + "|scope=" + s
                + "|location=" + (location != null ? location : "");
        return new MessageView(null, null, agent.name(), MessageType.QUERY,
                content, entryId, null, null, artefactRefs, null, null, null, 0);
    }

    private MessageView agree(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.RESPONSE,
                content, targetId, null, null, null, null, null, null, 0);
    }

    private MessageView dispute(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.DECLINE,
                content, targetId, null, null, null, null, null, null, 0);
    }

    private MessageView qualify(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.RESPONSE,
                "[QUALIFY] " + content, targetId, null, null, null, null, null, null, 0);
    }

    private MessageView flag(AgentType agent, String targetId, String content) {
        return new MessageView(null, null, agent.name(), MessageType.HANDOFF,
                content, targetId, null, "human", null, null, null, null, 0);
    }

    private MessageView memo(AgentType agent, String content) {
        return new MessageView(null, null, agent.name(), MessageType.EVENT,
                content, null, null, null, null, null, null, null, 0);
    }

    @Test
    void identityReturnsEmptyState() {
        ReviewState state = projector.identity();
        assertThat(state.points()).isEmpty();
        assertThat(state.humanFlags()).isEmpty();
    }

    @Test
    void identityReturnsFreshInstanceEachCall() {
        assertThat(projector.identity()).isNotSameAs(projector.identity());
    }

    @Test
    void raiseCreatesReviewPoint() {
        ReviewState state = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P1, Scope.ISOLATED, "§3.2", "Point A."));
        assertThat(state.points()).containsKey("R1-REV-001");
        ReviewPoint point = state.points().get("R1-REV-001");
        assertThat(point.currentStatus()).isEqualTo(ReviewStatus.OPEN);
        assertThat(point.classification().priority()).isEqualTo(Priority.P1);
        assertThat(point.thread()).hasSize(1);
        assertThat(point.thread().get(0).type()).isEqualTo(EntryType.RAISE);
    }

    @Test
    void agreeTransitionsToAgreed() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P1, Scope.ISOLATED, null, "Point A."));
        ReviewState s1 = projector.apply(s0, agree(AgentType.IMP, "R1-REV-001", "Agreed."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(s1.points().get("R1-REV-001").thread()).hasSize(2);
    }

    @Test
    void disputeTransitionsToActive() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P2, Scope.SYSTEMIC, null, "Point A."));
        ReviewState s1 = projector.apply(s0, dispute(AgentType.IMP, "R1-REV-001", "Disagree."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
    }

    @Test
    void qualifyTransitionsToActive() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P2, Scope.ISOLATED, null, "Point A."));
        ReviewState s1 = projector.apply(s0, qualify(AgentType.IMP, "R1-REV-001", "Partially accepted."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);
        assertThat(s1.points().get("R1-REV-001").thread().get(1).type()).isEqualTo(EntryType.QUALIFY);
    }

    @Test
    void flagHumanTransitionsToPendingHuman() {
        ReviewState s0 = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P1, Scope.ISOLATED, null, "Point A."));
        ReviewState s1 = projector.apply(s0, flag(AgentType.REV, "R1-REV-001", "Human needed."));
        assertThat(s1.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.PENDING_HUMAN);
        assertThat(s1.humanFlags()).hasSize(1);
        assertThat(s1.humanFlags().get(0).content()).isEqualTo("Human needed.");
    }

    @Test
    void memoIsNoOp() {
        ReviewState s0 = projector.identity();
        ReviewState s1 = projector.apply(s0, memo(AgentType.REV, "Private thought."));
        assertThat(s1.points()).isEmpty();
        assertThat(s1.humanFlags()).isEmpty();
    }

    @Test
    void projectConvenienceMethodFoldsEvents() {
        List<DebateEvent> events = List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, "§3.2", "Point A."),
                new DebateEvent.ResponseEvent(2, AgentType.IMP, "R1-REV-001",
                        EntryType.AGREE, "Agreed.", ReviewStatus.AGREED)
        );
        ReviewState state = projector.project(events);
        assertThat(state.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
    }

    @Test
    void applyDoesNotMutateInputState() {
        ReviewState initial = projector.apply(projector.identity(),
                raise("R1-REV-001", AgentType.REV, Priority.P1, Scope.ISOLATED, null, "Point A."));

        int originalPointCount = initial.points().size();

        // Apply a response — must not mutate initial
        projector.apply(initial, agree(AgentType.IMP, "R1-REV-001", "Agreed."));

        // initial must be unchanged
        assertThat(initial.points()).hasSize(originalPointCount);
        assertThat(initial.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.OPEN);
    }

    @Test
    void incrementalFoldOnlyProcessesNewEvents() {
        List<DebateEvent> allEvents = List.of(
                new DebateEvent.RaiseEvent("R1-REV-001", 1, AgentType.REV,
                        Priority.P1, Scope.ISOLATED, null, "Point A."),
                new DebateEvent.ResponseEvent(2, AgentType.IMP, "R1-REV-001",
                        EntryType.AGREE, "Agreed.", ReviewStatus.AGREED)
        );
        ReviewState afterRound1 = projector.project(allEvents.subList(0, 1));
        ReviewState afterRound2 = projector.projectIncremental(afterRound1, allEvents, 1);
        assertThat(afterRound2.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
    }
}
