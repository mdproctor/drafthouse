package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentTimelineModelTest {

    @Test
    void snapshotSource_gitCommit_carries_round_number() {
        var source = new SnapshotSource.GitCommit("abc123", Instant.parse("2026-07-07T10:00:00Z"), 1);
        assertEquals("abc123", source.commitHash());
        assertEquals(1, source.roundNumber());
    }

    @Test
    void documentSnapshot_uses_label_for_display() {
        var source = new SnapshotSource.GitCommit("abc123", Instant.parse("2026-07-07T10:00:00Z"), 0);
        var snap = new DocumentSnapshot("docs/spec.md", "Round 0 (original)", source);
        assertEquals("Round 0 (original)", snap.label());
        assertNotNull(snap.documentPath()); // Fixed: should be assertNotNull
    }

    @Test
    void documentTimeline_orders_snapshots() {
        var s0 = new DocumentSnapshot("spec.md", "Round 0", new SnapshotSource.GitCommit("aaa", Instant.EPOCH, 0));
        var s1 = new DocumentSnapshot("spec.md", "Round 1", new SnapshotSource.GitCommit("bbb", Instant.EPOCH.plusSeconds(60), 1));
        var timeline = new DocumentTimeline("spec.md", List.of(s0, s1));
        assertEquals(2, timeline.snapshots().size());
        assertEquals("Round 0", timeline.snapshots().get(0).label());
    }

    @Test
    void entryType_round_snapshot_exists() {
        assertNotNull(EntryType.valueOf("ROUND_SNAPSHOT"));
    }
}
