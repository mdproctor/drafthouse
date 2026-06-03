package io.casehub.drafthouse;

import java.util.UUID;

/**
 * Immutable snapshot of an active document review session.
 *
 * selectionSide and selectionText are both null when no selection is active,
 * or both non-null when a selection is active. Mixed state is rejected by the
 * compact constructor.
 *
 * instanceId is the per-session Qhorus instance ID registered at start_review time.
 * docAKey and docBKey are Qhorus SharedData keys: "drafthouse/{sessionId}/doc-a" etc.
 */
public record ReviewSession(
        UUID channelId,
        String sessionId,
        String instanceId,
        String docAKey,
        String docBKey,
        DocumentSide selectionSide,  // null = no selection active (must match selectionText)
        String selectionText,        // null = no selection active (must match selectionSide)
        String personality
) {
    public ReviewSession {
        if ((selectionSide == null) != (selectionText == null)) {
            throw new IllegalArgumentException(
                    "selectionSide and selectionText must both be null or both be non-null");
        }
    }

    /**
     * Returns a new ReviewSession with updated selection, preserving all other fields.
     * Pass null for both arguments to clear the selection.
     *
     * @param side null clears the selection; text must also be null when side is null
     * @param text null clears the selection; side must also be null when text is null
     */
    public ReviewSession withSelection(final DocumentSide side, final String text) {
        return new ReviewSession(
                channelId, sessionId, instanceId, docAKey, docBKey,
                side, text, personality
        );
    }
}
