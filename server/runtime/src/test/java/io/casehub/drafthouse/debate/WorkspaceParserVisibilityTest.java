package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceParserVisibilityTest {

    private final Path fixture = Path.of("src/test/resources/fixtures/workspace-replay");

    @Test
    void discoverMaxRound_finds_highest_round() {
        int max = WorkspaceParser.discoverMaxRound(fixture.resolve("responses"));
        assertTrue(max >= 1, "fixture should have at least 1 round");
    }

    @Test
    void parseRoundFromMarkdown_returns_issues_for_round_1() {
        Set<String> existing = new HashSet<>();
        var round = WorkspaceParser.parseRoundFromMarkdown(
                fixture.resolve("responses"), 1, existing);
        assertNotNull(round);
        assertEquals(1, round.roundNumber());
        assertFalse(round.issues().isEmpty(), "round 1 should have issues");
    }

    @Test
    void parseTracker_returns_entries() {
        var entries = WorkspaceParser.parseTracker(fixture);
        assertNotNull(entries);
    }
}
