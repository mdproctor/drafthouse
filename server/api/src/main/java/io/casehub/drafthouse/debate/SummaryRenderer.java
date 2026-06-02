package io.casehub.drafthouse.debate;

import java.time.Instant;

public class SummaryRenderer {

    public String render(ReviewState state) {
        var sb = new StringBuilder();
        sb.append("# Review Summary\n");
        sb.append("**Updated:** ").append(Instant.now()).append("\n\n---\n\n");

        for (ReviewPoint point : state.points().values()) {
            String statusMarker = switch (point.currentStatus()) {
                case OPEN          -> "🔴";
                case ACTIVE        -> "🟡";
                case AGREED        -> "✅";
                case PENDING_HUMAN -> "🔵";
            };

            String firstContent = point.thread().isEmpty() ? "" : point.thread().get(0).content();
            String header = "[" + point.id() + "] "
                    + point.classification().priority() + " · "
                    + point.classification().scope()
                    + (point.classification().location() != null
                       ? " · " + point.classification().location() : "")
                    + " — " + firstContent;

            if (point.currentStatus() == ReviewStatus.AGREED) {
                sb.append("## ").append(statusMarker).append(" ~~").append(header).append("~~\n");
            } else {
                sb.append("## ").append(statusMarker).append(" ").append(header).append("\n");
            }

            for (ThreadEntry entry : point.thread()) {
                String typeLabel = switch (entry.type()) {
                    case RAISE      -> "raise";
                    case AGREE      -> "agree";
                    case DISPUTE    -> "dispute";
                    case QUALIFY    -> "qualify";
                    case FLAG_HUMAN -> "flag";
                };
                sb.append("> **").append(entry.agent()).append(" (").append(typeLabel).append("):** ")
                  .append(entry.content()).append("\n");
            }
            sb.append("\n---\n\n");
        }

        if (!state.humanFlags().isEmpty()) {
            sb.append("⚑ **Human review needed:**\n");
            for (FlagEntry flag : state.humanFlags()) {
                sb.append("- ").append(flag.content()).append("\n");
            }
        }
        return sb.toString();
    }
}
