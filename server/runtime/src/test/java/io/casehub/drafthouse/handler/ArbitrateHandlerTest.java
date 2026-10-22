package io.casehub.drafthouse.handler;

import io.casehub.agency.communication.dispatch.AgentTask;
import io.casehub.agency.communication.dispatch.ChannelAgentRequest;
import io.casehub.agency.deliberation.conversation.ConversationPoint;
import io.casehub.agency.deliberation.conversation.ConversationState;
import io.casehub.agency.deliberation.conversation.PointClassification;
import io.casehub.agency.deliberation.conversation.Priority;
import io.casehub.agency.deliberation.conversation.ThreadEntry;
import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.DebateSessionRegistry;
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArbitrateHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    ArbitrateHandler handler;
    UUID channelId = UUID.randomUUID();

    private void setField(String name, Object value) throws Exception {
        var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(handler, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        handler = new ArbitrateHandler();
        setField("projectionService", projectionService);
        setField("debateProjection", debateProjection);
        setField("registry", registry);
        setField("messageService", messageService);
        lenient().when(outboundMessage.content()).thenReturn(DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|role=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1\n\n");
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
        lenient().when(registry.find(channelId)).thenReturn(Optional.of(
                new DebateSession(channelId, channelId.toString(), "ch", null)));
    }

    private ConversationState stateWith(List<ThreadEntry> thread) {
        var point = new ConversationPoint("pt-1", null,
                new PointClassification(Priority.HIGH, "ISOLATED", null), thread, "DISPUTED");
        return new ConversationState(Map.of("pt-1", point), List.of(), List.of(), Map.of());
    }

    @Test
    void uses_last_dispute_qualify_counter_not_thread_last() {
        var thread = List.of(
                new ThreadEntry("pt-1", null, null, null, null, "REV", 1, "RAISE", "The raise."),
                new ThreadEntry(null, null, null, null, null, "IMP", 2, "QUALIFY", "The qualify."),
                new ThreadEntry(null, null, null, null, null, "REV", 3, "FLAG_HUMAN", "Flag!")
                            );
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage, null));
        assertThat(task.assembledInput()).contains("The qualify.");
        assertThat(task.assembledInput()).doesNotContain("Flag!");
    }

    @Test
    void uses_last_of_multiple_responses() {
        var thread = List.of(
                new ThreadEntry("pt-1", null, null, null, null, "REV", 1, "RAISE", "The raise."),
                new ThreadEntry(null, null, null, null, null, "IMP", 2, "DISPUTE", "Dispute."),
                new ThreadEntry(null, null, null, null, null, "REV", 3, "COUNTER", "Counter."),
                new ThreadEntry(null, null, null, null, null, "IMP", 4, "QUALIFY", "Qualify.")
                            );
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage, null));
        assertThat(task.assembledInput()).contains("Qualify.");
        assertThat(task.assembledInput()).doesNotContain("Counter.");
        assertThat(task.assembledInput()).doesNotContain("Dispute.");
    }

    @Test
    void no_response_yet_uses_sentinel() {
        var thread = List.of(new ThreadEntry("pt-1", null, null, null, null, "REV", 1, "RAISE", "The raise."));
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage, null));
        assertThat(task.assembledInput()).contains("(no response yet)");
    }
}
