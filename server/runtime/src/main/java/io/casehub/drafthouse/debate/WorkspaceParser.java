package io.casehub.drafthouse.debate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WorkspaceParser {

    // ── Records ──────────────────────────────────────────────────────────────

    public record WorkspaceParseResult(
            String specPath,
            String mode,
            String contextNote,
            List<ParsedRound> rounds,
            List<ParsedTrackerEntry> trackerStatuses,
            String projectRepoPath) {}

    public record ParsedRound(
            int roundNumber,
            String signal,
            String signalDescription,
            List<ParsedIssue> issues,
            List<ParsedResponse> responses,
            List<ParsedConfirmation> confirmations,
            List<String> assumptions,
            List<ParsedSettledDecision> settledDecisions) {}


    public record Evidence(String location, String commit, String lines) {}

    public record ParsedIssue(String issueId, String title, String body, String location, String priority, List<String> depends) {}

    public record ParsedResponse(String issueId, String status, String sectionRef, String rationale, String body, List<Evidence> evidence) {}

    public record ParsedConfirmation(String issueId, String verdict, String reason) {}

    public record ParsedSettledDecision(String text, String fromIssue) {}

    public record ParsedTrackerEntry(
            String issueId,
            String title,
            String status,
            String evidence,
            String commitHash) {}

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

        String projectRepoPath = null;
        String sourceDirs = readFileOrNull(workspaceDir.resolve(".source-dirs"));
        if (sourceDirs != null) {
            String firstLine = sourceDirs.lines().findFirst().orElse(null);
            if (firstLine != null) projectRepoPath = firstLine.trim();
        }

        return new WorkspaceParseResult(
                specPath != null ? specPath.trim() : null,
                mode != null ? mode.trim() : null,
                contextNote,
                rounds,
                trackerStatuses,
                projectRepoPath);
    }

    // ── Round parsing ────────────────────────────────────────────────────────

    private static List<ParsedRound> parseRounds(Path workspaceDir) {
        Path responsesDir = workspaceDir.resolve("responses");
        if (!Files.isDirectory(responsesDir)) {return List.of();}

        int               maxRound       = discoverMaxRound(responsesDir);
        List<ParsedRound> rounds         = new ArrayList<>();
        Set<String>       allExistingIds = new HashSet<>();

        for (int n = 1; n <= maxRound; n++) {
            ParsedRound round;
            if (hasJsonlForRound(responsesDir, n)) {
                round = parseRoundFromJsonl(responsesDir, n);
            } else {
                round = parseRoundFromMarkdown(responsesDir, n, allExistingIds);
            }
            round.issues().forEach(i -> allExistingIds.add(i.issueId()));
            rounds.add(round);
        }

        return rounds;}

    static ParsedRound parseRoundFromMarkdown(Path responsesDir, int roundNum, Set<String> existingIds) {
        String reviewerContent    = readFileOrNull(responsesDir.resolve("reviewer-" + roundNum + ".md"));
        String implementorContent = readFileOrNull(responsesDir.resolve("implementor-" + roundNum + ".md"));

        List<ParsedIssue> issues = reviewerContent != null
                                   ? extractNewIssues(reviewerContent, roundNum, existingIds) : List.of();

        List<ParsedResponse> responses = implementorContent != null
                                         ? extractIssueResponses(implementorContent) : List.of();

        String signal            = "CONTINUE";
        String signalDescription = null;
        if (reviewerContent != null) {
            var sig = extractSignal(reviewerContent);
            signal            = sig[0];
            signalDescription = sig[1];
        }

        List<String> assumptions = reviewerContent != null
                                   ? extractAssumptions(reviewerContent) : List.of();

        List<ParsedSettledDecision> settled = List.of();
        if (reviewerContent != null) {settled = extractSettledDecisions(reviewerContent);}
        if (settled.isEmpty() && implementorContent != null) {
            settled = extractSettledDecisions(implementorContent);
        }

        // In-source-round model: extract confirmations from THIS round's reviewer content
        List<ParsedConfirmation> confirmations = reviewerContent != null
                                                 ? extractConfirmations(reviewerContent) : List.of();

        return new ParsedRound(roundNum, signal, signalDescription,
                               issues, responses, confirmations, assumptions, settled);
    }


    static int discoverMaxRound(Path responsesDir) {
        int max = 0;
        for (int n = 1; n <= 100; n++) {
            if (Files.exists(responsesDir.resolve("reviewer-" + n + ".md"))
                || Files.exists(responsesDir.resolve("implementor-" + n + ".md"))
                || Files.exists(responsesDir.resolve("reviewer-" + n + ".jsonl"))
                || Files.exists(responsesDir.resolve("implementor-" + n + ".jsonl"))) {
                max = n;
            } else {
                break;
            }
        }
        return max;}

    private static boolean hasJsonlForRound(Path responsesDir, int roundNum) {
        return Files.exists(responsesDir.resolve("reviewer-" + roundNum + ".jsonl"))
               || Files.exists(responsesDir.resolve("implementor-" + roundNum + ".jsonl"));
    }

    @SuppressWarnings("unchecked")
    static ParsedRound parseRoundFromJsonl(Path responsesDir, int roundNum) {
        List<ParsedIssue>           issues            = new ArrayList<>();
        List<ParsedResponse>        responses         = new ArrayList<>();
        List<ParsedConfirmation>    confirmations     = new ArrayList<>();
        List<String>                assumptions       = new ArrayList<>();
        List<ParsedSettledDecision> settledDecisions  = new ArrayList<>();
        String                      signal            = "CONTINUE";
        String                      signalDescription = null;

        for (String role : List.of("reviewer", "implementor")) {
            Path jsonlFile = responsesDir.resolve(role + "-" + roundNum + ".jsonl");
            if (!Files.exists(jsonlFile)) {continue;}

            String content = readFileOrNull(jsonlFile);
            if (content == null) {continue;}

            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) {continue;}

                var obj = parseJsonObject(line);
                if (obj == null) {continue;}

                String event = (String) obj.get("event");
                if (event == null || "schema_version".equals(event)) {continue;}

                switch (event) {
                    case "issue_raised" -> {
                        List<String> depends = obj.containsKey("depends") && obj.get("depends") instanceof List
                                               ? (List<String>) obj.get("depends") : List.of();
                        issues.add(new ParsedIssue(
                                (String) obj.get("id"),
                                (String) obj.get("title"),
                                (String) obj.get("body"),
                                (String) obj.get("location"),
                                obj.getOrDefault("priority", "LOW").toString(),
                                depends));
                    }
                    case "issue_fixed" -> {
                        List<Evidence> evidence = new ArrayList<>();
                        if (obj.get("evidence") instanceof List<?> evList) {
                            for (Object evObj : evList) {
                                if (evObj instanceof Map<?, ?> evMap) {
                                    evidence.add(new Evidence(
                                            (String) evMap.get("location"),
                                            (String) evMap.get("commit"),
                                            (String) evMap.get("lines")));
                                }
                            }
                        }
                        responses.add(new ParsedResponse(
                                (String) obj.get("id"), "FIXED",
                                (String) obj.get("sectionRef"),
                                (String) obj.getOrDefault("rationale", ""),
                                (String) obj.getOrDefault("rationale", ""),
                                evidence));
                    }
                    case "issue_rejected" -> responses.add(new ParsedResponse(
                            (String) obj.get("id"), "REJECTED", null,
                            (String) obj.getOrDefault("rationale", ""),
                            (String) obj.getOrDefault("rationale", ""),
                            List.of()));
                    case "issue_escalated" -> responses.add(new ParsedResponse(
                            (String) obj.get("id"), "ESCALATED", null,
                            (String) obj.getOrDefault("rationale", ""),
                            (String) obj.getOrDefault("rationale", ""),
                            List.of()));
                    case "confirmation" -> confirmations.add(new ParsedConfirmation(
                            (String) obj.get("id"),
                            (String) obj.getOrDefault("verdict", "contested"),
                            (String) obj.getOrDefault("reason", "")));
                    case "assumption" -> assumptions.add((String) obj.get("text"));
                    case "settled_decision" -> settledDecisions.add(new ParsedSettledDecision(
                            (String) obj.get("text"),
                            (String) obj.getOrDefault("fromIssue", "")));
                    case "round_signal" -> {
                        if ("reviewer".equals(obj.get("role"))) {
                            signal            = (String) obj.getOrDefault("signal", "CONTINUE");
                            signalDescription = (String) obj.get("description");
                        }
                    }
                }
            }
        }

        return new ParsedRound(roundNum, signal, signalDescription,
                               issues, responses, confirmations, assumptions, settledDecisions);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String line) {
        try {
            return JSON.readValue(line, Map.class);
        } catch (Exception e) {
            return null;
        }
    }


    // ── Issue extraction ─────────────────────────────────────────────────────

    static List<ParsedIssue> extractNewIssues(String content, int roundNum, Set<String> existingIds) {
        List<ParsedIssue> issues   = new ArrayList<>();
        Matcher           m        = HEADING_RE.matcher(content);
        List<int[]>       headings = new ArrayList<>();
        List<String>      titles   = new ArrayList<>();

        while (m.find()) {
            headings.add(new int[]{m.start(), m.end()});
            titles.add(m.group(2));
        }

        int seq = 1;
        for (int i = 0; i < headings.size(); i++) {
            String title      = titles.get(i);
            String titleLower = title.replaceAll(":.*$", "").trim().toLowerCase();
            if (KNOWN_SECTIONS.contains(titleLower)) {continue;}

            Matcher idCheck = ISSUE_ID_RE.matcher(title);
            if (idCheck.find() && existingIds.contains(idCheck.group())) {continue;}

            int    bodyStart = headings.get(i)[1] + 1;
            int    bodyEnd   = (i + 1 < headings.size()) ? headings.get(i + 1)[0] : content.length();
            String body      = content.substring(bodyStart, bodyEnd).trim();

            String[] parts = SIGNAL_SEPARATOR.split(body, 2);
            body = parts[0].trim();

            // Strip issue ID prefix from title (e.g., "R1-01: Title" -> "Title")
            String cleanTitle = title.replaceFirst("^R\\d+-\\d+\\s*:\\s*", "");

            String issueId = String.format("R%d-%02d", roundNum, seq++);
            issues.add(new ParsedIssue(issueId, cleanTitle, body, null, "LOW", List.of()));
        }

        return issues;}

    // ── Response extraction ──────────────────────────────────────────────────

    static List<ParsedResponse> extractIssueResponses(String content) {
        List<ParsedResponse> responses = new ArrayList<>();
        Matcher              m         = ISSUE_RESPONSE_RE.matcher(content);
        List<int[]>          matches   = new ArrayList<>();
        List<String[]>       parsed    = new ArrayList<>();

        while (m.find()) {
            matches.add(new int[]{m.start(), m.end()});
            parsed.add(new String[]{
                    "R" + m.group(1) + "-" + String.format("%02d", Integer.parseInt(m.group(2))),
                    m.group(3).toUpperCase()
            });
        }

        for (int i = 0; i < matches.size(); i++) {
            int    bodyStart = matches.get(i)[1] + 1;
            int    bodyEnd   = (i + 1 < matches.size()) ? matches.get(i + 1)[0] : content.length();
            String body      = content.substring(bodyStart, bodyEnd).trim();

            String[] signalParts = SIGNAL_SEPARATOR.split(body, 2);
            body = signalParts[0].trim();

            body = Pattern.compile("^SETTLED:", Pattern.MULTILINE)
                          .split(body, 2)[0].trim();

            String  sectionRef = null;
            Matcher refMatch   = SECTION_REF_RE.matcher(body);
            if (refMatch.find()) {
                sectionRef = refMatch.group(1) != null ? refMatch.group(1) : refMatch.group(2);
            }

            String status    = parsed.get(i)[1];
            String rationale = ("REJECTED".equals(status) || "ESCALATED".equals(status)) ? body : "";

            responses.add(new ParsedResponse(parsed.get(i)[0], status, sectionRef, rationale, body, List.of()));
        }

        return responses;}

    // ── Confirmation extraction ──────────────────────────────────────────────

    static List<ParsedConfirmation> extractConfirmations(String content) {
        List<ParsedConfirmation> confirmations = new ArrayList<>();
        Matcher                  m             = CONFIRMATION_RE.matcher(content);

        while (m.find()) {
            String issueId    = "R" + m.group(1) + "-" + String.format("%02d", Integer.parseInt(m.group(2)));
            String statusText = m.group(3).toLowerCase();

            String verdict;
            if (statusText.contains("resolved") && !statusText.contains("still")) {
                verdict = "resolved";
            } else if (statusText.contains("accepted")) {
                verdict = "accepted";
            } else {
                verdict = "contested";
            }

            String reason = "";
            if ("contested".equals(verdict)) {
                int afterMatch = m.end();
                int lineEnd    = content.indexOf('\n', afterMatch);
                if (lineEnd < 0) {lineEnd = content.length();}
                reason = content.substring(afterMatch, lineEnd)
                                .replaceAll("^[\\s—\\-:]+", "").trim();
            }

            confirmations.add(new ParsedConfirmation(issueId, verdict, reason));
        }

        return confirmations;}

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

            String commitHash = null;
            if (evidence != null) {
                String cleaned = evidence.replaceFirst("^.*→\\s*", "").trim();
                if (!cleaned.isEmpty()) commitHash = cleaned;
            }

            entries.add(new ParsedTrackerEntry(
                    headingData.get(i)[0], headingData.get(i)[1], status, evidence, commitHash));
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
