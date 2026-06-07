package io.casehub.drafthouse.debate;

import java.util.List;

public class ReviewConversationRenderer {

    private static final String SENTINEL = "No prior review activity in this session.";

    public String render(ReviewState state) {
        var sb = new StringBuilder();
        for (ReviewPoint point : state.points().values()) {
            if (point.currentStatus() != ReviewStatus.AGREED
                    && point.currentStatus() != ReviewStatus.DECLINED) {
                continue;
            }
            String question = point.thread().isEmpty() ? ""
                : (point.thread().get(0).content() != null ? point.thread().get(0).content() : "");
            sb.append("Q: ").append(question).append("\n");

            String rawAnswer = lastResponseContent(point.thread());
            if (point.currentStatus() == ReviewStatus.DECLINED) {
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
            if (thread.get(i).type() == EntryType.AGREE
                    || thread.get(i).type() == EntryType.QUALIFY
                    || thread.get(i).type() == EntryType.DISPUTE
                    || thread.get(i).type() == EntryType.DECLINED) {
                String c = thread.get(i).content();
                return c != null ? c : "";
            }
        }
        return "";
    }
}
