package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.drafthouse.*;
import io.casehub.drafthouse.debate.*;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    CustomHandler handler;
    UUID channelId = UUID.randomUUID();

    private void setField(String name, Object value) throws Exception {
        var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(handler, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        handler = new CustomHandler();
        setField("projectionService", projectionService);
        setField("debateProjection", debateProjection);
        setField("registry", registry);
        setField("messageService", messageService);
    }

    private ChannelAgentRequest requestWithBody(String body) {
        String content = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|role=REV|taskType=CUSTOM|subTaskId=sub-1\n\n"
                + (body != null ? body : "");
        lenient().when(outboundMessage.content()).thenReturn(content);
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
        return new ChannelAgentRequest(channelId, "sub-1", outboundMessage, null);
    }

    @Test
    void blank_body_throws_with_message() {
        assertThatThrownBy(() -> handler.prepareTask(requestWithBody("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CUSTOM");
    }

    @Test
    void body_is_verbatim_assembled_input() {
        AgentTask task = handler.prepareTask(requestWithBody("My custom analysis question."));
        assertThat(task.assembledInput()).isEqualTo("My custom analysis question.");
    }
}
