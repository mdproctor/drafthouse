package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
@TestProfile(JpaDebateSessionStoreTest.PersistenceProfile.class)
class JpaDebateSessionStoreTest {

    public static class PersistenceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.drafthouse.persistence.enabled", "true");
        }
    }

    @Inject
    DebateSessionStore store;

    @Test
    void store_isJpaImplementation() {
        assertThat(store).isInstanceOf(JpaDebateSessionStore.class);
    }

    @Test
    void save_and_load_roundTrips() {
        UUID id = UUID.randomUUID();
        var snap = new DebateSessionSnapshot(
                id, id.toString(), "ch-name",
                List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
                new ComparisonPair("/a.md", "/b.md"),
                Map.of(AgentType.REV, "rev-id", AgentType.IMP, "imp-id"));

        store.save(snap);
        var loaded = store.load(id);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().documents()).hasSize(2);
        assertThat(loaded.get().documents().get(0).path()).isEqualTo("/a.md");
        assertThat(loaded.get().comparison().pathA()).isEqualTo("/a.md");
        assertThat(loaded.get().participants()).hasSize(2);
    }

    @Test
    void save_update_overwritesExisting() {
        UUID id = UUID.randomUUID();
        var snap = new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec")), null, Map.of());
        store.save(snap);

        var updated = new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
                new ComparisonPair("/a.md", "/b.md"),
                Map.of(AgentType.REV, "rev-id"));
        store.save(updated);

        var loaded = store.load(id);
        assertThat(loaded.get().documents()).hasSize(2);
        assertThat(loaded.get().comparison()).isNotNull();
        assertThat(loaded.get().participants()).hasSize(1);
    }

    @Test
    void remove_makesLoadReturnEmpty() {
        UUID id = UUID.randomUUID();
        store.save(new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec")), null, Map.of()));
        store.remove(id);
        assertThat(store.load(id)).isEmpty();
    }

    @Test
    void save_nullComparison_persistsCorrectly() {
        UUID id = UUID.randomUUID();
        store.save(new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec")), null, Map.of()));
        var loaded = store.load(id);
        assertThat(loaded.get().comparison()).isNull();
    }

    @Test
    void save_documentOrder_preserved() {
        UUID id = UUID.randomUUID();
        var docs = List.of(
                new DocumentEntry("/c.md", "third"),
                new DocumentEntry("/a.md", "first"),
                new DocumentEntry("/b.md", "second"));
        store.save(new DebateSessionSnapshot(id, id.toString(), "ch", docs, null, Map.of()));
        var loaded = store.load(id);
        assertThat(loaded.get().documents().get(0).path()).isEqualTo("/c.md");
        assertThat(loaded.get().documents().get(1).path()).isEqualTo("/a.md");
        assertThat(loaded.get().documents().get(2).path()).isEqualTo("/b.md");
    }
}
