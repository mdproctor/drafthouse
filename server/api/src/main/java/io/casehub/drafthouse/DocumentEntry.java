package io.casehub.drafthouse;

import java.util.Objects;

public record DocumentEntry(String path, String label) {
    public DocumentEntry {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
        Objects.requireNonNull(label, "label");
    }
}
