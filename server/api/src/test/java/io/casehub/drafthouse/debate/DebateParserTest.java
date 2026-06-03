package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DebateParserTest {

    private final DebateParser parser = new DebateParser();

    private static final String ROUND1_AND_2 = """
            # Debate Log
            **Spec:** /path/to/spec.md
            **Session:** drafthouse-20260602-a3f2

            ---

            <!-- Round 1 — Reviewer -->

            <a name="R1-REV-001"></a>
            **[R1-REV-001]** `raise` · P1 · Isolated · §3.2
            Both `start_review` and `begin_review` appear.
            Status: 🔴 Open

            <a name="R1-REV-002"></a>
            **[R1-REV-002]** `raise` · P2 · Systemic · §4.1
            No stated behaviour on network failure.
            Status: 🔴 Open

            **REV memo R1:** §4 feels under-specified.

            ---

            <!-- Round 2 — Implementer -->

            <a name="R2-IMP-001"></a>
            **[R2-IMP-001]** `agree` · → [R1-REV-001]
            Standardising to `start_review`.
            → [R1-REV-001] Status: ✅ Agreed

            <a name="R2-IMP-002"></a>
            **[R2-IMP-002]** `dispute` · → [R1-REV-002]
            Retry is caller responsibility.
            → [R1-REV-002] Status: 🟡 Active

            **IMP memo R2:** Reviewer's pattern concern may hold.
            """;

    @Test
    void parsesRaiseEvents() {
        List<DebateEvent> events = parser.parse(ROUND1_AND_2);
        List<DebateEvent.RaiseEvent> raises = events.stream()
                .filter(e -> e instanceof DebateEvent.RaiseEvent)
                .map(e -> (DebateEvent.RaiseEvent) e)
                .toList();
        assertThat(raises).hasSize(2);
        assertThat(raises.get(0).entryId()).isEqualTo("R1-REV-001");
        assertThat(raises.get(0).priority()).isEqualTo(Priority.P1);
        assertThat(raises.get(0).scope()).isEqualTo(Scope.ISOLATED);
        assertThat(raises.get(0).location()).isEqualTo("§3.2");
        assertThat(raises.get(0).agent()).isEqualTo(AgentType.REV);
        assertThat(raises.get(0).round()).isEqualTo(1);
        assertThat(raises.get(0).content()).isEqualTo("Both `start_review` and `begin_review` appear.");
    }

    @Test
    void parsesResponseEvents() {
        List<DebateEvent> events = parser.parse(ROUND1_AND_2);
        List<DebateEvent.ResponseEvent> responses = events.stream()
                .filter(e -> e instanceof DebateEvent.ResponseEvent)
                .map(e -> (DebateEvent.ResponseEvent) e)
                .toList();
        assertThat(responses).hasSize(2);

        DebateEvent.ResponseEvent agree = responses.get(0);
        assertThat(agree.type()).isEqualTo(EntryType.AGREE);
        assertThat(agree.targetId()).isEqualTo("R1-REV-001");
        assertThat(agree.agent()).isEqualTo(AgentType.IMP);
        assertThat(agree.statusDirective()).isEqualTo(ReviewStatus.AGREED);

        DebateEvent.ResponseEvent dispute = responses.get(1);
        assertThat(dispute.type()).isEqualTo(EntryType.DISPUTE);
        assertThat(dispute.targetId()).isEqualTo("R1-REV-002");
        assertThat(dispute.statusDirective()).isEqualTo(ReviewStatus.ACTIVE);
    }

    @Test
    void parsesMemos() {
        List<DebateEvent> events = parser.parse(ROUND1_AND_2);
        List<DebateEvent.AgentMemo> memos = events.stream()
                .filter(e -> e instanceof DebateEvent.AgentMemo)
                .map(e -> (DebateEvent.AgentMemo) e)
                .toList();
        assertThat(memos).hasSize(2);
        assertThat(memos.get(0).content()).contains("§4 feels under-specified");
        assertThat(memos.get(0).agent()).isEqualTo(AgentType.REV);
        assertThat(memos.get(1).agent()).isEqualTo(AgentType.IMP);
    }

    @Test
    void preservesDocumentOrder() {
        List<DebateEvent> events = parser.parse(ROUND1_AND_2);
        // Expected: Raise, Raise, Memo(REV), Response(agree), Response(dispute), Memo(IMP)
        assertThat(events).hasSize(6);
        assertThat(events.get(0)).isInstanceOf(DebateEvent.RaiseEvent.class);
        assertThat(events.get(1)).isInstanceOf(DebateEvent.RaiseEvent.class);
        assertThat(events.get(2)).isInstanceOf(DebateEvent.AgentMemo.class);
        assertThat(events.get(3)).isInstanceOf(DebateEvent.ResponseEvent.class);
        assertThat(events.get(4)).isInstanceOf(DebateEvent.ResponseEvent.class);
        assertThat(events.get(5)).isInstanceOf(DebateEvent.AgentMemo.class);
    }

    @Test
    void emptyDebateReturnsEmptyList() {
        assertThat(parser.parse("# Debate Log\n**Spec:** /spec.md\n")).isEmpty();
    }
}
