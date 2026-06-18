package io.casehub.drafthouse;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry of active debate sessions, keyed by Qhorus channel ID.
 * Thread-safety: implementations must be safe for concurrent access.
 */
public interface DebateSessionRegistry {

    /** Returns the session for the given channel, or empty if no session is active. */
    Optional<DebateSession> find(UUID channelId);

    /** Registers a new session. Replaces any existing session for the same channelId. */
    void put(DebateSession session);

    /** Removes the session for the given channel. No-op if not found. */
    void remove(UUID channelId);

    /** Persists the current session state to the store without re-registering in the cache. */
    void persist(DebateSession session);

    /** Returns a snapshot of all active sessions. Safe to iterate concurrently. */
    Collection<DebateSession> activeSessions();
}
