package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResultTest {

    @Test
    void responseCarriesContent() {
        var result = new ReviewResult(false, "Looks good.");
        assertFalse(result.declined());
        assertEquals("Looks good.", result.content());
    }

    @Test
    void declineFactorySetsDeclinesTrue() {
        var result = ReviewResult.decline("Out of scope.");
        assertTrue(result.declined());
        assertEquals("Out of scope.", result.content());
    }

    @Test
    void nullContentRejected() {
        assertThrows(NullPointerException.class, () -> new ReviewResult(false, null));
        assertThrows(NullPointerException.class, () -> ReviewResult.decline(null));
    }

    @Test
    void equalityByValue() {
        assertEquals(ReviewResult.decline("x"), ReviewResult.decline("x"));
        assertNotEquals(ReviewResult.decline("x"), ReviewResult.decline("y"));
    }
}
