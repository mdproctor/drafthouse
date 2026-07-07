package io.casehub.drafthouse.debate;

import java.time.Instant;

public sealed interface SnapshotSource {
    record GitCommit(String commitHash, Instant timestamp, int roundNumber) implements SnapshotSource {}
}
