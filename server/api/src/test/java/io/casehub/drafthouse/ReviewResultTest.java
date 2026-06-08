package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import static io.casehub.drafthouse.ReviewResult.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ReviewResultTest {

    @Test
    void agree_outcome_isAgree_notDeclineNotQualify() {
        var r = ReviewResult.agree("Looks good.");
        assertThat(r.outcome()).isEqualTo(AGREE);
        assertThat(r.content()).isEqualTo("Looks good.");
    }

    @Test
    void qualify_outcome_isQualify() {
        var r = ReviewResult.qualify("Still in dialogue.");
        assertThat(r.outcome()).isEqualTo(QUALIFY);
        assertThat(r.content()).isEqualTo("Still in dialogue.");
    }

    @Test
    void decline_outcome_isDecline() {
        var r = ReviewResult.decline("Out of scope.");
        assertThat(r.outcome()).isEqualTo(DECLINE);
        assertThat(r.content()).isEqualTo("Out of scope.");
    }

    @Test
    void nullContentRejected() {
        assertThatNullPointerException().isThrownBy(() -> ReviewResult.agree(null));
        assertThatNullPointerException().isThrownBy(() -> ReviewResult.qualify(null));
        assertThatNullPointerException().isThrownBy(() -> ReviewResult.decline(null));
    }

    @Test
    void nullOutcomeRejected() {
        assertThatNullPointerException().isThrownBy(() -> new ReviewResult(null, "content"));
    }

    @Test
    void equalityByValue() {
        assertThat(ReviewResult.decline("x")).isEqualTo(ReviewResult.decline("x"));
        assertThat(ReviewResult.decline("x")).isNotEqualTo(ReviewResult.decline("y"));
        assertThat(ReviewResult.agree("x")).isNotEqualTo(ReviewResult.qualify("x"));
    }
}
