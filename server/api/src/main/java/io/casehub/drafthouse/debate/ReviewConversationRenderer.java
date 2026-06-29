package io.casehub.drafthouse.debate;

import io.casehub.blocks.conversation.ConversationPoint;
import io.casehub.blocks.conversation.ConversationState;
import io.casehub.blocks.conversation.ThreadEntry;

import java.util.List;

public class ReviewConversationRenderer {

    private static final String SENTINEL = "No prior review activity in this session.";

    public String render(ConversationState state) {
        var sb = new StringBuilder();
        for (ConversationPoint point : state.points().values()) {
            if (!"AGREED".equals(point.status())
                    && !"DECLINED".equals(point.status())) {
                continue;
            }
            String question = point.thread().isEmpty() ? ""
                : (point.thread().get(0).content() != null ? point.thread().get(0).content() : "");
            sb.append("Q: ").append(question).append("\n");

            String rawAnswer = lastResponseContent(point.thread());
            if ("DECLINED".equals(point.status())) {
                String reason = rawAnswer.endsWith(".")
                        ? rawAnswer.substring(0, rawAnswer.length() - 1)
                        : rawAnswer;
                sb.append("A: (Declined — ").append(reason).append(")\n");
            } else {
                sb.append("A: ").append(rawAnswer).append("\n");
            }
            sb.append("\n");
        }
        String result = sb.toString().strip();
        return result.isEmpty() ? SENTINEL : result;
    }

    private static String lastResponseContent(List<ThreadEntry> thread) {
        for (int i = thread.size() - 1; i >= 0; i--) {
            String entryType = thread.get(i).entryType();
            if ("AGREE".equals(entryType)
                    || "QUALIFY".equals(entryType)
                    || "DISPUTE".equals(entryType)
                    || "DECLINED".equals(entryType)) {
                String c = thread.get(i).content();
                return c != null ? c : "";
            }
        }
        return "";
    }
}
