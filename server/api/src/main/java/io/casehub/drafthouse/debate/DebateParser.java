package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DebateParser {

    private static final Pattern ROUND_HEADER   = Pattern.compile("<!--\\s*Round\\s+(\\d+)\\s*[—-]\\s*(Reviewer|Implementer)\\s*-->", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR         = Pattern.compile("<a\\s+name=\"(R(\\d+)-(REV|IMP)-(\\d+))\"");
    private static final Pattern ENTRY_HEADER   = Pattern.compile("\\*\\*\\[([^]]+)\\]\\*\\*\\s*`(raise|agree|dispute|qualify|flag[_-]human)`(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_DIR     = Pattern.compile("→\\s*\\[([^]]+)\\]\\s*Status:\\s*(.+)");
    private static final Pattern CLASSIFICATION = Pattern.compile("[·•]\\s*(P[123])\\s*[·•]\\s*(Systemic|Isolated)(?:\\s*[·•]\\s*(.+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_REF     = Pattern.compile("→\\s*\\[([^]]+)\\]");
    private static final Pattern MEMO_LINE      = Pattern.compile("\\*\\*(REV|IMP)\\s+memo\\s+R\\d+:\\*\\*\\s*(.*)", Pattern.CASE_INSENSITIVE);

    public List<DebateEvent> parse(String debateMarkdown) {
        List<DebateEvent> events = new ArrayList<>();
        String[] lines = debateMarkdown.split("\\r?\\n");

        int currentRound = 0;
        AgentType currentAgent = AgentType.REV;

        // Pending entry state
        String pendingEntryId = null;
        String pendingType = null;
        String pendingClassification = null;
        String pendingTargetId = null;
        ReviewStatus pendingStatus = null;
        List<String> pendingContentLines = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.strip();

            // Round header
            Matcher roundM = ROUND_HEADER.matcher(trimmed);
            if (roundM.find()) {
                if (pendingEntryId != null) {
                    events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                            pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
                    pendingEntryId = null;
                    pendingContentLines = new ArrayList<>();
                    pendingTargetId = null;
                    pendingStatus = null;
                    pendingType = null;
                    pendingClassification = null;
                }
                currentRound = Integer.parseInt(roundM.group(1));
                currentAgent = roundM.group(2).equalsIgnoreCase("Reviewer") ? AgentType.REV : AgentType.IMP;
                continue;
            }

            // Memo line
            Matcher memoM = MEMO_LINE.matcher(trimmed);
            if (memoM.find()) {
                if (pendingEntryId != null) {
                    events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                            pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
                    pendingEntryId = null;
                    pendingContentLines = new ArrayList<>();
                    pendingTargetId = null;
                    pendingStatus = null;
                    pendingType = null;
                    pendingClassification = null;
                }
                AgentType memoAgent = memoM.group(1).equalsIgnoreCase("REV") ? AgentType.REV : AgentType.IMP;
                events.add(new DebateEvent.AgentMemo(currentRound, memoAgent, memoM.group(2).strip()));
                continue;
            }

            // Anchor — starts a new entry
            Matcher anchorM = ANCHOR.matcher(trimmed);
            if (anchorM.find()) {
                if (pendingEntryId != null) {
                    events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                            pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
                }
                pendingEntryId = anchorM.group(1);
                pendingType = null;
                pendingClassification = null;
                pendingContentLines = new ArrayList<>();
                pendingTargetId = null;
                pendingStatus = null;
                continue;
            }

            if (pendingEntryId == null) continue;

            // Entry header
            Matcher headerM = ENTRY_HEADER.matcher(trimmed);
            if (headerM.find()) {
                pendingType = headerM.group(2).toLowerCase().replace("-", "_");
                String rest = headerM.group(3);
                pendingClassification = rest;
                Matcher targetM = TARGET_REF.matcher(rest);
                if (targetM.find()) pendingTargetId = targetM.group(1);
                continue;
            }

            // Status directive
            Matcher statusM = STATUS_DIR.matcher(trimmed);
            if (statusM.find()) {
                pendingStatus = parseStatus(statusM.group(2).strip());
                continue;
            }

            // Skip decorative / metadata lines
            if (trimmed.startsWith("---") || trimmed.startsWith("Status:") || trimmed.isEmpty()
                    || trimmed.startsWith("# ") || trimmed.startsWith("**Spec:")
                    || trimmed.startsWith("**Session:") || trimmed.startsWith("**Round:")) continue;

            // Content line
            pendingContentLines.add(trimmed);
        }

        // Flush last pending entry
        if (pendingEntryId != null) {
            events.add(buildEntry(pendingEntryId, pendingType, pendingClassification,
                    pendingContentLines, pendingTargetId, pendingStatus, currentRound, currentAgent));
        }
        return events;
    }

    private DebateEvent buildEntry(String entryId, String type, String classification,
                                   List<String> contentLines, String targetId,
                                   ReviewStatus statusDirective, int round, AgentType agent) {
        String content = String.join(" ", contentLines).strip();
        // Derive agent from entryId (e.g. R1-REV-001 → REV)
        AgentType entryAgent = agent;
        if (entryId != null && entryId.contains("-REV-")) entryAgent = AgentType.REV;
        else if (entryId != null && entryId.contains("-IMP-")) entryAgent = AgentType.IMP;

        return switch (type != null ? type : "") {
            case "raise" -> {
                Priority p = Priority.P3;
                Scope s = Scope.ISOLATED;
                String loc = null;
                if (classification != null) {
                    Matcher cm = CLASSIFICATION.matcher(classification);
                    if (cm.find()) {
                        try { p = Priority.valueOf(cm.group(1).toUpperCase()); } catch (Exception ignored) {}
                        s = cm.group(2).equalsIgnoreCase("Systemic") ? Scope.SYSTEMIC : Scope.ISOLATED;
                        if (cm.groupCount() >= 3 && cm.group(3) != null) loc = cm.group(3).strip();
                    }
                }
                yield new DebateEvent.RaiseEvent(entryId, round, entryAgent, p, s, loc, content);
            }
            case "agree"      -> new DebateEvent.ResponseEvent(round, entryAgent, targetId, EntryType.AGREE,      content, statusDirective);
            case "dispute"    -> new DebateEvent.ResponseEvent(round, entryAgent, targetId, EntryType.DISPUTE,    content, statusDirective);
            case "qualify"    -> new DebateEvent.ResponseEvent(round, entryAgent, targetId, EntryType.QUALIFY,    content, statusDirective);
            case "flag_human" -> new DebateEvent.FlagHumanEvent(round, entryAgent, content, targetId, statusDirective);
            default           -> new DebateEvent.AgentMemo(round, entryAgent, content);
        };
    }

    private ReviewStatus parseStatus(String s) {
        if (s.contains("✅")) return ReviewStatus.AGREED;
        if (s.contains("🟡")) return ReviewStatus.ACTIVE;
        if (s.contains("🔵")) return ReviewStatus.PENDING_HUMAN;
        return ReviewStatus.OPEN;
    }
}
