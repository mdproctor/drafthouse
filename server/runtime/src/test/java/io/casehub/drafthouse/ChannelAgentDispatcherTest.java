package io.casehub.drafthouse;

import io.casehub.blocks.channel.AgentResultParseException;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.channel.ChannelAgentHandler;
import io.casehub.blocks.channel.ChannelAgentRequest;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
        lenient().when(outboundMessage.content()).thenReturn(
                io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1\n\n");
        lenient().when(messageService.findByCorrelationId(any())).thenReturn(java.util.Optional.empty());
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                List.of(matchingHandler), null);
    }

    @Test
    void handler_found_dispatches_finding() {
        when(debateAgentProvider.analyse(any())).thenReturn("LLM finding text.");
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
        when(debateAgentProvider.analyse(any())).thenReturn("raw output");
        ChannelAgentHandler throwingHandler = new ChannelAgentHandler() {
            public boolean handles(ChannelAgentRequest r) { return true; }
            public AgentTask prepareTask(ChannelAgentRequest r) { return new AgentTask("s", "u"); }
            public MessageDispatch buildResponse(UUID c, String s, String o, ChannelAgentRequest t) {
                throw new AgentResultParseException("bad format");
            }
        };
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                List.of(throwingHandler), null);
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().content()).contains("Sub-agent returned an unreadable result.");
        assertThat(cap.getValue().content()).doesNotContain("bad format");
    }

    @Test
    void no_handler_dispatches_error_with_correct_content() {
        ChannelAgentHandler noMatch = new ChannelAgentHandler() {
            public boolean handles(ChannelAgentRequest r) { return false; }
            public AgentTask prepareTask(ChannelAgentRequest r) { throw new UnsupportedOperationException(); }
            public MessageDispatch buildResponse(UUID c, String s, String o, ChannelAgentRequest t) { throw new UnsupportedOperationException(); }
        };
        dispatcher = new ChannelAgentDispatcher(debateAgentProvider, messageService,
                List.of(noMatch), null);
        dispatcher.onChannelAgentRequest(new ChannelAgentRequest(channelId, "sub-1", outboundMessage));
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.STATUS);
        assertThat(cap.getValue().content()).contains("No handler matched this sub-task request.");
        verify(debateAgentProvider, never()).analyse(any());
    }
}
