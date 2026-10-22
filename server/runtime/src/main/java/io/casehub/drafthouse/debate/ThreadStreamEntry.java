package io.casehub.drafthouse.debate;

import io.casehub.agency.communication.dispatch.ChannelMessageMeta;
import io.casehub.agency.deliberation.conversation.ConversationProtocol;

import java.time.Instant;
import java.util.Map;

public record ThreadStreamEntry(
        String threadId,
        String threadAction,
        String content,
        String agentRole,
        String sender,
        Instant timestamp,
        Anchor anchor) {

    public record Anchor(String side, int startLine, int endLine, String selectedText) {}

    public static ThreadStreamEntry from(String encodedContent, String sender) {
        Map<String, String> meta = DebateProtocol.parseMeta(encodedContent);
        String threadId = meta.get("threadId");
        if (threadId == null) return null;

        String action = meta.get("threadAction");
        if (action == null) return null;

        String role = meta.get(ConversationProtocol.ROLE);
        String body = DebateProtocol.bodyContent(encodedContent);

        Anchor anchor = null;
        if ("START".equals(action)) {
            String side = meta.get("side");
            String startLine = meta.get("startLine");
            String endLine = meta.get("endLine");
            String selectedText = meta.get("selectedText");
            if (side != null && startLine != null && endLine != null && selectedText != null) {
                try {
                    anchor = new Anchor(side, Integer.parseInt(startLine),
                            Integer.parseInt(endLine), selectedText);
                } catch (NumberFormatException ignored) {}
            }
        }

        return new ThreadStreamEntry(threadId, action, body, role, sender, Instant.now(), anchor);
    }

    public static ThreadStreamEntry from(io.casehub.qhorus.api.gateway.OutboundMessage msg) {
        if (msg.content() == null) return null;
        return from(msg.content(), msg.sender());
    }
}
