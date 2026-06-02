package io.casehub.drafthouse.debate;

import java.util.List;

public record ReviewPoint(String id, PointClassification classification,
                          List<ThreadEntry> thread, ReviewStatus currentStatus) {}
