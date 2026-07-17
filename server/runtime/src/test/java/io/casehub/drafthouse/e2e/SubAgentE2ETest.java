package io.casehub.drafthouse.e2e;

import io.casehub.blocks.channel.ChannelAgentRequest;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.casehub.drafthouse.DebateMcpTools;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Full sub-agent lifecycle E2E test.
 *
 * Exercises the complete async dispatch chain in a real @QuarkusTest container:
 *   DebateMcpTools.requestSubagent()
 *     → MessageService.dispatch(SUB_TASK_REQUEST)
 *     → ChannelGateway.fanOut() → DebateChannelBackend.post()
 *     → Event<ChannelAgentRequest>.fireAsync()
 *     → ChannelAgentDispatcher.onChannelAgentRequest() [virtual thread]
 *     → MockDebateAgentProvider.analyse() → "Mock sub-agent finding."
 *     → MessageService.dispatch(SUB_TASK_FINDING)
 *
 * MockDebateAgentProvider (@Alternative @Priority(1)) displaces the real
 * LangChain4j provider, making this test deterministic and fast.
 * Awaitility covers the @ObservesAsync gap.
 */
@QuarkusTest
class SubAgentE2ETest {

    private static final Pattern DEBATE_ID_PATTERN  = Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");
    private static final Pattern POINT_ID_PATTERN   = Pattern.compile("\"pointId\":\"([^\"]+)\"");
    private static final Pattern SUBTASK_ID_PATTERN = Pattern.compile("\"subTaskId\":\"([^\"]+)\"");

    @Inject DebateMcpTools tools;

    private String activeDebateSessionId;

    @AfterEach
    void tearDown() {
        if (activeDebateSessionId != null) {
            tools.endDebate(activeDebateSessionId, false);
            activeDebateSessionId = null;
        }
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void subAgent_dispatchesAndCompletes_findingAppearsInSummary() {
        // 1. start_debate
        String startResult = tools.startDebate("test-spec.md", null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        assertThat(sessionId).isNotBlank();
        activeDebateSessionId = sessionId;

        // 2. raise_point
        String raiseResult = tools.raisePoint(sessionId, "REV", 1,
                "The API contract is underspecified.", "HIGH", "ISOLATED", "§3.2");
        String pointId = extractGroup(POINT_ID_PATTERN, raiseResult);
        assertThat(pointId).isNotBlank();

        // 3. request_subagent(ARBITRATE, pointId, round=1)
        String subResult = tools.requestSubagent(sessionId, "REV", "ARBITRATE", pointId, 1, null);
        assertThat(subResult).contains("subTaskId");
        assertThat(subResult).contains("dispatched");
        String subTaskId = extractGroup(SUBTASK_ID_PATTERN, subResult);
        assertThat(subTaskId).isNotBlank();

        // 4. immediately: summary shows ⏳ pending indicator
        String summaryPending = tools.getDebateSummary(sessionId);
        assertThat(summaryPending).contains("⏳");

        // 5. await async dispatch: MockDebateAgentProvider posts the finding (~500ms)
        await().atMost(5, SECONDS).untilAsserted(() -> {
            ManagedContext rc = Arc.container().requestContext();
            rc.activate();
            try {
                String summary = tools.getDebateSummary(sessionId);
                assertThat(summary).contains("⊕");
                assertThat(summary).contains("Mock sub-agent finding.");
            } finally {
                rc.deactivate();
            }
        });

        // 6. post_memo
        String memoResult = tools.postMemo(sessionId, "IMP", 1,
                "Sub-agent confirms the contract gap — this needs a fix.");
        assertThat(memoResult).contains("dispatched");

        // 7. memo appears in summary
        String summaryWithMemo = tools.getDebateSummary(sessionId);
        assertThat(summaryWithMemo).containsAnyOf("Agent Memos", "memo");
        assertThat(summaryWithMemo).contains("Sub-agent confirms the contract gap");

        // 8. end_debate
        String endResult = tools.endDebate(sessionId, false);
        assertThat(endResult).contains("ended");
        activeDebateSessionId = null;
    }

    @Test
    void subAgent_pointAnchoredFinding_appearsInsidePointSection() {
        String sessionId = extractGroup(DEBATE_ID_PATTERN, tools.startDebate("spec.md", null));
        activeDebateSessionId = sessionId;

        String pointId = extractGroup(POINT_ID_PATTERN,
                tools.raisePoint(sessionId, "REV", 1, "Needs verification.", "MEDIUM", "ISOLATED", null));

        tools.requestSubagent(sessionId, "REV", "VERIFY", pointId, 1, null);

        await().atMost(5, SECONDS).untilAsserted(() -> {
            ManagedContext rc = Arc.container().requestContext();
            rc.activate();
            try {
                String summary = tools.getDebateSummary(sessionId);
                assertThat(summary).contains("Mock sub-agent finding.");
                assertThat(summary).doesNotContain("Sub-task findings");
            } finally {
                rc.deactivate();
            }
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }
}
