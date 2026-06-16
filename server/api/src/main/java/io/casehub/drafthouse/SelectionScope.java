package io.casehub.drafthouse;

import java.util.Objects;

public record SelectionScope(DocumentSide side, int startLine, int endLine, String selectedText) {
    public SelectionScope {
        Objects.requireNonNull(side, "side");
        if (selectedText == null || selectedText.isBlank()) {
            throw new IllegalArgumentException("selectedText must be non-null and non-blank");
        }
        if (startLine < 0) throw new IllegalArgumentException("startLine must be >= 0");
        if (endLine < startLine) throw new IllegalArgumentException("endLine must be >= startLine");
    }
}
