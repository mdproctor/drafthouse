package io.casehub.drafthouse;

import java.util.UUID;

public record ReviewSession(
        UUID channelId,
        String sessionId,
        String channelName,
        String instanceId,
        String docAContent,
        String docBContent,
        SelectionScope selection,
        ResolvedReviewer reviewer
) {
    public ReviewSession withSelection(final SelectionScope selection) {
        return new ReviewSession(
                channelId, sessionId, channelName, instanceId,
                docAContent, docBContent, selection, reviewer
        );
    }
}
