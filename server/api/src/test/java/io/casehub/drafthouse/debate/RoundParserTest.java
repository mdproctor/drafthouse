package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RoundParserTest {

    private final RoundParser parser = new RoundParser();

    @Test
    void parsesRaiseEntry() {
        String snippet = """
            TYPE: raise
            PRIORITY: P1
            SCOPE: Isolated
            LOCATION: §3.2
            CONTENT: Both start_review and begin_review appear.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        DebateEntry e = entries.get(0);
        assertThat(e.type()).isEqualTo(EntryType.RAISE);
        assertThat(e.priority()).isEqualTo(Priority.P1);
        assertThat(e.scope()).isEqualTo(Scope.ISOLATED);
        assertThat(e.location()).isEqualTo("§3.2");
        assertThat(e.content()).isEqualTo("Both start_review and begin_review appear.");
        assertThat(e.targetId()).isNull();
    }

    @Test
    void parsesAgreeEntry() {
        String snippet = """
            TYPE: agree
            TARGET: R1-REV-001
            STATUS: Agreed
            CONTENT: Standardising to start_review throughout.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        DebateEntry e = entries.get(0);
        assertThat(e.type()).isEqualTo(EntryType.AGREE);
        assertThat(e.targetId()).isEqualTo("R1-REV-001");
        assertThat(e.statusDirective()).isEqualTo(ReviewStatus.AGREED);
        assertThat(e.priority()).isNull();
    }

    @Test
    void parsesDisputeEntry() {
        String snippet = """
            TYPE: dispute
            TARGET: R1-REV-002
            STATUS: Active
            CONTENT: Retry is caller responsibility per MCP contract.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.DISPUTE);
        assertThat(entries.get(0).statusDirective()).isEqualTo(ReviewStatus.ACTIVE);
    }

    @Test
    void parsesQualifyEntry() {
        String snippet = """
            TYPE: qualify
            TARGET: R2-IMP-001
            STATUS: Active
            CONTENT: Accepted on substance but contract reference must be cited.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.QUALIFY);
    }

    @Test
    void parsesFlagHumanEntry() {
        String snippet = """
            TYPE: flag_human
            CONTENT: REV and IMP differ on MCP contract scope.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.FLAG_HUMAN);
    }

    @Test
    void parsesMultipleEntriesAndIgnoresMemo() {
        String snippet = """
            TYPE: raise
            PRIORITY: P2
            SCOPE: Systemic
            CONTENT: Missing error handling.

            TYPE: agree
            TARGET: R1-REV-001
            STATUS: Agreed
            CONTENT: Fixed.

            MEMO: Section 4 needs more work.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.RAISE);
        assertThat(entries.get(1).type()).isEqualTo(EntryType.AGREE);
    }

    @Test
    void isCaseInsensitiveOnTypeField() {
        String snippet = """
            TYPE: RAISE
            PRIORITY: p2
            SCOPE: isolated
            CONTENT: Test.
            """;
        List<DebateEntry> entries = parser.parse(snippet);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo(EntryType.RAISE);
    }
}
