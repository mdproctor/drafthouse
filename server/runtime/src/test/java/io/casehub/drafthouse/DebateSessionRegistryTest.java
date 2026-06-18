package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DebateSessionRegistryTest {

    private DebateSessionRegistryImpl registry;
    private DebateSessionStore store;

    @BeforeEach
    void setUp() {
        store = mock(DebateSessionStore.class);
        when(store.loadAll()).thenReturn(List.of());
        registry = new DebateSessionRegistryImpl();
        registry.store = store;
        registry.init();
    }

    @Test
    void activeSessions_empty_returnsEmptyCollection() {
        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).isEmpty();
    }

    @Test
    void activeSessions_afterPut_containsSession() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test");
        session.addDocument("test-spec.md", "spec");
        registry.put(session);

        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.iterator().next().channelId()).isEqualTo(channelId);
    }

    @Test
    void activeSessions_afterRemove_doesNotContainSession() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test");
        session.addDocument("test-spec.md", "spec");
        registry.put(session);
        registry.remove(channelId);

        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).isEmpty();
    }

    @Test
    void put_delegatesToStore() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test");
        session.addDocument("test-spec.md", "spec");
        registry.put(session);

        var captor = ArgumentCaptor.forClass(DebateSessionSnapshot.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().channelId()).isEqualTo(channelId);
    }

    @Test
    void remove_delegatesToStore() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test");
        session.addDocument("test-spec.md", "spec");
        registry.put(session);
        registry.remove(channelId);

        verify(store).remove(channelId);
    }

    @Test
    void persist_savesSnapshotToStore() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test");
        session.addDocument("test-spec.md", "spec");
        registry.put(session);
        reset(store);

        session.addDocument("/b.md", "impl");
        registry.persist(session);

        var captor = ArgumentCaptor.forClass(DebateSessionSnapshot.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().documents()).hasSize(2);
    }

    @Test
    void init_loadsFromStore() {
        UUID channelId = UUID.randomUUID();
        var snap = new DebateSessionSnapshot(
                channelId, channelId.toString(), "ch-name",
                List.of(new DocumentEntry("/a.md", "spec")),
                null, Map.of());
        when(store.loadAll()).thenReturn(List.of(snap));

        var freshRegistry = new DebateSessionRegistryImpl();
        freshRegistry.store = store;
        freshRegistry.init();

        assertThat(freshRegistry.find(channelId)).isPresent();
        assertThat(freshRegistry.find(channelId).get().documents()).hasSize(1);
    }
}
