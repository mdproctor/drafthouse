package io.casehub.drafthouse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * In-memory registry of active debate sessions, keyed by Qhorus channel ID.
 * Thread-safe via ConcurrentHashMap. Write-through cache backed by DebateSessionStore.
 */
@ApplicationScoped
public class DebateSessionRegistryImpl implements DebateSessionRegistry {

    private final ConcurrentHashMap<UUID, DebateSession> sessions = new ConcurrentHashMap<>();

    @Inject
    DebateSessionStore store;

    @jakarta.annotation.PostConstruct
    void init() {
        for (DebateSessionSnapshot snap : store.loadAll()) {
            sessions.put(snap.channelId(), DebateSession.fromSnapshot(snap));
        }
    }

    @Override
    public Optional<DebateSession> find(final UUID channelId) {
        return Optional.ofNullable(sessions.get(channelId));
    }

    @Override
    public void put(final DebateSession session) {
        sessions.put(session.channelId(), session);
        store.save(session.snapshot());
    }

    @Override
    public void remove(final UUID channelId) {
        sessions.remove(channelId);
        store.remove(channelId);
    }

    @Override
    public void persist(final DebateSession session) {
        store.save(session.snapshot());
    }

    @Override
    public Collection<DebateSession> activeSessions() {
        return List.copyOf(sessions.values());
    }
}
