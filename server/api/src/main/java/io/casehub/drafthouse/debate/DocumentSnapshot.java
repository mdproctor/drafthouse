package io.casehub.drafthouse.debate;

public record DocumentSnapshot(
    String documentPath,
    String label,
    SnapshotSource source
) {}
