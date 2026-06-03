package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;

/**
 * Registry of active document review sessions, keyed by Qhorus channel ID.
 *
 * Implemented by ReviewerChannelBackendFactory in the runtime module, which
 * holds the Map&lt;UUID, ReviewSession&gt; and registers ChannelBackend instances.
 * DraftHouseMcpTools injects this interface, not the factory directly.
 *
 * Thread-safety: implementations must be safe for concurrent calls from
 * the Qhorus InProcessMessageBus (async CDI event delivery).
 */
public interface ReviewSessionRegistry {

    /** Returns the session for the given channel, or empty if no session is active. */
    Optional<ReviewSession> find(UUID channelId);

    /** Registers a new session. Replaces any existing session for the same channelId. */
    void put(ReviewSession session);

    /** Removes the session for the given channel. No-op if not found. */
    void remove(UUID channelId);

    /**
     * Atomically replaces the ReviewSession with an updated selection state.
     * No-op if no session exists for the given channelId.
     *
     * @param side null to clear the selection; text must also be null when side is null
     * @param text null to clear the selection; side must also be null when text is null
     */
    void updateSelection(UUID channelId, DocumentSide side, String text);
}
