package io.casehub.drafthouse.debate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReviewState(Map<String, ReviewPoint> points,
                          List<FlagEntry> humanFlags) {
    public ReviewState {
        // Defensive copy + unmodifiable wrapper to protect the pure left-fold contract.
        // DebateChannelProjection always constructs new ReviewState on each apply(); callers
        // must not mutate the returned state.
        points     = Collections.unmodifiableMap(new LinkedHashMap<>(points));
        humanFlags = Collections.unmodifiableList(List.copyOf(humanFlags));
    }
}
