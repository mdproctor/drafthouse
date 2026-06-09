package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelAgentDispatcherTest {

    @Mock DebateAgentProvider debateAgentProvider;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    ChannelAgentHandler matchingHandler = new ChannelAgentHandler() {
        public boolean handles(ChannelAgentRequest r) { return true; }
        public AgentTask prepareTask(ChannelAgentRequest r) { return new AgentTask("system", "user"); }
        public MessageDispatch buildResponse(UUID channelId, String senderId,
                                             String llmOutput, ChannelAgentRequest trigger) {
            return MessageDispatch.builder()
                    .channelId(channelId).sender(senderId)
                    .type(MessageType.STATUS).content("FINDING: " + llmOutput)
                    .correlationId(trigger.correlationId())
                    .actorType(io.casehub.platform.api.identity.ActorType.AGENT)
                    .build();
        }
    };

    ChannelAgentDispatcher dispatcher;
    UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(outboundMessage.content()).thenReturn(
                io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1\n\n");
        when(outboundMessage.correlationId()).thenReturn(null);
        when(debateAgentProvider.analyse(any())).thenReturn("LLM finding text.");
        when(messageService.findByCorrelationId(any())).thenReturn(java.util.Optional.empty());
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                java.util.List.of(matchingHandler));
    }

    @Test
    void handler_found_dispatches_finding() {
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        verify(messageService).dispatch(argThat(d -> d.content().contains("FINDING:")));
    }

    @Test
    void provider_throws_dispatches_sanitized_error() {
        when(debateAgentProvider.analyse(any())).thenThrow(new RuntimeException("timeout"));
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(cap.getValue().content()).contains("Sub-agent analysis failed.");
        assertThat(cap.getValue().content()).doesNotContain("timeout");
    }

    @Test
    void parse_exception_dispatches_distinct_error() {
        ChannelAgentHandler throwingHandler = new ChannelAgentHandler() {
            public boolean handles(ChannelAgentRequest r) { return true; }
            public AgentTask prepareTask(ChannelAgentRequest r) { return new AgentTask("s", "u"); }
            public MessageDispatch buildResponse(UUID c, String s, String o, ChannelAgentRequest t)
                    throws AgentResultParseException {
                throw new AgentResultParseException("bad format");
            }
        };
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                java.util.List.of(throwingHandler));
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().content()).contains("Sub-agent returned an unreadable result.");
    }

    @Test
    void no_handler_dispatches_error() {
        ChannelAgentHandler noMatch = new ChannelAgentHandler() {
            public boolean handles(ChannelAgentRequest r) { return false; }
            public AgentTask prepareTask(ChannelAgentRequest r) { throw new UnsupportedOperationException(); }
            public MessageDispatch buildResponse(UUID c, String s, String o, ChannelAgentRequest t) { throw new UnsupportedOperationException(); }
        };
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                java.util.List.of(noMatch));
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        verify(messageService).dispatch(any());
        verify(debateAgentProvider, never()).analyse(any());
    }
}
