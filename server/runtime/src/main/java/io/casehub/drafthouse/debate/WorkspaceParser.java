package io.casehub.drafthouse.debate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorkspaceParser {

    // ── Records ──────────────────────────────────────────────────────────────

    public record WorkspaceParseResult(
            String specPath,
            String mode,
            String contextNote,
            List<ParsedRound> rounds,
            List<ParsedTrackerEntry> trackerStatuses) {}

    public record ParsedRound(
            int roundNumber,
            String signal,
            String signalDescription,
            List<ParsedIssue> issues,
            List<ParsedResponse> responses,
            List<ParsedConfirmation> confirmations,
            List<String> assumptions,
            List<ParsedSettledDecision> settledDecisions) {}

    public record ParsedIssue(String issueId, String title, String body) {}

    public record ParsedResponse(
            String issueId,
            String status,
            String sectionRef,
            String rationale,
            String body) {}

    public record ParsedConfirmation(
            String issueId,
            boolean resolved,
            boolean accepted,
            String reason) {}

    public record ParsedSettledDecision(String text, String fromIssue) {}

    public record ParsedTrackerEntry(
            String issueId,
            String title,
            String status,
            String evidence) {}

    // ── Regex patterns (ported from parser.py) ───────────────────────────────

    private static final Pattern SIGNAL_RE = Pattern.compile(
            "^\\s*SIGNAL\\s*[:\\s]+\\s*(APPROVED|CONTINUE|DECISION_NEEDED)\\b\\s*[.:]*\\s*(.*?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern HEADING_RE = Pattern.compile(
            "^(#{2,3})\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private static final Pattern ISSUE_ID_RE = Pattern.compile("R(\\d+)-(\\d+)");

    private static final Pattern ISSUE_RESPONSE_RE = Pattern.compile(
            "^#{2,3}\\s+R(\\d+)-(\\d+)\\s*[:\\s—\\-]+\\s*(FIXED|REJECTED|ESCALATED)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern CONFIRMATION_RE = Pattern.compile(
            "R(\\d+)-(\\d+)\\b[^#\\n]*?\\b(resolved|accepted|still\\s+open)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SECTION_REF_RE = Pattern.compile(
            "§(\\d+(?:\\.\\d+)*)|[Ss]ection\\s+(\\d+(?:\\.\\d+)*)");

    private static final Pattern ASSUMPTION_RE = Pattern.compile(
            "^ASSUMPTION:\\s*(.+)$", Pattern.MULTILINE);

    private static final Pattern SETTLED_RE = Pattern.compile(
            "^SETTLED:\\s*(.+?)(?:\\(from\\s+(R\\d+-\\d+)\\))?\\s*$", Pattern.MULTILINE);

    private static final Pattern SIGNAL_SEPARATOR = Pattern.compile("\\n---\\s*\\n");

    private static final Pattern TRACKER_HEADING_RE = Pattern.compile(
            "^###\\s+(R\\d+-\\d+):\\s+(.+)$", Pattern.MULTILINE);

    private static final Pattern TRACKER_STATUS_RE = Pattern.compile(
            "^-\\s+\\*\\*Status:\\*\\*\\s+(\\w+)", Pattern.MULTILINE);

    private static final Pattern TRACKER_EVIDENCE_RE = Pattern.compile(
            "^-\\s+\\*\\*Spec commit:\\*\\*\\s*(.*)", Pattern.MULTILINE);

    private static final Set<String> KNOWN_SECTIONS = Set.of(
            "addressed items", "assumptions", "settled decisions", "signals", "signal",
            "summary", "overview", "overall assessment", "final assessment", "final sweep",
            "final verification", "final scan", "verdict", "strengths", "background",
            "context", "conclusion", "next steps", "notes", "references",
            "critical issues", "design issues", "completeness gaps", "completeness",
            "verified correct", "new issues", "items resolved with remaining concerns");

    private WorkspaceParser() {}

    // ── Public API ───────────────────────────────────────────────────────────

    public static WorkspaceParseResult parse(Path workspaceDir) {
        String specPath = readFileOrNull(workspaceDir.resolve(".spec-path"));
        String mode = readFileOrNull(workspaceDir.resolve(".mode"));
        String contextNote = readFileOrNull(workspaceDir.resolve("context.md"));

        List<ParsedRound> rounds = parseRounds(workspaceDir);
        List<ParsedTrackerEntry> trackerStatuses = parseTracker(workspaceDir);

        return new WorkspaceParseResult(
                specPath != null ? specPath.trim() : null,
                mode != null ? mode.trim() : null,
                contextNote,
                rounds,
                trackerStatuses);
    }

    // ── Round parsing ────────────────────────────────────────────────────────

    private static List<ParsedRound> parseRounds(Path workspaceDir) {
        Path responsesDir = workspaceDir.resolve("responses");
        if (!Files.isDirectory(responsesDir)) return List.of();

        List<ParsedRound> rounds = new ArrayList<>();
        Set<String> allExistingIds = new HashSet<>();
        Map<Integer, List<ParsedConfirmation>> confirmationsByRound = new HashMap<>();
        Set<String> confirmedIssues = new HashSet<>();

        int maxRound = discoverMaxRound(responsesDir);

        // First pass: extract issues, responses, signals, assumptions, settled decisions
        for (int n = 1; n <= maxRound; n++) {
            String reviewerContent = readFileOrNull(responsesDir.resolve("reviewer-" + n + ".md"));
            String implementorContent = readFileOrNull(responsesDir.resolve("implementor-" + n + ".md"));

            List<ParsedIssue> issues = reviewerContent != null
                    ? extractNewIssues(reviewerContent, n, allExistingIds) : List.of();
            issues.forEach(i -> allExistingIds.add(i.issueId()));

            List<ParsedResponse> responses = implementorContent != null
                    ? extractIssueResponses(implementorContent) : List.of();

            String signal = "CONTINUE";
            String signalDescription = null;
            if (reviewerContent != null) {
                var sig = extractSignal(reviewerContent);
                signal = sig[0];
                signalDescription = sig[1];
            }

            List<String> assumptions = reviewerContent != null
                    ? extractAssumptions(reviewerContent) : List.of();

            List<ParsedSettledDecision> settled = List.of();
            if (reviewerContent != null) settled = extractSettledDecisions(reviewerContent);
            if (settled.isEmpty() && implementorContent != null) {
                settled = extractSettledDecisions(implementorContent);
            }

            // Extract confirmations from next reviewer and route to appropriate rounds
            String nextReviewerContent = readFileOrNull(responsesDir.resolve("reviewer-" + (n + 1) + ".md"));
            if (nextReviewerContent != null) {
                List<ParsedConfirmation> confirmations = extractConfirmations(nextReviewerContent);
                for (ParsedConfirmation conf : confirmations) {
                    // Skip if this issue already has a confirmation from an earlier round
                    if (confirmedIssues.contains(conf.issueId())) continue;

                    confirmedIssues.add(conf.issueId());

                    // Extract round number from issue ID (e.g., "R1-01" -> 1)
                    Matcher m = ISSUE_ID_RE.matcher(conf.issueId());
                    if (m.find()) {
                        int issueRound = Integer.parseInt(m.group(1));
                        confirmationsByRound.computeIfAbsent(issueRound, k -> new ArrayList<>()).add(conf);
                    }
                }
            }

            rounds.add(new ParsedRound(n, signal, signalDescription,
                    issues, responses, confirmationsByRound.getOrDefault(n, List.of()),
                    assumptions, settled));
        }

        return rounds;
    }

    private static int discoverMaxRound(Path responsesDir) {
        int max = 0;
        for (int n = 1; n <= 100; n++) {
            if (Files.exists(responsesDir.resolve("reviewer-" + n + ".md"))
                    || Files.exists(responsesDir.resolve("implementor-" + n + ".md"))) {
                max = n;
            } else {
                break;
            }
        }
        return max;
    }

    // ── Issue extraction ─────────────────────────────────────────────────────

    static List<ParsedIssue> extractNewIssues(String content, int roundNum, Set<String> existingIds) {
        List<ParsedIssue> issues = new ArrayList<>();
        Matcher m = HEADING_RE.matcher(content);
        List<int[]> headings = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        while (m.find()) {
            headings.add(new int[]{m.start(), m.end()});
            titles.add(m.group(2));
        }

        int seq = 1;
        for (int i = 0; i < headings.size(); i++) {
            String title = titles.get(i);
            String titleLower = title.replaceAll(":.*$", "").trim().toLowerCase();
            if (KNOWN_SECTIONS.contains(titleLower)) continue;

            Matcher idCheck = ISSUE_ID_RE.matcher(title);
            if (idCheck.find() && existingIds.contains(idCheck.group())) continue;

            int bodyStart = headings.get(i)[1] + 1;
            int bodyEnd = (i + 1 < headings.size()) ? headings.get(i + 1)[0] : content.length();
            String body = content.substring(bodyStart, bodyEnd).trim();

            String[] parts = SIGNAL_SEPARATOR.split(body, 2);
            body = parts[0].trim();

            // Strip issue ID prefix from title (e.g., "R1-01: Title" -> "Title")
            String cleanTitle = title.replaceFirst("^R\\d+-\\d+\\s*:\\s*", "");

            String issueId = String.format("R%d-%02d", roundNum, seq++);
            issues.add(new ParsedIssue(issueId, cleanTitle, body));
        }

        return issues;
    }

    // ── Response extraction ──────────────────────────────────────────────────

    static List<ParsedResponse> extractIssueResponses(String content) {
        List<ParsedResponse> responses = new ArrayList<>();
        Matcher m = ISSUE_RESPONSE_RE.matcher(content);
        List<int[]> matches = new ArrayList<>();
        List<String[]> parsed = new ArrayList<>();

        while (m.find()) {
            matches.add(new int[]{m.start(), m.end()});
            parsed.add(new String[]{
                    "R" + m.group(1) + "-" + String.format("%02d", Integer.parseInt(m.group(2))),
                    m.group(3).toUpperCase()
            });
        }

        for (int i = 0; i < matches.size(); i++) {
            int bodyStart = matches.get(i)[1] + 1;
            int bodyEnd = (i + 1 < matches.size()) ? matches.get(i + 1)[0] : content.length();
            String body = content.substring(bodyStart, bodyEnd).trim();

            String[] signalParts = SIGNAL_SEPARATOR.split(body, 2);
            body = signalParts[0].trim();

            body = Pattern.compile("^SETTLED:", Pattern.MULTILINE)
                    .split(body, 2)[0].trim();

            String sectionRef = null;
            Matcher refMatch = SECTION_REF_RE.matcher(body);
            if (refMatch.find()) {
                sectionRef = refMatch.group(1) != null ? refMatch.group(1) : refMatch.group(2);
            }

            String status = parsed.get(i)[1];
            String rationale = ("REJECTED".equals(status) || "ESCALATED".equals(status)) ? body : "";

            responses.add(new ParsedResponse(parsed.get(i)[0], status, sectionRef, rationale, body));
        }

        return responses;
    }

    // ── Confirmation extraction ──────────────────────────────────────────────

    static List<ParsedConfirmation> extractConfirmations(String content) {
        List<ParsedConfirmation> confirmations = new ArrayList<>();
        Matcher m = CONFIRMATION_RE.matcher(content);

        while (m.find()) {
            String issueId = "R" + m.group(1) + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
            String statusText = m.group(3).toLowerCase();
            boolean resolved = statusText.contains("resolved") && !statusText.contains("still");
            boolean accepted = statusText.contains("accepted");

            String reason = "";
            if (!resolved && !accepted) {
                int afterMatch = m.end();
                int lineEnd = content.indexOf('\n', afterMatch);
                if (lineEnd < 0) lineEnd = content.length();
                reason = content.substring(afterMatch, lineEnd)
                        .replaceAll("^[\\s—\\-:]+", "").trim();
            }

            confirmations.add(new ParsedConfirmation(issueId, resolved, accepted, reason));
        }

        return confirmations;
    }

    // ── Signal extraction ────────────────────────────────────────────────────

    static String[] extractSignal(String content) {
        String[] lines = content.split("\n");
        int searchFrom = Math.max(0, lines.length - 10);
        String lastTenLines = String.join("\n", Arrays.copyOfRange(lines, searchFrom, lines.length));

        Matcher m = SIGNAL_RE.matcher(lastTenLines);
        String signal = "CONTINUE";
        String description = null;

        while (m.find()) {
            signal = m.group(1).toUpperCase();
            description = "DECISION_NEEDED".equals(signal) ? m.group(2) : null;
        }

        if ("CONTINUE".equals(signal)) {
            m = SIGNAL_RE.matcher(content);
            while (m.find()) {
                signal = m.group(1).toUpperCase();
                description = "DECISION_NEEDED".equals(signal) ? m.group(2) : null;
            }
        }

        return new String[]{signal, description};
    }

    // ── Assumption / settled extraction ───────────────────────────────────────

    static List<String> extractAssumptions(String content) {
        List<String> assumptions = new ArrayList<>();
        Matcher m = ASSUMPTION_RE.matcher(content);
        while (m.find()) assumptions.add(m.group(1).trim());
        return assumptions;
    }

    static List<ParsedSettledDecision> extractSettledDecisions(String content) {
        List<ParsedSettledDecision> decisions = new ArrayList<>();
        Matcher m = SETTLED_RE.matcher(content);
        while (m.find()) {
            decisions.add(new ParsedSettledDecision(
                    m.group(1).trim(),
                    m.group(2) != null ? m.group(2) : ""));
        }
        return decisions;
    }

    // ── Tracker parsing ──────────────────────────────────────────────────────

    static List<ParsedTrackerEntry> parseTracker(Path workspaceDir) {
        String content = readFileOrNull(workspaceDir.resolve("tracker.md"));
        if (content == null) return List.of();

        List<ParsedTrackerEntry> entries = new ArrayList<>();
        Matcher headingMatcher = TRACKER_HEADING_RE.matcher(content);

        List<int[]> headingPositions = new ArrayList<>();
        List<String[]> headingData = new ArrayList<>();
        while (headingMatcher.find()) {
            headingPositions.add(new int[]{headingMatcher.start(), headingMatcher.end()});
            headingData.add(new String[]{headingMatcher.group(1), headingMatcher.group(2).trim()});
        }

        for (int i = 0; i < headingPositions.size(); i++) {
            int sectionStart = headingPositions.get(i)[1];
            int sectionEnd = (i + 1 < headingPositions.size())
                    ? headingPositions.get(i + 1)[0] : content.length();
            String section = content.substring(sectionStart, sectionEnd);

            String status = null;
            Matcher sm = TRACKER_STATUS_RE.matcher(section);
            if (sm.find()) status = sm.group(1);

            String evidence = null;
            Matcher em = TRACKER_EVIDENCE_RE.matcher(section);
            if (em.find()) {
                String raw = em.group(1).trim();
                evidence = raw.isEmpty() ? null : raw;
            }

            entries.add(new ParsedTrackerEntry(
                    headingData.get(i)[0], headingData.get(i)[1], status, evidence));
        }

        return entries;
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private static String readFileOrNull(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
