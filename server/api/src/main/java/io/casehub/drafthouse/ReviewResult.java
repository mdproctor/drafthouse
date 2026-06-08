package io.casehub.drafthouse;

import java.util.Objects;

/**
 * Result returned by DocumentReviewer.review().
 *
 * AGREE   → dispatch DONE on the review channel (point resolved, discussion concludes).
 * QUALIFY → dispatch RESPONSE on the review channel (reviewer qualifies position, discussion continues).
 * DECLINE → dispatch DECLINE on the review channel (out-of-scope or LLM error — FAILURE is reserved
 *            for COMMAND obligations only per Qhorus ADR-0005 speech-act taxonomy).
 */
public record ReviewResult(Outcome outcome, String content) {

    public enum Outcome { AGREE, QUALIFY, DECLINE }

    public ReviewResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public static ReviewResult agree(final String content) {
        return new ReviewResult(Outcome.AGREE, content);
    }

    public static ReviewResult qualify(final String content) {
        return new ReviewResult(Outcome.QUALIFY, content);
    }

    public static ReviewResult decline(final String reason) {
        return new ReviewResult(Outcome.DECLINE, reason);
    }
}
