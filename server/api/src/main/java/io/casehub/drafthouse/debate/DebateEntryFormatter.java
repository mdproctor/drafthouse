package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DebateEntryFormatter {

    private static final Pattern ANCHOR_PATTERN =
            Pattern.compile("<a\\s+name=\"R(\\d+)-(REV|IMP)-(\\d+)\"");

    public String format(List<DebateEntry> entries, int round, AgentType agent, String existingDebate) {
        int nextSeq = nextSequenceNumber(existingDebate != null ? existingDebate : "", round, agent);

        // Sort: RAISE first, then AGREE/DISPUTE/QUALIFY, then FLAG_HUMAN
        List<DebateEntry> sorted = new ArrayList<>();
        entries.stream().filter(e -> e.type() == EntryType.RAISE).forEach(sorted::add);
        entries.stream().filter(e -> e.type() == EntryType.AGREE
                || e.type() == EntryType.DISPUTE || e.type() == EntryType.QUALIFY).forEach(sorted::add);
        entries.stream().filter(e -> e.type() == EntryType.FLAG_HUMAN).forEach(sorted::add);

        var sb = new StringBuilder();
        sb.append("\n<!-- Round ").append(round).append(" — ")
          .append(agent == AgentType.REV ? "Reviewer" : "Implementer").append(" -->\n\n");

        for (DebateEntry entry : sorted) {
            String entryId = "R" + round + "-" + agent.name() + "-" + String.format("%03d", nextSeq++);
            sb.append("<a name=\"").append(entryId).append("\"></a>\n");
            sb.append("**[").append(entryId).append("]** `").append(typeLabel(entry.type())).append("`");

            if (entry.type() == EntryType.RAISE) {
                if (entry.priority() != null) {
                    sb.append(" · ").append(entry.priority());
                }
                if (entry.scope() != null) {
                    sb.append(" · ").append(scopeLabel(entry.scope()));
                }
                if (entry.location() != null) sb.append(" · ").append(entry.location());
            } else if (entry.targetId() != null) {
                sb.append(" · → [").append(entry.targetId()).append("]");
            }
            sb.append("\n");
            if (entry.content() != null) sb.append(entry.content()).append("\n");

            if (entry.type() == EntryType.RAISE) {
                sb.append("Status: 🔴 Open\n");
            } else if (entry.targetId() != null && entry.statusDirective() != null) {
                sb.append("→ [").append(entry.targetId()).append("] Status: ")
                  .append(statusEmoji(entry.statusDirective())).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private int nextSequenceNumber(String existingDebate, int round, AgentType agent) {
        int max = 0;
        Matcher m = ANCHOR_PATTERN.matcher(existingDebate);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == round && m.group(2).equals(agent.name())) {
                max = Math.max(max, Integer.parseInt(m.group(3)));
            }
        }
        return max + 1;
    }

    private String typeLabel(EntryType type) {
        return switch (type) {
            case RAISE      -> "raise";
            case AGREE      -> "agree";
            case DISPUTE    -> "dispute";
            case QUALIFY    -> "qualify";
            case FLAG_HUMAN -> "flag_human";
        };
    }

    private String scopeLabel(Scope scope) {
        return switch (scope) {
            case SYSTEMIC -> "Systemic";
            case ISOLATED -> "Isolated";
        };
    }

    private String statusEmoji(ReviewStatus status) {
        return switch (status) {
            case OPEN          -> "🔴 Open";
            case ACTIVE        -> "🟡 Active";
            case AGREED        -> "✅ Agreed";
            case PENDING_HUMAN -> "🔵 Pending Human";
        };
    }
}
