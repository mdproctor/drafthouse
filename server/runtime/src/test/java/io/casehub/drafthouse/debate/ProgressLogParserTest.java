package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ProgressLogParserTest {

    @Test
    void parse_agent_start_reviewer() {
        var event = ProgressLogParser.parse(
                "[10:00:00]   Reviewer (fresh session)... (this may take 1-2 minutes)");
        assertInstanceOf(ProgressLogParser.AgentStart.class, event);
        var start = (ProgressLogParser.AgentStart) event;
        assertEquals("reviewer", start.agent());
        assertFalse(start.cached());
    }

    @Test
    void parse_agent_start_implementor_cached() {
        var event = ProgressLogParser.parse(
                "[10:00:00]   Implementor (continued — cached context)... (this may take 1-2 minutes)");
        assertInstanceOf(ProgressLogParser.AgentStart.class, event);
        var start = (ProgressLogParser.AgentStart) event;
        assertEquals("implementor", start.agent());
        assertTrue(start.cached());
    }

    @Test
    void parse_agent_status() {
        var event = ProgressLogParser.parse(
                "[10:00:30]     [30s] reviewer: Reading spec and exploring codebase");
        assertInstanceOf(ProgressLogParser.AgentStatus.class, event);
        var status = (ProgressLogParser.AgentStatus) event;
        assertEquals("reviewer", status.agent());
        assertEquals(30, status.elapsedSeconds());
        assertEquals("Reading spec and exploring codebase", status.message());
    }

    @Test
    void parse_agent_complete() {
        var event = ProgressLogParser.parse("[10:01:00]   Reviewer done ($1.50)");
        assertInstanceOf(ProgressLogParser.AgentComplete.class, event);
        var complete = (ProgressLogParser.AgentComplete) event;
        assertEquals("reviewer", complete.agent());
        assertEquals(1.50, complete.cost(), 0.001);
    }

    @Test
    void parse_issues_raised() {
        var event = ProgressLogParser.parse("[10:01:00]   13 new issue(s) raised");
        assertInstanceOf(ProgressLogParser.IssuesRaised.class, event);
        assertEquals(13, ((ProgressLogParser.IssuesRaised) event).count());
    }

    @Test
    void parse_round_complete() {
        var event = ProgressLogParser.parse(
                "[10:02:00]   Round 1 complete — ~$2.70/round, $4.50 cumulative");
        assertInstanceOf(ProgressLogParser.RoundComplete.class, event);
        var rc = (ProgressLogParser.RoundComplete) event;
        assertEquals(1, rc.round());
        assertEquals(2.70, rc.roundCost(), 0.001);
        assertEquals(4.50, rc.cumulativeCost(), 0.001);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "REVIEW DONE",
            "REVIEW PAUSED",
            "REVIEW PAUSED: received SIGTERM — parent session likely ended",
            "REVIEW FAILED (exit 1)",
            "REVIEW ABORTED",
            "REVIEW CRASHED: java.lang.OutOfMemoryError",
            "REVIEW INTERRUPTED"
    })
    void parse_terminal_states(String line) {
        var event = ProgressLogParser.parse(line);
        assertInstanceOf(ProgressLogParser.ReviewTerminal.class, event);
        assertTrue(ProgressLogParser.isTerminal(line));
    }

    @Test
    void parse_terminal_extracts_state() {
        assertEquals("DONE", ProgressLogParser.terminalState("REVIEW DONE"));
        assertEquals("PAUSED", ProgressLogParser.terminalState(
                "REVIEW PAUSED: received SIGTERM — parent session likely ended"));
        assertEquals("FAILED", ProgressLogParser.terminalState("REVIEW FAILED (exit 1)"));
    }

    @Test
    void parse_unrecognised_line_returns_null() {
        assertNull(ProgressLogParser.parse("[10:00:00] Mode: spec-review"));
        assertNull(ProgressLogParser.parse("============================================================"));
        assertNull(ProgressLogParser.parse(
                "[10:00:00]     [60s] no output file yet — agent may be reading/exploring"));
    }

    @Test
    void isTerminal_returns_false_for_non_terminal() {
        assertFalse(ProgressLogParser.isTerminal(
                "[10:00:00]   Round 1 complete — ~$2.70/round, $4.50 cumulative"));
        assertFalse(ProgressLogParser.isTerminal(""));
    }
}
