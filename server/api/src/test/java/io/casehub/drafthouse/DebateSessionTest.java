package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DebateSessionTest {

    private static final UUID CHANNEL_ID   = UUID.randomUUID();
    private static final String SESSION_ID = CHANNEL_ID.toString();
    private static final String NAME       = "drafthouse/debate/d-" + SESSION_ID;

    private static DebateSession sessionWithSpec(String specPath) {
        var session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        if (specPath != null) session.addDocument(specPath, "spec");
        return session;
    }

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
        DebateSession session = sessionWithSpec("spec.md");
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
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
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
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
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
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.registerIfAbsent(AgentType.REV, () -> "rev-id");

        assertThatThrownBy(() -> session.participants().put(AgentType.IMP, "imp-id"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void participants_reflectsRegisteredRoles() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.registerIfAbsent(AgentType.REV, () -> "rev");
        session.registerIfAbsent(AgentType.IMP, () -> "imp");

        assertThat(session.participants()).containsOnlyKeys(AgentType.REV, AgentType.IMP);
        assertThat(session.participants().get(AgentType.REV)).isEqualTo("rev");
    }

    // ── instanceIdFor() ───────────────────────────────────────────────────────

    @Test
    void instanceIdFor_returnsNullBeforeRegistration() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        assertThat(session.instanceIdFor(AgentType.MODERATOR)).isNull();
    }

    // ── getters ───────────────────────────────────────────────────────────────

    @Test
    void getters_returnConstructorValues() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        assertThat(session.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(session.debateSessionId()).isEqualTo(SESSION_ID);
        assertThat(session.channelName()).isEqualTo(NAME);
    }

    @Test
    void primaryPath_derivedFromFirstDocument() {
        DebateSession session = sessionWithSpec("my-spec.md");
        assertThat(session.primaryPath()).isEqualTo("my-spec.md");
    }

    @Test
    void primaryPath_nullWhenNoDocuments() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        assertThat(session.primaryPath()).isNull();
    }

    // ── contextTracker() ──────────────────────────────────────────────────

    @Test
    void contextTracker_isInitializedOnConstruction() {
        DebateSession session = sessionWithSpec("spec.md");
        assertThat(session.contextTracker()).isNotNull();
        var snap = session.contextTracker().snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isZero();
        assertThat(snap.messageCount()).isZero();
    }

    @Test
    void contextTracker_accumulatesAcrossMultipleCalls() {
        DebateSession session = sessionWithSpec("spec.md");
        session.contextTracker().addContribution(1000);
        session.contextTracker().addContribution(2000);
        var snap = session.contextTracker().snapshot(800_000, 80.0);
        assertThat(snap.serverContributionChars()).isEqualTo(3000);
        assertThat(snap.messageCount()).isEqualTo(2);
    }

    // ── addDocument() ─────────────────────────────────────────────────────
    @Test
    void addDocument_newPath_returnsTrue() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        assertThat(session.addDocument("/a.md", "spec")).isTrue();
        assertThat(session.documents()).hasSize(1);
        assertThat(session.documents().get(0).path()).isEqualTo("/a.md");
    }

    @Test
    void addDocument_duplicatePath_returnsFalse() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        assertThat(session.addDocument("/a.md", "other")).isFalse();
        assertThat(session.documents()).hasSize(1);
    }

    // ── removeDocument() ──────────────────────────────────────────────────
    @Test
    void removeDocument_existingNonPrimary_returnsComparisonCleared() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        session.addDocument("/b.md", "impl");
        session.setComparison("/a.md", "/b.md");

        boolean comparisonCleared = session.removeDocument("/b.md");
        assertThat(comparisonCleared).isTrue();
        assertThat(session.documents()).hasSize(1);
        assertThat(session.currentComparison()).isNull();
    }

    @Test
    void removeDocument_noComparisonAffected_returnsFalse() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        session.addDocument("/b.md", "impl");
        session.addDocument("/c.md", "test");
        session.setComparison("/a.md", "/b.md");

        boolean comparisonCleared = session.removeDocument("/c.md");
        assertThat(comparisonCleared).isFalse();
        assertThat(session.currentComparison()).isNotNull();
    }

    @Test
    void removeDocument_primary_throwsIllegalArgument() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        session.addDocument("/b.md", "impl");

        assertThatThrownBy(() -> session.removeDocument("/a.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primary");
    }

    @Test
    void removeDocument_notFound_throwsIllegalArgument() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");

        assertThatThrownBy(() -> session.removeDocument("/no-such.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in document set");
    }

    // ── setComparison() ───────────────────────────────────────────────────
    @Test
    void setComparison_validPaths_sets() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        session.addDocument("/b.md", "impl");
        session.setComparison("/a.md", "/b.md");
        assertThat(session.currentComparison().pathA()).isEqualTo("/a.md");
        assertThat(session.currentComparison().pathB()).isEqualTo("/b.md");
    }

    @Test
    void setComparison_pathNotInSet_throwsIllegalArgument() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");

        assertThatThrownBy(() -> session.setComparison("/a.md", "/missing.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── documents() and primary() ─────────────────────────────────────────
    @Test
    void documents_returnsDefensiveCopy() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        assertThatThrownBy(() -> session.documents().add(new DocumentEntry("/b.md", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void primary_returnsFirstDocument() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        session.addDocument("/b.md", "impl");
        assertThat(session.primary()).isPresent();
        assertThat(session.primary().get().path()).isEqualTo("/a.md");
    }

    @Test
    void primary_empty_returnsEmpty() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        assertThat(session.primary()).isEmpty();
    }

    // ── branchFrom() ──────────────────────────────────────────────────────
    @Test
    void branchFrom_copiesDocumentsAndComparison() {
        DebateSession original = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        original.addDocument("/a.md", "spec");
        original.addDocument("/b.md", "impl");
        original.setComparison("/a.md", "/b.md");

        UUID newId = UUID.randomUUID();
        DebateSession branched = DebateSession.branchFrom(original,
                newId, newId.toString(), "new-channel");

        assertThat(branched.channelId()).isEqualTo(newId);
        assertThat(branched.documents()).hasSize(2);
        assertThat(branched.currentComparison().pathA()).isEqualTo("/a.md");

        // mutations on branch don't affect original
        branched.addDocument("/c.md", "test");
        assertThat(original.documents()).hasSize(2);
    }

    // ── snapshot() / fromSnapshot() ───────────────────────────────────────
    @Test
    void snapshot_capturesAllDurableState() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        session.addDocument("/b.md", "impl");
        session.setComparison("/a.md", "/b.md");
        session.registerIfAbsent(AgentType.REV, () -> "rev-id");

        DebateSessionSnapshot snap = session.snapshot();
        assertThat(snap.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(snap.debateSessionId()).isEqualTo(SESSION_ID);
        assertThat(snap.channelName()).isEqualTo(NAME);
        assertThat(snap.documents()).hasSize(2);
        assertThat(snap.documents().get(0).path()).isEqualTo("/a.md");
        assertThat(snap.comparison()).isNotNull();
        assertThat(snap.comparison().pathA()).isEqualTo("/a.md");
        assertThat(snap.participants()).containsEntry(AgentType.REV, "rev-id");
    }

    @Test
    void fromSnapshot_reconstitutesLiveSession() {
        var snap = new DebateSessionSnapshot(
                CHANNEL_ID, SESSION_ID, NAME,
                List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
                new ComparisonPair("/a.md", "/b.md"),
                Map.of(AgentType.REV, "rev-id", AgentType.IMP, "imp-id"));

        DebateSession session = DebateSession.fromSnapshot(snap);
        assertThat(session.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(session.documents()).hasSize(2);
        assertThat(session.currentComparison().pathA()).isEqualTo("/a.md");
        assertThat(session.instanceIdFor(AgentType.REV)).isEqualTo("rev-id");
        assertThat(session.instanceIdFor(AgentType.IMP)).isEqualTo("imp-id");
        assertThat(session.contextTracker()).isNotNull();
        assertThat(session.currentSelection()).isNull();
    }

    @Test
    void snapshot_documentsAreImmutableCopy() {
        DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
        session.addDocument("/a.md", "spec");
        DebateSessionSnapshot snap = session.snapshot();

        session.addDocument("/b.md", "impl");
        assertThat(snap.documents()).hasSize(1);
    }
}
