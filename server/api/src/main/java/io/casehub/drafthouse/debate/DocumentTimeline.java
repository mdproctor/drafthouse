package io.casehub.drafthouse.debate;

import java.util.List;

public record DocumentTimeline(
    String documentId,
    List<DocumentSnapshot> snapshots
) {}
