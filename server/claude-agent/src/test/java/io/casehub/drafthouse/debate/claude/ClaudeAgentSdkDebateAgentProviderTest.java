package io.casehub.drafthouse.debate.claude;

import io.casehub.drafthouse.debate.*;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class ClaudeAgentSdkDebateAgentProviderTest {

    @Test
    void stubThrowsUnsupportedOperation() {
        var provider = new ClaudeAgentSdkDebateAgentProvider();
        var ctx = new DebateRoundContext("spec", "debate",
                new ReviewState(new LinkedHashMap<>(), new ArrayList<>()), 1, "s1");
        assertThatThrownBy(() -> provider.executeReviewerRound(ctx))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("platform#55");
    }
}
