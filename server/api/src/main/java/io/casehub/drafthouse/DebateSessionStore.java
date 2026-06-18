package io.casehub.drafthouse;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DebateSessionStore {
    void save(DebateSessionSnapshot snapshot);
    Optional<DebateSessionSnapshot> load(UUID channelId);
    void remove(UUID channelId);
    Collection<DebateSessionSnapshot> loadAll();
}
