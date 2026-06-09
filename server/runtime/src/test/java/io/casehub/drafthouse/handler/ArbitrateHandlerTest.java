package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.*;
import io.casehub.drafthouse.debate.*;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
                + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1|pointId=pt-1\n\n");
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
        lenient().when(registry.find(channelId)).thenReturn(Optional.of(new DebateSession(
                channelId, channelId.toString(), "ch", "rev", "imp", null)));
    }

    private ReviewState stateWith(List<ThreadEntry> thread) {
        var point = new ReviewPoint("pt-1",
                new PointClassification(Priority.P1, Scope.ISOLATED, null), thread, ReviewStatus.DISPUTED);
        return new ReviewState(Map.of("pt-1", point), List.of(), List.of(), Map.of());
    }

    @Test
    void uses_last_dispute_qualify_counter_not_thread_last() {
        var thread = List.of(
                new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The raise."),
                new ThreadEntry(null, AgentType.IMP, 2, EntryType.QUALIFY, "The qualify."),
                new ThreadEntry(null, AgentType.REV, 3, EntryType.FLAG_HUMAN, "Flag!")
        );
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("The qualify.");
        assertThat(task.assembledInput()).doesNotContain("Flag!");
    }

    @Test
    void uses_last_of_multiple_responses() {
        var thread = List.of(
                new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The raise."),
                new ThreadEntry(null, AgentType.IMP, 2, EntryType.DISPUTE, "Dispute."),
                new ThreadEntry(null, AgentType.REV, 3, EntryType.COUNTER, "Counter."),
                new ThreadEntry(null, AgentType.IMP, 4, EntryType.QUALIFY, "Qualify.")
        );
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("Qualify.");
        assertThat(task.assembledInput()).doesNotContain("Counter.");
        assertThat(task.assembledInput()).doesNotContain("Dispute.");
    }

    @Test
    void no_response_yet_uses_sentinel() {
        var thread = List.of(new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "The raise."));
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(stateWith(thread), null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("(no response yet)");
    }
}
