package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrainstormSessionRegistryTest {

    private BrainstormSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BrainstormSessionRegistry();
    }

    @Test
    void find_empty_returnsEmpty() {
        assertThat(registry.find("nonexistent")).isEmpty();
    }

    @Test
    void put_thenFind_returnsSession() {
        var session = new BrainstormSession("bs-1");
        registry.put(session);

        assertThat(registry.find("bs-1")).isPresent();
        assertThat(registry.find("bs-1").get().sessionId()).isEqualTo("bs-1");
    }

    @Test
    void remove_thenFind_returnsEmpty() {
        var session = new BrainstormSession("bs-1");
        registry.put(session);
        registry.remove("bs-1");

        assertThat(registry.find("bs-1")).isEmpty();
    }

    @Test
    void remove_nonexistent_noError() {
        registry.remove("nonexistent");
    }

    @Test
    void activeSessions_empty() {
        assertThat(registry.activeSessions()).isEmpty();
    }

    @Test
    void activeSessions_afterPut_containsSession() {
        var session = new BrainstormSession("bs-1");
        registry.put(session);

        assertThat(registry.activeSessions()).hasSize(1);
        assertThat(registry.activeSessions().iterator().next().sessionId()).isEqualTo("bs-1");
    }

    @Test
    void activeSessions_afterRemove_empty() {
        var session = new BrainstormSession("bs-1");
        registry.put(session);
        registry.remove("bs-1");

        assertThat(registry.activeSessions()).isEmpty();
    }

    @Test
    void multipleSessions() {
        registry.put(new BrainstormSession("bs-1"));
        registry.put(new BrainstormSession("bs-2"));

        assertThat(registry.activeSessions()).hasSize(2);
        assertThat(registry.find("bs-1")).isPresent();
        assertThat(registry.find("bs-2")).isPresent();
    }

    @Test
    void put_replaces_existingSession() {
        var session1 = new BrainstormSession("bs-1");
        session1.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        registry.put(session1);

        var session2 = new BrainstormSession("bs-1");
        registry.put(session2);

        assertThat(registry.find("bs-1").get().options()).isEmpty();
    }

    @Test
    void activeSessions_isSnapshot() {
        registry.put(new BrainstormSession("bs-1"));
        var snapshot = registry.activeSessions();
        registry.put(new BrainstormSession("bs-2"));

        assertThat(snapshot).hasSize(1);
    }
}
