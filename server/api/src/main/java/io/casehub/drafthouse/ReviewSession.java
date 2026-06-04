package io.casehub.drafthouse;

import java.util.UUID;

/**
 * Immutable snapshot of an active document review session.
 *
 * selectionSide and selectionText are both null when no selection is active,
 * or both non-null when a selection is active. Mixed state is rejected by the
 * compact constructor.
 *
 * channelName is stored explicitly because the naming slug ("drafthouse/{uuid}")
 * is independent from channel.id — it cannot be reconstructed from sessionId.
 *
 * docAContent and docBContent store the full document text. Content is session-private
 * and ephemeral; using Qhorus DataService (cross-agent shared bus) would be the wrong
 * abstraction for this use case.
 */
public record ReviewSession(
        UUID channelId,       // registry key; also UUID.fromString(sessionId)
        String sessionId,     // channel.id.toString() — the caller's stable handle
        String channelName,   // "drafthouse/{slug}" — needed by end_review for deletion
        String instanceId,    // "drafthouse-reviewer-{sessionId}"
        String docAContent,   // full text of document A (bounded by maxDocChars)
        String docBContent,   // full text of document B (bounded by maxDocChars)
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

    public ReviewSession withSelection(final DocumentSide side, final String text) {
        return new ReviewSession(
                channelId, sessionId, channelName, instanceId,
                docAContent, docBContent, side, text, personality
        );
    }
}
