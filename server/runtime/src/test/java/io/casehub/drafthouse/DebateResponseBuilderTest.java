package io.casehub.drafthouse;

import io.casehub.agency.core.AgentRef;
import io.casehub.agency.core.AgentResult;
import io.casehub.agency.deliberation.conversation.ConversationState;
import io.casehub.agency.deliberation.conversation.orchestration.AgentParticipant;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.platform.api.identity.ActorType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DebateResponseBuilderTest {

    private static final ConversationState EMPTY_STATE =
            new ConversationState(Map.of(), List.of(), List.of(), Map.of());

    private static AgentRef testRef(String name) {
        return AgentRef.external(name, ignored ->
                CompletableFuture.completedFuture(AgentResult.success(null, "")));
    }

    @Test
    void build_encodesResponseWithMetaSentinel() {
        var builder = new DebateResponseBuilder();
        var ref = testRef("rev-agent");
        var agent = new AgentParticipant(ref, "REV", "system prompt");
        var result = AgentResult.success(ref, "I raise concern about section 3.");

        var message = builder.build(agent, result, EMPTY_STATE);

        assertThat(message.content()).startsWith(DebateProtocol.META_SENTINEL);
        assertThat(message.sender()).isEqualTo("rev-agent");
        assertThat(message.actorType()).isEqualTo(ActorType.AGENT);
    }

    @Test
    void build_includesRoleInMeta() {
        var builder = new DebateResponseBuilder();
        var ref = testRef("imp-agent");
        var agent = new AgentParticipant(ref, "IMP", "system prompt");
        var result = AgentResult.success(ref, "Counter-argument.");

        var message = builder.build(agent, result, EMPTY_STATE);

        var meta = DebateProtocol.parseMeta(message.content());
        assertThat(meta.get("role")).isEqualTo("IMP");
    }

    @Test
    void build_includesEntryTypeInMeta() {
        var builder = new DebateResponseBuilder();
        var ref = testRef("rev-agent");
        var agent = new AgentParticipant(ref, "REV", "system prompt");
        var result = AgentResult.success(ref, "Some review point.");

        var message = builder.build(agent, result, EMPTY_STATE);

        var meta = DebateProtocol.parseMeta(message.content());
        assertThat(meta.get("entryType")).isNotNull();
    }

    @Test
    void build_includesRoundInMeta() {
        var builder = new DebateResponseBuilder();
        var ref = testRef("rev-agent");
        var agent = new AgentParticipant(ref, "REV", "system prompt");
        var result = AgentResult.success(ref, "Point raised.");

        var message = builder.build(agent, result, EMPTY_STATE);

        var meta = DebateProtocol.parseMeta(message.content());
        assertThat(meta).containsKey("round");
    }

    @Test
    void build_preservesBodyContent() {
        var builder = new DebateResponseBuilder();
        var ref = testRef("rev-agent");
        var agent = new AgentParticipant(ref, "REV", "system prompt");
        var result = AgentResult.success(ref, "The API contract is ambiguous in section 3.");

        var message = builder.build(agent, result, EMPTY_STATE);

        String body = DebateProtocol.bodyContent(message.content());
        assertThat(body).isEqualTo("The API contract is ambiguous in section 3.");
    }

    @Test
    void build_setsCorrelationId() {
        var builder = new DebateResponseBuilder();
        var ref = testRef("rev-agent");
        var agent = new AgentParticipant(ref, "REV", "system prompt");
        var result = AgentResult.success(ref, "A point.");

        var message = builder.build(agent, result, EMPTY_STATE);

        assertThat(message.correlationId()).isNotNull().isNotBlank();
    }
}
