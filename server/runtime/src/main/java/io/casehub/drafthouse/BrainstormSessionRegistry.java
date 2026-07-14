package io.casehub.drafthouse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BrainstormSessionRegistry {

    private final ConcurrentHashMap<String, BrainstormSession> sessions = new ConcurrentHashMap<>();

    public Optional<BrainstormSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void put(BrainstormSession session) {
        sessions.put(session.sessionId(), session);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public Collection<BrainstormSession> activeSessions() {
        return List.copyOf(sessions.values());
    }
}
