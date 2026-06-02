package io.casehub.drafthouse.debate;

import java.util.LinkedHashMap;
import java.util.List;

public record ReviewState(LinkedHashMap<String, ReviewPoint> points,
                          List<FlagEntry> humanFlags) {}
