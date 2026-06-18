package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DebateSessionStoreContractTest {

    private final DebateSessionStore store = new InMemoryDebateSessionStore();

    private DebateSessionSnapshot testSnapshot() {
        UUID channelId = UUID.randomUUID();
        return new DebateSessionSnapshot(
                channelId, channelId.toString(), "drafthouse/debate/d-test",
                List.of(new DocumentEntry("/a.md", "spec")),
                new ComparisonPair("/a.md", "/b.md"),
                Map.of(AgentType.REV, "rev-id"));
    }

    @Test
    void save_and_load_roundTrips() {
        var snap = testSnapshot();
        store.save(snap);
        var loaded = store.load(snap.channelId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().channelId()).isEqualTo(snap.channelId());
        assertThat(loaded.get().documents()).hasSize(1);
        assertThat(loaded.get().comparison().pathA()).isEqualTo("/a.md");
        assertThat(loaded.get().participants()).containsEntry(AgentType.REV, "rev-id");
    }

    @Test
    void load_nonexistent_returnsEmpty() {
        assertThat(store.load(UUID.randomUUID())).isEmpty();
    }

    @Test
    void remove_existing_makesLoadReturnEmpty() {
        var snap = testSnapshot();
        store.save(snap);
        store.remove(snap.channelId());
        assertThat(store.load(snap.channelId())).isEmpty();
    }

    @Test
    void loadAll_returnsAllSaved() {
        var snap1 = testSnapshot();
        var snap2 = testSnapshot();
        store.save(snap1);
        store.save(snap2);
        assertThat(store.loadAll()).hasSize(2);
    }

    @Test
    void save_sameId_updatesExisting() {
        var snap = testSnapshot();
        store.save(snap);
        var updated = new DebateSessionSnapshot(
                snap.channelId(), snap.debateSessionId(), snap.channelName(),
                List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
                null, snap.participants());
        store.save(updated);
        var loaded = store.load(snap.channelId());
        assertThat(loaded.get().documents()).hasSize(2);
        assertThat(loaded.get().comparison()).isNull();
    }

    /** Trivial in-memory implementation for contract testing only. */
    static class InMemoryDebateSessionStore implements DebateSessionStore {
        private final java.util.concurrent.ConcurrentHashMap<UUID, DebateSessionSnapshot> map
                = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void save(DebateSessionSnapshot snapshot) {
            map.put(snapshot.channelId(), snapshot);
        }
        @Override public java.util.Optional<DebateSessionSnapshot> load(UUID channelId) {
            return java.util.Optional.ofNullable(map.get(channelId));
        }
        @Override public void remove(UUID channelId) { map.remove(channelId); }
        @Override public java.util.Collection<DebateSessionSnapshot> loadAll() {
            return List.copyOf(map.values());
        }
    }
}
