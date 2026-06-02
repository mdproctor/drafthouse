package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DebateEntryFormatterTest {

    private final DebateEntryFormatter formatter = new DebateEntryFormatter();

    @Test
    void assignsSequentialIds() {
        var entries = List.of(
                new DebateEntry(EntryType.RAISE, null, "Point A.", null, Priority.P1, Scope.ISOLATED, "§3.2"),
                new DebateEntry(EntryType.RAISE, null, "Point B.", null, Priority.P2, Scope.SYSTEMIC, null));
        String result = formatter.format(entries, 1, AgentType.REV, "");
        assertThat(result).contains("R1-REV-001");
        assertThat(result).contains("R1-REV-002");
    }

    @Test
    void incrementsSeqFromExistingDebate() {
        String existingDebate = "<a name=\"R1-REV-001\"></a>\n**[R1-REV-001]** `raise`";
        var entries = List.of(
                new DebateEntry(EntryType.RAISE, null, "Point B.", null, Priority.P2, Scope.ISOLATED, null));
        String result = formatter.format(entries, 1, AgentType.REV, existingDebate);
        assertThat(result).contains("R1-REV-002");
        assertThat(result).doesNotContain("R1-REV-001");
    }

    @Test
    void includesRoundCommentBoundary() {
        var entries = List.of(
                new DebateEntry(EntryType.RAISE, null, "Point.", null, Priority.P1, Scope.ISOLATED, null));
        String result = formatter.format(entries, 3, AgentType.IMP, "");
        assertThat(result).contains("<!-- Round 3 — Implementer -->");
    }

    @Test
    void includesHtmlAnchor() {
        var entries = List.of(
                new DebateEntry(EntryType.RAISE, null, "Point.", null, Priority.P1, Scope.ISOLATED, "§4.1"));
        String result = formatter.format(entries, 1, AgentType.REV, "");
        assertThat(result).contains("<a name=\"R1-REV-001\"></a>");
    }

    @Test
    void includesStatusDirectiveOnResponse() {
        var entries = List.of(
                new DebateEntry(EntryType.AGREE, "R1-REV-001", "Agreed.", ReviewStatus.AGREED, null, null, null));
        String result = formatter.format(entries, 2, AgentType.IMP, "");
        assertThat(result).contains("→ [R1-REV-001] Status: ✅ Agreed");
    }

    @Test
    void orderingIsRaiseThenResponsesThenFlags() {
        var entries = List.of(
                new DebateEntry(EntryType.FLAG_HUMAN, null, "Need help.", null, null, null, null),
                new DebateEntry(EntryType.RAISE, null, "Point.", null, Priority.P1, Scope.ISOLATED, null),
                new DebateEntry(EntryType.AGREE, "R1-REV-001", "OK.", ReviewStatus.AGREED, null, null, null));
        String result = formatter.format(entries, 1, AgentType.REV, "");
        int raisePos = result.indexOf("`raise`");
        int agreePos = result.indexOf("`agree`");
        int flagPos  = result.indexOf("`flag_human`");
        assertThat(raisePos).isLessThan(agreePos);
        assertThat(agreePos).isLessThan(flagPos);
    }
}
