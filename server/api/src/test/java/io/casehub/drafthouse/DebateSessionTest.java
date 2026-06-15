package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DebateSessionTest {

    private static final UUID CHANNEL_ID   = UUID.randomUUID();
    private static final String SESSION_ID = CHANNEL_ID.toString();
    private static final String NAME       = "drafthouse/debate/d-" + SESSION_ID;

    // ── instanceId() — static naming convention ───────────────────────────────

    @Test
    void instanceId_rev_hasCorrectFormat() {
        assertThat(DebateSession.instanceId(AgentType.REV, SESSION_ID))
                .isEqualTo("drafthouse-rev-" + SESSION_ID);
    }

    @Test
    void instanceId_allRoles_followLowercaseConvention() {
        for (AgentType role : AgentType.values()) {
            String id = DebateSession.instanceId(role, SESSION_ID);
            assertThat(id)
                    .startsWith("drafthouse-" + role.name().toLowerCase() + "-")
                    .endsWith(SESSION_ID);
        }
    }

    // ── registerIfAbsent() — lazy registration semantics ──────────────────────

    @Test
    void registerIfAbsent_firstCall_invokesSupplierAndStoresId() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "spec.md");
        AtomicInteger calls = new AtomicInteger();

        String id = session.registerIfAbsent(AgentType.REV, () -> {
            calls.incrementAndGet();
            return "instance-rev";
        });

        assertThat(id).isEqualTo("instance-rev");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(session.instanceIdFor(AgentType.REV)).isEqualTo("instance-rev");
    }

    @Test
    void registerIfAbsent_secondCall_returnsSameIdWithoutInvokingSupplier() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, null);
        session.registerIfAbsent(AgentType.IMP, () -> "imp-id");

        AtomicInteger calls = new AtomicInteger();
        String id = session.registerIfAbsent(AgentType.IMP, () -> {
            calls.incrementAndGet();
            return "should-not-be-returned";
        });

        assertThat(id).isEqualTo("imp-id");
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void registerIfAbsent_supplierThrows_keyRemainsAbsent_nextCallRetries() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, null);
        AtomicInteger calls = new AtomicInteger();

        // First call throws
        assertThatThrownBy(() -> session.registerIfAbsent(AgentType.SUPERVISOR, () -> {
            calls.incrementAndGet();
            throw new RuntimeException("registration failed");
        })).isInstanceOf(RuntimeException.class);

        assertThat(session.instanceIdFor(AgentType.SUPERVISOR)).isNull();

        // Second call succeeds (retry is safe — register() is an upsert)
        String id = session.registerIfAbsent(AgentType.SUPERVISOR, () -> {
            calls.incrementAndGet();
            return "supervisor-id";
        });

        assertThat(id).isEqualTo("supervisor-id");
        assertThat(calls.get()).isEqualTo(2); // called twice total
    }

    // ── participants() — unmodifiable view ────────────────────────────────────

    @Test
    void participants_returnsUnmodifiableView() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, null);
        session.registerIfAbsent(AgentType.REV, () -> "rev-id");

        assertThatThrownBy(() -> session.participants().put(AgentType.IMP, "imp-id"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void participants_reflectsRegisteredRoles() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, null);
        session.registerIfAbsent(AgentType.REV, () -> "rev");
        session.registerIfAbsent(AgentType.IMP, () -> "imp");

        assertThat(session.participants()).containsOnlyKeys(AgentType.REV, AgentType.IMP);
        assertThat(session.participants().get(AgentType.REV)).isEqualTo("rev");
    }

    // ── instanceIdFor() ───────────────────────────────────────────────────────

    @Test
    void instanceIdFor_returnsNullBeforeRegistration() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, null);
        assertThat(session.instanceIdFor(AgentType.MODERATOR)).isNull();
    }

    // ── getters ───────────────────────────────────────────────────────────────

    @Test
    void getters_returnConstructorValues() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "my-spec.md");
        assertThat(session.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(session.debateSessionId()).isEqualTo(SESSION_ID);
        assertThat(session.channelName()).isEqualTo(NAME);
        assertThat(session.specPath()).isEqualTo("my-spec.md");
    }

    // ── contextTracker() ──────────────────────────────────────────────────

    @Test
    void contextTracker_isInitializedOnConstruction() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "spec.md");
        assertThat(session.contextTracker()).isNotNull();
        var snap = session.contextTracker().snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isZero();
        assertThat(snap.messageCount()).isZero();
    }

    @Test
    void contextTracker_accumulatesAcrossMultipleCalls() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "spec.md");
        session.contextTracker().addContribution(1000);
        session.contextTracker().addContribution(2000);
        var snap = session.contextTracker().snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isEqualTo(3000);
        assertThat(snap.messageCount()).isEqualTo(2);
    }
}
