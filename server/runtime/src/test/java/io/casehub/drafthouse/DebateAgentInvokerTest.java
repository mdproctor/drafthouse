package io.casehub.drafthouse;

import io.casehub.agency.agentic.AgentRef;
import io.casehub.agency.agentic.AgentResult;
import io.casehub.agency.channel.AgentTask;
import io.casehub.agency.conversation.orchestration.AgentParticipant;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DebateAgentInvokerTest {

    private static AgentRef testRef(String name) {
        return AgentRef.external(name, ignored ->
                CompletableFuture.completedFuture(AgentResult.success(null, "")));
    }

    @Test
    void invoke_delegatesToProvider_returnsSuccess() {
        var ref = testRef("test-rev");
        DebateAgentProvider provider = task -> "LLM response for: " + task.assembledInput();
        var participants = List.of(new AgentParticipant(ref, "REV", "You are a reviewer."));
        var invoker = new DebateAgentInvoker(provider, participants);

        AgentResult result = invoker.invoke(ref, "Document content here").await().indefinitely();

        assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.SUCCESS);
        assertThat(result.output()).asString().contains("LLM response for: Document content here");
        assertThat(result.agent()).isSameAs(ref);
        assertThat(result.duration()).isNotNull();
    }

    @Test
    void invoke_passesSystemPromptToAgentTask() {
        var ref = testRef("test-imp");
        DebateAgentProvider provider = task -> "system=" + task.systemPrompt() + "|input=" + task.assembledInput();
        var participants = List.of(new AgentParticipant(ref, "IMP", "You are an implementer."));
        var invoker = new DebateAgentInvoker(provider, participants);

        AgentResult result = invoker.invoke(ref, "Review this").await().indefinitely();

        assertThat(result.output()).asString()
                .contains("system=You are an implementer.")
                .contains("input=Review this");
    }

    @Test
    void invoke_providerThrows_returnsFailure() {
        var ref = testRef("test-rev");
        DebateAgentProvider provider = task -> { throw new RuntimeException("LLM unavailable"); };
        var participants = List.of(new AgentParticipant(ref, "REV", "system prompt"));
        var invoker = new DebateAgentInvoker(provider, participants);

        AgentResult result = invoker.invoke(ref, "prompt").await().indefinitely();

        assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.FAILURE);
        assertThat(result.output()).asString().contains("LLM unavailable");
    }

    @Test
    void invoke_unknownAgent_usesEmptySystemPrompt() {
        var ref = testRef("unknown-agent");
        DebateAgentProvider provider = task -> "system=" + task.systemPrompt();
        var invoker = new DebateAgentInvoker(provider, List.of());

        AgentResult result = invoker.invoke(ref, "prompt").await().indefinitely();

        assertThat(result.status()).isEqualTo(AgentResult.AgentResultStatus.SUCCESS);
        assertThat(result.output()).asString().isEqualTo("system=");
    }
}
