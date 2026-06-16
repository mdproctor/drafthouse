package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SelectionScopeTest {
    @Test void validConstruction() {
        var scope = new SelectionScope(DocumentSide.A, 5, 12, "selected text");
        assertThat(scope.side()).isEqualTo(DocumentSide.A);
        assertThat(scope.startLine()).isEqualTo(5);
        assertThat(scope.endLine()).isEqualTo(12);
        assertThat(scope.selectedText()).isEqualTo("selected text");
    }
    @Test void zeroLines_validForReviewPath() {
        var scope = new SelectionScope(DocumentSide.B, 0, 0, "text only");
        assertThat(scope.startLine()).isEqualTo(0);
        assertThat(scope.endLine()).isEqualTo(0);
    }
    @Test void nullSide_throws() {
        assertThatThrownBy(() -> new SelectionScope(null, 0, 0, "text"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("side");
    }
    @Test void nullSelectedText_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void blankSelectedText_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, 0, 0, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void negativeStartLine_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, -1, 0, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void endLineBeforeStartLine_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, 5, 3, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
