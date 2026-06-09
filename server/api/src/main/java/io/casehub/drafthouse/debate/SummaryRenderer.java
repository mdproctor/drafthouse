package io.casehub.drafthouse.debate;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

public class SummaryRenderer {

    private Supplier<Instant> clock = Instant::now;

    void setClockForTest(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public String render(ReviewState state) {
        var sb = new StringBuilder();
        sb.append("# Review Summary\n");
        sb.append("**Updated:** ").append(clock.get()).append("\n\n---\n\n");

        for (ReviewPoint point : state.points().values()) {
            String statusMarker = switch (point.currentStatus()) {
                case OPEN          -> "🔴";
                case ACTIVE        -> "🟡";
                case AGREED        -> "✅";
                case PENDING_HUMAN -> "🔵";
                case DECLINED      -> "🚫";
                case DISPUTED      -> "⚡";
            };

            String firstContent = point.thread().isEmpty() ? "" : point.thread().get(0).content();
            String header = "[" + point.id() + "] "
                    + point.classification().priority() + " · "
                    + point.classification().scope()
                    + (point.classification().location() != null
                       ? " · " + point.classification().location() : "")
                    + " — " + firstContent;

            boolean strikethrough = point.currentStatus() == ReviewStatus.AGREED
                    || point.currentStatus() == ReviewStatus.DECLINED;
            if (strikethrough) {
                sb.append("## ").append(statusMarker).append(" ~~").append(header).append("~~\n");
            } else {
                sb.append("## ").append(statusMarker).append(" ").append(header).append("\n");
            }

            for (ThreadEntry entry : point.thread()) {
                String typeLabel = switch (entry.type()) {
                    case RAISE      -> "raise";
                    case AGREE      -> "agree";
                    case COUNTER    -> "counter";
                    case DISPUTE    -> "dispute";
                    case QUALIFY    -> "qualify";
                    case FLAG_HUMAN -> "flag";
                    case DECLINED   -> "declined";
                    case MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR ->
                        throw new IllegalStateException("entry type " + entry.type() + " must not appear in ThreadEntry");
                };
                sb.append("> **").append(entry.agent()).append(" (").append(typeLabel).append("):** ")
                  .append(entry.content()).append("\n");
            }

            state.subTaskFindings().values().stream()
                    .filter(f -> point.id().equals(f.pointId()))
                    .forEach(f -> sb.append(renderFinding(f)));

            sb.append("\n---\n\n");
        }

        if (!state.humanFlags().isEmpty()) {
            sb.append("⚑ **Human review needed:**\n");
            for (FlagEntry flag : state.humanFlags()) {
                sb.append("- ").append(flag.content()).append("\n");
            }
        }

        // Sub-task findings with null pointId (NEUTRAL_SUMMARY, CUSTOM) — standalone section
        List<SubTaskFinding> standaloneFindings = state.subTaskFindings().values().stream()
                .filter(f -> f.pointId() == null)
                .toList();
        if (!standaloneFindings.isEmpty()) {
            sb.append("\n---\n\n**Sub-task findings**\n\n");
            for (SubTaskFinding f : standaloneFindings) {
                sb.append(renderFinding(f));
            }
        }

        // Memos section
        if (!state.memos().isEmpty()) {
            sb.append("\n---\n\n**Agent Memos**\n\n");
            for (RoundMemo memo : state.memos()) {
                sb.append("**").append(memo.agentRole()).append(" memo — Round ").append(memo.round())
                  .append(":** ").append(memo.content()).append("\n\n");
            }
        }

        return sb.toString();
    }

    private String renderFinding(SubTaskFinding f) {
        return switch (f.status()) {
            case PENDING  -> "  ⏳ **" + f.taskType() + "** pending...\n";
            case ERROR    -> "  ✗ **" + f.taskType() + "** failed: " + f.errorReason() + "\n";
            case COMPLETE -> "  ⊕ **" + f.taskType() + "** _(fresh context — no prior round knowledge)_\n"
                           + "  " + java.util.Objects.requireNonNullElse(f.finding(), "(no finding)") + "\n";
        };
    }
}
