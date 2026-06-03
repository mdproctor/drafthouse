package io.casehub.drafthouse;

import java.util.Objects;

/**
 * Result returned by DocumentReviewer.review().
 *
 * declined=false → dispatch RESPONSE on the review channel.
 * declined=true  → dispatch DECLINE on the review channel (covers both out-of-scope
 *                  queries and LLM errors — FAILURE is reserved for COMMAND obligations only,
 *                  per Qhorus ADR-0005 speech-act taxonomy).
 */
public record ReviewResult(boolean declined, String content) {

    public ReviewResult {
        Objects.requireNonNull(content, "content must not be null");
    }

    /** Convenience factory — both out-of-scope and error paths produce a DECLINE. */
    public static ReviewResult decline(final String reason) {
        return new ReviewResult(true, reason);
    }
}
