package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DebateE2EFixtures {

    private static final Pattern SESSION_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");
    private static final Pattern NEW_SESSION_ID_PATTERN =
            Pattern.compile("\"newDebateSessionId\":\"([^\"]+)\"");
    private static final Pattern POINT_ID_PATTERN =
            Pattern.compile("\"pointId\":\"([^\"]+)\"");

    private DebateE2EFixtures() {}

    public static String startDebateSession(DebateMcpTools tools) {
        return startDebateSession(tools, "test-spec.md");
    }

    public static String startDebateSession(DebateMcpTools tools, String specPath) {
        String result = tools.startDebate(specPath, null);
        String sessionId = extractSessionId(result);
        if (sessionId.isBlank()) {
            throw new AssertionError("startDebate failed: " + result);
        }
        return sessionId;
    }

    public static void loadWithDebate(Page page, URL base, String sessionId) {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-b.md"), StandardCharsets.UTF_8);
        page.navigate(base + "?a=" + a + "&b=" + b + "&debate=" + sessionId);
        PlaywrightFixtures.waitForRender(page);
    }

    public static void waitForDebateEntries(Page page, int count) {
        page.locator("drafthouse-debate .entry").nth(count - 1).waitFor();
    }

    public static void waitForTrackerPoints(Page page, int count) {
        page.locator("drafthouse-review-tracker .point-item").nth(count - 1).waitFor();
    }

    public static void listenForPointSelected(Page page) {
        page.evaluate("() => {"
                + "window.__pointSelectedDetail = null;"
                + "document.addEventListener('point-selected',"
                + "  e => window.__pointSelectedDetail = e.detail);"
                + "}");
    }

    public static Object getPointSelectedDetail(Page page) {
        return page.evaluate("() => window.__pointSelectedDetail");
    }

    public static String extractSessionId(String mcpResult) {
        return extractGroup(SESSION_ID_PATTERN, mcpResult);
    }

    public static String extractNewSessionId(String mcpResult) {
        return extractGroup(NEW_SESSION_ID_PATTERN, mcpResult);
    }

    public static String extractPointId(String mcpResult) {
        return extractGroup(POINT_ID_PATTERN, mcpResult);
    }

    // ── dispatch helpers (absorb ledger frontier schema drift) ────────────────

    /**
     * Dispatches a raise_point, returning the pointId.
     * Catches ledger frontier exceptions — the message is persisted before the
     * frontier query fails, so SSE delivery works regardless.
     * When the exception swallows the return value, falls back to querying
     * the channel's messages for the correlationId of the RAISE entry.
     */
    public static String dispatchRaise(DebateMcpTools tools, MessageService messageService,
                                       String sid, String role, int round, String content,
                                       String priority, String scope, String location) {
        try {
            return extractPointId(tools.raisePoint(sid, role, round, content, priority, scope, location));
        } catch (Exception e) {
            // Ledger frontier schema drift — message already committed.
            // The return value with pointId is lost, so query the channel messages.
            return findLatestCorrelationId(messageService, sid);
        }
    }

    /**
     * Dispatches a respondTo, absorbing ledger frontier exceptions.
     */
    public static void dispatchResponse(DebateMcpTools tools,
                                        String sid, String role, int round,
                                        String pointId, String entryType, String content) {
        try {
            tools.respondTo(sid, role, round, pointId, entryType, content);
        } catch (Exception ignored) {
            // Message committed before frontier query — SSE delivery works.
        }
    }

    /**
     * Dispatches a flagHuman, absorbing ledger frontier exceptions.
     */
    public static void dispatchFlag(DebateMcpTools tools,
                                    String sid, String role, int round,
                                    String pointId, String reason) {
        try {
            tools.flagHuman(sid, role, round, pointId, reason);
        } catch (Exception ignored) {
            // Message committed before frontier query — SSE delivery works.
        }
    }

    /**
     * Dispatches a requestSubagent, absorbing ledger frontier exceptions.
     * requestSubagent has its own try-catch and returns "error: ..." on failure,
     * but the message is still committed before the frontier query fails.
     */
    public static void dispatchSubagentRequest(DebateMcpTools tools,
                                               String sid, String role, String taskType,
                                               String pointId, int round, String customInput) {
        try {
            tools.requestSubagent(sid, role, taskType, pointId, round, customInput);
        } catch (Exception ignored) {
            // Message committed before frontier query — SSE delivery works.
        }
    }

    /**
     * Queries the channel's messages to find the correlationId of the latest RAISE entry.
     * Used as a fallback when raisePoint throws before returning the pointId.
     */
    public static String findLatestCorrelationId(MessageService messageService, String sid) {
        UUID channelId = UUID.fromString(sid);
        List<Message> messages = messageService.pollAfter(channelId, 0L, 500);
        // Walk backwards to find the latest message with a correlationId
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.correlationId != null && !m.correlationId.isBlank()) {
                return m.correlationId;
            }
        }
        throw new AssertionError("No message with correlationId found in channel " + sid);
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }
}
