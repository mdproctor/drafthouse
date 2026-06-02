package io.casehub.drafthouse.debate.claude;

import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.casehub.drafthouse.debate.DebateEntry;
import io.casehub.drafthouse.debate.DebateRoundContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;

/**
 * Stub implementation. Will use claude-agent-sdk-java once the Maven coordinate is
 * verified — see casehubio/platform#55 and casehubio/drafthouse#29.
 *
 * <p>Activates only when this module is added as a compile dependency to server/runtime/pom.xml.
 * Off by default.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class ClaudeAgentSdkDebateAgentProvider implements DebateAgentProvider {

    // TODO: implement using claude-agent-sdk-java once coordinate verified (platform#55)

    @Override
    public List<DebateEntry> executeReviewerRound(DebateRoundContext context) {
        throw new UnsupportedOperationException(
                "Claude Agent SDK implementation pending — see casehubio/platform#55");
    }

    @Override
    public List<DebateEntry> executeImplementerRound(DebateRoundContext context) {
        throw new UnsupportedOperationException(
                "Claude Agent SDK implementation pending — see casehubio/platform#55");
    }
}
