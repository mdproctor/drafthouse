package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.conversation.ConversationState;
import io.casehub.blocks.conversation.ConversationPoint;
import io.casehub.blocks.conversation.ThreadEntry;
import io.casehub.blocks.conversation.PointClassification;
import io.casehub.blocks.conversation.Priority;
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
class NeutralSummaryHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    NeutralSummaryHandler handler;
    UUID channelId = UUID.randomUUID();

    private void setField(String name, Object value) throws Exception {
        var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(handler, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        handler = new NeutralSummaryHandler();
        setField("projectionService", projectionService);
        setField("debateProjection", debateProjection);
        setField("registry", registry);
        setField("messageService", messageService);
        lenient().when(outboundMessage.content()).thenReturn(DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|role=REV|taskType=NEUTRAL_SUMMARY|subTaskId=sub-1\n\n");
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
    }

    @Test
    void empty_state_does_not_throw_uses_sentinel() {
        var state = new ConversationState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("(no debate entries)");
    }

    @Test
    void points_appear_in_assembled_input() {
        var thread = List.of(
                new ThreadEntry("pt-1", "REV", 1, "RAISE", "The raise content.")
        );
        var point = new ConversationPoint("pt-1",
                new PointClassification(Priority.HIGH, "ISOLATED", null), thread, "OPEN");
        var state = new ConversationState(Map.of("pt-1", point), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("pt-1");
        assertThat(task.assembledInput()).contains("The raise content.");
    }

    @Test
    void multi_entry_thread_all_entries_appear_in_assembled_input() {
        var thread = List.of(
                new ThreadEntry("pt-1", "REV", 1, "RAISE", "The concern."),
                new ThreadEntry(null,   "IMP", 2, "DISPUTE", "I disagree because...")
        );
        var point = new ConversationPoint("pt-1",
                new PointClassification(Priority.MEDIUM, "SYSTEMIC", null), thread, "DISPUTED");
        var state = new ConversationState(Map.of("pt-1", point), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        AgentTask task = handler.prepareTask(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        assertThat(task.assembledInput()).contains("The concern.");
        assertThat(task.assembledInput()).contains("I disagree because...");
        assertThat(task.assembledInput()).contains("RAISE");
        assertThat(task.assembledInput()).contains("DISPUTE");
    }
}
