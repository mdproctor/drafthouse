package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;

/**
 * Registry of active document review sessions, keyed by Qhorus channel ID.
 *
 * Implemented by ReviewSessionRegistryImpl in the runtime module.
 * ReviewerChannelBackendFactory injects this interface to look up sessions
 * when wiring backends on channel init. DraftHouseMcpTools also injects it
 * directly for session lifecycle management.
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
     * @param selection the new selection, or null to clear
     */
    void updateSelection(UUID channelId, SelectionScope selection);
}
