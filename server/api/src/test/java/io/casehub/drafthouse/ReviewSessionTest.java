package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReviewSessionTest {

    private static final UUID CHANNEL_ID = UUID.randomUUID();

    private ReviewSession minimal() {
        return new ReviewSession(
                CHANNEL_ID,
                "session-1",
                "drafthouse-reviewer-session-1",
                "drafthouse/session-1/doc-a",
                "drafthouse/session-1/doc-b",
                null,
                null,
                "You are a reviewer."
        );
    }

    @Test
    void constructsWithRequiredFields() {
        var session = minimal();
        assertEquals(CHANNEL_ID, session.channelId());
        assertEquals("session-1", session.sessionId());
        assertEquals("drafthouse-reviewer-session-1", session.instanceId());
        assertEquals("drafthouse/session-1/doc-a", session.docAKey());
        assertEquals("drafthouse/session-1/doc-b", session.docBKey());
        assertNull(session.selectionSide());
        assertNull(session.selectionText());
        assertEquals("You are a reviewer.", session.personality());
    }

    @Test
    void constructsWithSelection() {
        var session = new ReviewSession(
                CHANNEL_ID, "s", "i", "a", "b",
                DocumentSide.A, "selected text", "persona"
        );
        assertEquals(DocumentSide.A, session.selectionSide());
        assertEquals("selected text", session.selectionText());
    }

    @Test
    void halfNullSelectionStateRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewSession(CHANNEL_ID, "s", "i", "a", "b", DocumentSide.A, null, "p"));
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewSession(CHANNEL_ID, "s", "i", "a", "b", null, "text", "p"));
    }

    @Test
    void equalityByValue() {
        assertEquals(minimal(), minimal());
    }

    @Test
    void withSelectionReturnsNewRecord() {
        var original = minimal();
        var updated = original.withSelection(DocumentSide.B, "some text");
        // original unchanged
        assertNull(original.selectionSide());
        // updated carries selection
        assertEquals(DocumentSide.B, updated.selectionSide());
        assertEquals("some text", updated.selectionText());
        // all fields preserved
        assertEquals(original.channelId(), updated.channelId());
        assertEquals(original.sessionId(), updated.sessionId());
        assertEquals(original.instanceId(), updated.instanceId());
        assertEquals(original.docAKey(), updated.docAKey());
        assertEquals(original.docBKey(), updated.docBKey());
        assertEquals(original.personality(), updated.personality());
    }

    @Test
    void withSelectionClearsSelection() {
        var withSelection = new ReviewSession(
                CHANNEL_ID, "s", "i", "a", "b", DocumentSide.A, "text", "p"
        );
        var cleared = withSelection.withSelection(null, null);
        assertNull(cleared.selectionSide());
        assertNull(cleared.selectionText());
    }
}
