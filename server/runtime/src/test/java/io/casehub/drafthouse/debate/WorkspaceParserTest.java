package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceParserTest {

    private static WorkspaceParser.WorkspaceParseResult result;

    @BeforeAll
    static void parse() {
        Path fixture = Path.of("src/test/resources/fixtures/workspace-replay");
        result = WorkspaceParser.parse(fixture);
    }

    @Test
    void metadata_extracted() {
        assertEquals("/tmp/test-spec.md", result.specPath());
        assertEquals("spec-review", result.mode());
        assertEquals("This is a test review context note.", result.contextNote().trim());
    }

    @Test
    void round_count() {
        assertEquals(3, result.rounds().size());
    }

    @Test
    void round1_issues() {
        var round1 = result.rounds().get(0);
        assertEquals(1, round1.roundNumber());
        assertEquals(3, round1.issues().size());

        var r101 = round1.issues().get(0);
        assertEquals("R1-01", r101.issueId());
        assertEquals("Missing error handling in parser", r101.title());
        assertTrue(r101.body().contains("parser does not handle malformed input"));
    }

    @Test
    void round1_responses() {
        var round1 = result.rounds().get(0);
        assertEquals(3, round1.responses().size());

        var resp1 = round1.responses().get(0);
        assertEquals("R1-01", resp1.issueId());
        assertEquals("FIXED", resp1.status());
        assertEquals("3.2", resp1.sectionRef());

        var resp2 = round1.responses().get(1);
        assertEquals("R1-02", resp2.issueId());
        assertEquals("REJECTED", resp2.status());

        var resp3 = round1.responses().get(2);
        assertEquals("R1-03", resp3.issueId());
        assertEquals("ESCALATED", resp3.status());
    }

    @Test
    void round1_confirmations_from_reviewer2() {
        var round1 = result.rounds().get(0);
        assertEquals(3, round1.confirmations().size());

        var c1 = round1.confirmations().get(0);
        assertEquals("R1-01", c1.issueId());
        assertTrue(c1.resolved());
        assertFalse(c1.accepted());

        var c2 = round1.confirmations().get(1);
        assertEquals("R1-02", c2.issueId());
        assertFalse(c2.resolved());
        assertTrue(c2.accepted());

        var c3 = round1.confirmations().get(2);
        assertEquals("R1-03", c3.issueId());
        assertFalse(c3.resolved());
        assertFalse(c3.accepted());
    }

    @Test
    void round2_issues() {
        var round2 = result.rounds().get(1);
        assertEquals(2, round2.roundNumber());
        assertEquals(1, round2.issues().size());
        assertEquals("R2-01", round2.issues().get(0).issueId());
        assertEquals("Test coverage below threshold", round2.issues().get(0).title());
    }

    @Test
    void round2_confirmations_from_reviewer3() {
        var round2 = result.rounds().get(1);
        assertEquals(1, round2.confirmations().size());
        var c = round2.confirmations().get(0);
        assertEquals("R2-01", c.issueId());
        assertTrue(c.resolved());
    }

    @Test
    void signal_extraction() {
        assertEquals("CONTINUE", result.rounds().get(0).signal());
        assertEquals("APPROVED", result.rounds().get(1).signal());
        assertEquals("APPROVED", result.rounds().get(2).signal());
    }

    @Test
    void assumption_extraction() {
        var round1 = result.rounds().get(0);
        assertEquals(1, round1.assumptions().size());
        assertEquals("All input files are UTF-8 encoded.", round1.assumptions().get(0));
    }

    @Test
    void settled_decision_extraction() {
        var round2 = result.rounds().get(1);
        assertEquals(1, round2.settledDecisions().size());
        var sd = round2.settledDecisions().get(0);
        assertEquals("Response-envelope pattern is the standard for this API", sd.text());
        assertEquals("R1-02", sd.fromIssue());
    }

    @Test
    void tracker_statuses() {
        assertEquals(4, result.trackerStatuses().size());

        var t1 = result.trackerStatuses().get(0);
        assertEquals("R1-01", t1.issueId());
        assertEquals("Missing error handling in parser", t1.title());
        assertEquals("VERIFIED", t1.status());
        assertEquals("abc123 → def456", t1.evidence());

        var t2 = result.trackerStatuses().get(1);
        assertEquals("R1-02", t2.issueId());
        assertEquals("ACCEPTED", t2.status());
        assertNull(t2.evidence());

        var t3 = result.trackerStatuses().get(2);
        assertEquals("R1-03", t3.issueId());
        assertEquals("DEFERRED", t3.status());

        var t4 = result.trackerStatuses().get(3);
        assertEquals("R2-01", t4.issueId());
        assertEquals("VERIFIED", t4.status());
    }

    @Test
    void known_section_headings_skipped() {
        // "Overview" and "Addressed Items" are known sections — not extracted as issues
        var allIssueIds = result.rounds().stream()
                .flatMap(r -> r.issues().stream())
                .map(WorkspaceParser.ParsedIssue::issueId)
                .toList();
        assertEquals(List.of("R1-01", "R1-02", "R1-03", "R2-01"), allIssueIds);
    }

    @Test
    void trackerEntry_extracts_commitHash_from_evidence() throws Exception {
        java.nio.file.Path tempWorkspace = java.nio.file.Files.createTempDirectory("workspace-parser-test");
        java.nio.file.Files.writeString(tempWorkspace.resolve("tracker.md"),
                "# Tracker\n\n### R1-01: Some issue\n- **Status:** VERIFIED\n- **Spec commit:**  → abc123\n");

        var result = WorkspaceParser.parse(tempWorkspace);
        assertEquals(1, result.trackerStatuses().size());
        assertEquals("abc123", result.trackerStatuses().get(0).commitHash());

        // cleanup
        java.nio.file.Files.deleteIfExists(tempWorkspace.resolve("tracker.md"));
        java.nio.file.Files.deleteIfExists(tempWorkspace);
    }

    @Test
    void trackerEntry_null_commitHash_when_no_evidence() throws Exception {
        java.nio.file.Path tempWorkspace = java.nio.file.Files.createTempDirectory("workspace-parser-test");
        java.nio.file.Files.writeString(tempWorkspace.resolve("tracker.md"),
                "# Tracker\n\n### R1-01: Some issue\n- **Status:** OPEN\n");

        var result = WorkspaceParser.parse(tempWorkspace);
        assertEquals(1, result.trackerStatuses().size());
        assertNull(result.trackerStatuses().get(0).commitHash());

        // cleanup
        java.nio.file.Files.deleteIfExists(tempWorkspace.resolve("tracker.md"));
        java.nio.file.Files.deleteIfExists(tempWorkspace);
    }

    @Test
    void parseResult_reads_projectRepoPath_from_sourceDirs() throws Exception {
        java.nio.file.Path tempWorkspace = java.nio.file.Files.createTempDirectory("workspace-parser-test");
        java.nio.file.Files.writeString(tempWorkspace.resolve(".source-dirs"),
                "/Users/dev/project\n/Users/dev/workspace\n");

        var result = WorkspaceParser.parse(tempWorkspace);
        assertEquals("/Users/dev/project", result.projectRepoPath());

        // cleanup
        java.nio.file.Files.deleteIfExists(tempWorkspace.resolve(".source-dirs"));
        java.nio.file.Files.deleteIfExists(tempWorkspace);
    }

    @Test
    void parseResult_null_projectRepoPath_when_no_sourceDirs() throws Exception {
        java.nio.file.Path tempWorkspace = java.nio.file.Files.createTempDirectory("workspace-parser-test");

        var result = WorkspaceParser.parse(tempWorkspace);
        assertNull(result.projectRepoPath());

        // cleanup
        java.nio.file.Files.deleteIfExists(tempWorkspace);
    }
}
