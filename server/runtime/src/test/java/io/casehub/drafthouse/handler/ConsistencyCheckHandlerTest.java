package io.casehub.drafthouse.handler;

import io.casehub.agency.communication.dispatch.AgentTask;
import io.casehub.agency.communication.dispatch.ChannelAgentRequest;
import io.casehub.agency.deliberation.conversation.ConversationPoint;
import io.casehub.agency.deliberation.conversation.ConversationState;
import io.casehub.agency.deliberation.conversation.PointClassification;
import io.casehub.agency.deliberation.conversation.Priority;
import io.casehub.agency.deliberation.conversation.ThreadEntry;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
                + "entryType=SUB_TASK_REQUEST|role=IMP|taskType=CONSISTENCY_CHECK|subTaskId=sub-1\n\n"
                + (body != null ? body : "");
        lenient().when(outboundMessage.content()).thenReturn(content);
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
        return new ChannelAgentRequest(channelId, "sub-1", outboundMessage, null);
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
                new ThreadEntry("pt-a", null, null, null, null, "REV", 1, "RAISE", "Agreed point content."),
                new ThreadEntry(null, null, null, null, null, "IMP", 2, "AGREE", "Agreed.")
                                  );
        var openThread = List.of(
                new ThreadEntry("pt-b", null, null, null, null, "IMP", 1, "RAISE", "Open point content.")
                                );
        var state = new ConversationState(
                Map.of(
                        "pt-a", new ConversationPoint("pt-a", null, new PointClassification(Priority.HIGH, "ISOLATED", null), agreedThread, "AGREED"),
                        "pt-b", new ConversationPoint("pt-b", null, new PointClassification(Priority.MEDIUM, "ISOLATED", null), openThread, "OPEN")
                      ),
                List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));

        AgentTask task = handler.prepareTask(requestWithBody("Some resolution."));
        assertThat(task.assembledInput()).contains("Agreed point content.");
        assertThat(task.assembledInput()).doesNotContain("Open point content.");
    }

    @Test
    void no_agreed_points_uses_sentinel() {
        var state = new ConversationState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        AgentTask task = handler.prepareTask(requestWithBody("Some resolution."));
        assertThat(task.assembledInput()).contains("(no agreed points yet)");
    }
}
