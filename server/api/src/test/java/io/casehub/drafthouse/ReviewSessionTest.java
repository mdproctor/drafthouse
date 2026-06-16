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
        assertNull(s.selection());
        assertEquals("You are a reviewer.", s.personality());
    }

    @Test
    void constructsWithSelection() {
        var scope = new SelectionScope(DocumentSide.A, 0, 0, "selected text");
        var s = new ReviewSession(
                CHANNEL_ID, "sid", "cname", "iid", "docA", "docB",
                scope, "persona"
        );
        assertNotNull(s.selection());
        assertEquals(DocumentSide.A, s.selection().side());
        assertEquals("selected text", s.selection().selectedText());
    }

    @Test
    void nullSelectionIsValid() {
        var s = new ReviewSession(CHANNEL_ID, "s", "cn", "i", "a", "b", null, "p");
        assertNull(s.selection());
    }

    @Test
    void equalityByValue() {
        assertEquals(minimal(), minimal());
    }

    @Test
    void withSelectionReturnsNewRecord() {
        var original = minimal();
        var scope = new SelectionScope(DocumentSide.B, 0, 0, "some text");
        var updated = original.withSelection(scope);
        assertNull(original.selection());
        assertEquals(DocumentSide.B, updated.selection().side());
        assertEquals("some text", updated.selection().selectedText());
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
        var scope = new SelectionScope(DocumentSide.A, 0, 0, "text");
        var withSel = new ReviewSession(
                CHANNEL_ID, "s", "cn", "i", "a", "b", scope, "p"
        );
        var cleared = withSel.withSelection(null);
        assertNull(cleared.selection());
    }
}
