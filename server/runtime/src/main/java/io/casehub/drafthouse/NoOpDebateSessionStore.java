package io.casehub.drafthouse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpDebateSessionStore implements DebateSessionStore {

    @Override
    public void save(DebateSessionSnapshot snapshot) {}

    @Override
    public Optional<DebateSessionSnapshot> load(UUID channelId) {
        return Optional.empty();
    }

    @Override
    public void remove(UUID channelId) {}

    @Override
    public Collection<DebateSessionSnapshot> loadAll() {
        return List.of();
    }
}
