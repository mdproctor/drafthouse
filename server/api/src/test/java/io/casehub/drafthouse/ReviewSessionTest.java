package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSessionTest {

    private static final UUID CHANNEL_ID = UUID.randomUUID();

    private ReviewSession minimal() {
        return new ReviewSession(
                CHANNEL_ID,
                CHANNEL_ID.toString(),
                "drafthouse/test-channel",
                "drafthouse-reviewer-" + CHANNEL_ID,
                "Content of document A",
                "Content of document B",
                null,
                null,
                "You are a reviewer."
        );
    }

    @Test
    void constructsWithRequiredFields() {
        var s = minimal();
        assertEquals(CHANNEL_ID, s.channelId());
        assertEquals(CHANNEL_ID.toString(), s.sessionId());
        assertEquals("drafthouse/test-channel", s.channelName());
        assertEquals("drafthouse-reviewer-" + CHANNEL_ID, s.instanceId());
        assertEquals("Content of document A", s.docAContent());
        assertEquals("Content of document B", s.docBContent());
        assertNull(s.selectionSide());
        assertNull(s.selectionText());
        assertEquals("You are a reviewer.", s.personality());
    }

    @Test
    void constructsWithSelection() {
        var s = new ReviewSession(
                CHANNEL_ID, "sid", "cname", "iid", "docA", "docB",
                DocumentSide.A, "selected text", "persona"
        );
        assertEquals(DocumentSide.A, s.selectionSide());
        assertEquals("selected text", s.selectionText());
    }

    @Test
    void halfNullSelectionStateRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewSession(CHANNEL_ID, "s", "cn", "i", "a", "b",
                        DocumentSide.A, null, "p"));
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewSession(CHANNEL_ID, "s", "cn", "i", "a", "b",
                        null, "text", "p"));
    }

    @Test
    void equalityByValue() {
        assertEquals(minimal(), minimal());
    }

    @Test
    void withSelectionReturnsNewRecord() {
        var original = minimal();
        var updated = original.withSelection(DocumentSide.B, "some text");
        assertNull(original.selectionSide());
        assertEquals(DocumentSide.B, updated.selectionSide());
        assertEquals("some text", updated.selectionText());
        assertEquals(original.channelId(), updated.channelId());
        assertEquals(original.sessionId(), updated.sessionId());
        assertEquals(original.channelName(), updated.channelName());
        assertEquals(original.instanceId(), updated.instanceId());
        assertEquals(original.docAContent(), updated.docAContent());
        assertEquals(original.docBContent(), updated.docBContent());
        assertEquals(original.personality(), updated.personality());
    }

    @Test
    void withSelectionClearsSelection() {
        var withSel = new ReviewSession(
                CHANNEL_ID, "s", "cn", "i", "a", "b", DocumentSide.A, "text", "p"
        );
        var cleared = withSel.withSelection(null, null);
        assertNull(cleared.selectionSide());
        assertNull(cleared.selectionText());
    }
}
