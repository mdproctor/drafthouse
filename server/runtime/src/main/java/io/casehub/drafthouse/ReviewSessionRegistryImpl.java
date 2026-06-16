package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory registry of active review sessions, keyed by Qhorus channel ID.
 * Thread-safe via ConcurrentHashMap; updateSelection is atomic via computeIfPresent.
 */
@ApplicationScoped
public class ReviewSessionRegistryImpl implements ReviewSessionRegistry {

    private final ConcurrentHashMap<UUID, ReviewSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<ReviewSession> find(final UUID channelId) {
        return Optional.ofNullable(sessions.get(channelId));
    }

    @Override
    public void put(final ReviewSession session) {
        sessions.put(session.channelId(), session);
    }

    @Override
    public void remove(final UUID channelId) {
        sessions.remove(channelId);
    }

    @Override
    public void updateSelection(final UUID channelId, final SelectionScope selection) {
        sessions.computeIfPresent(channelId, (id, s) -> s.withSelection(selection));
    }
}
