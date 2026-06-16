package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewSessionRegistryTest {

    private ReviewSessionRegistryImpl registry;

    @BeforeEach
    void setUp() {
        registry = new ReviewSessionRegistryImpl();
    }

    @Test
    void find_returnsSession_afterPut() {
        final UUID channelId = UUID.randomUUID();
        final ReviewSession session = minimal(channelId);
        registry.put(session);
        assertThat(registry.find(channelId)).contains(session);
    }

    @Test
    void remove_clearsSession() {
        final UUID channelId = UUID.randomUUID();
        registry.put(minimal(channelId));
        registry.remove(channelId);
        assertThat(registry.find(channelId)).isEmpty();
    }

    @Test
    void updateSelection_replacesSelectionFields() {
        final UUID channelId = UUID.randomUUID();
        registry.put(minimal(channelId));
        registry.updateSelection(channelId, new SelectionScope(DocumentSide.A, 0, 0, "selected text"));
        final ReviewSession updated = registry.find(channelId).orElseThrow();
        assertThat(updated.selection()).isNotNull();
        assertThat(updated.selection().side()).isEqualTo(DocumentSide.A);
        assertThat(updated.selection().selectedText()).isEqualTo("selected text");
    }

    private ReviewSession minimal(final UUID channelId) {
        return new ReviewSession(
                channelId, channelId.toString(), "drafthouse/test",
                "iid", "docA", "docB", null, "personality");
    }
}
