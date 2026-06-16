package io.casehub.drafthouse;

import java.util.UUID;

/**
 * Immutable snapshot of an active document review session.
 *
 * selection is nullable: null when no selection is active, non-null when a
 * user has selected text in one of the document panels.
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
        SelectionScope selection,  // null = no selection active
        String personality
) {
    public ReviewSession withSelection(final SelectionScope selection) {
        return new ReviewSession(
                channelId, sessionId, channelName, instanceId,
                docAContent, docBContent, selection, personality
        );
    }
}
