package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
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
class ConsistencyCheckHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    ConsistencyCheckHandler handler;
    UUID channelId = UUID.randomUUID();

    private void setField(String name, Object value) throws Exception {
        var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(handler, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        handler = new ConsistencyCheckHandler();
        setField("projectionService", projectionService);
        setField("debateProjection", debateProjection);
        setField("registry", registry);
        setField("messageService", messageService);
    }

    private ChannelAgentRequest requestWithBody(String body) {
        String content = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|agent=IMP|taskType=CONSISTENCY_CHECK|subTaskId=sub-1\n\n"
                + (body != null ? body : "");
        lenient().when(outboundMessage.content()).thenReturn(content);
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
        return new ChannelAgentRequest(channelId, "sub-1", outboundMessage);
    }

    @Test
    void blank_body_throws_with_message_mentioning_resolution_text_not_customInput() {
        assertThatThrownBy(() -> handler.prepareTask(requestWithBody("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONSISTENCY_CHECK")
                .hasMessageContaining("proposed resolution text")
                .hasMessageNotContaining("customInput");
    }

    @Test
    void only_agreed_points_included_open_excluded() {
        var agreedThread = List.of(
                new ThreadEntry("pt-a", AgentType.REV, 1, EntryType.RAISE, "Agreed point content."),
                new ThreadEntry(null, AgentType.IMP, 2, EntryType.AGREE, "Agreed.")
        );
        var openThread = List.of(
                new ThreadEntry("pt-b", AgentType.IMP, 1, EntryType.RAISE, "Open point content.")
        );
        var state = new ReviewState(
                Map.of(
                    "pt-a", new ReviewPoint("pt-a", new PointClassification(Priority.P1, Scope.ISOLATED, null), agreedThread, ReviewStatus.AGREED),
                    "pt-b", new ReviewPoint("pt-b", new PointClassification(Priority.P2, Scope.ISOLATED, null), openThread, ReviewStatus.OPEN)
                ),
                List.of(), List.of(), Map.of()
        );
        when(projectionService.project(eq(channelId), any())).thenReturn(new ProjectionResult<>(state, null));
        AgentTask task = handler.prepareTask(requestWithBody("Proposed resolution text."));
        assertThat(task.assembledInput()).contains("Agreed point content.");
        assertThat(task.assembledInput()).doesNotContain("Open point content.");
        assertThat(task.assembledInput()).contains("Proposed resolution text.");
    }

    @Test
    void no_agreed_points_uses_sentinel() {
        var state = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        AgentTask task = handler.prepareTask(requestWithBody("Some resolution."));
        assertThat(task.assembledInput()).contains("(no agreed points yet)");
    }
}
