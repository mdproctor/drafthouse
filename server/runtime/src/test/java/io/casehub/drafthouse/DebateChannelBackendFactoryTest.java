package io.casehub.drafthouse;

import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;

class DebateChannelBackendFactoryTest {

    private ChannelGateway gateway;
    private DebateChannelBackend debateBackend;
    private DebateChannelBackendFactory debateFactory;
    private ReviewerChannelBackendFactory reviewerFactory;
    private ReviewSessionRegistry reviewRegistry;
    private DebateSessionRegistry debateRegistry;
    @SuppressWarnings("unchecked")
    private Event<ChannelAgentRequest> channelAgentEvent = mock(Event.class);

    @BeforeEach
    void setUp() {
        gateway = mock(ChannelGateway.class);
        debateRegistry = mock(DebateSessionRegistry.class);
        debateBackend = new DebateChannelBackend(channelAgentEvent, debateRegistry);

        debateFactory = new DebateChannelBackendFactory();
        debateFactory.gateway = gateway;
        debateFactory.debateBackend = debateBackend;

        reviewRegistry = mock(ReviewSessionRegistry.class);
        reviewerFactory = new ReviewerChannelBackendFactory();
        reviewerFactory.gateway = gateway;
        reviewerFactory.registry = reviewRegistry;
        // other ReviewerChannelBackendFactory fields left null — factory returns before using them when debate channel guard fires
    }

    @Test
    void debateChannel_registersDebateBackend_notReviewerBackend() {
        UUID channelId = UUID.randomUUID();
        ChannelInitialisedEvent event = new ChannelInitialisedEvent(channelId, "drafthouse/debate/d-abc123", false);

        debateFactory.onChannelInitialised(event);
        reviewerFactory.onChannelInitialised(event);

        verify(gateway).deregisterBackend(channelId, DebateChannelBackend.BACKEND_ID);
        verify(gateway).registerBackend(channelId, debateBackend, DebateChannelBackend.BACKEND_TYPE);
        // ReviewerChannelBackendFactory returns early — registry.find() must not have been called
        verifyNoInteractions(reviewRegistry);
    }

    @Test
    void reviewChannel_doesNotRegisterDebateBackend() {
        UUID channelId = UUID.randomUUID();
        String channelName = "drafthouse/r-" + UUID.randomUUID();
        ChannelInitialisedEvent event = new ChannelInitialisedEvent(channelId, channelName, false);

        // ReviewerChannelBackendFactory will call registry.find() — no session → returns early
        when(reviewRegistry.find(channelId)).thenReturn(Optional.empty());

        debateFactory.onChannelInitialised(event);
        reviewerFactory.onChannelInitialised(event);

        // DebateChannelBackendFactory should not register for non-debate channels
        verify(gateway, never()).registerBackend(eq(channelId), eq(debateBackend), anyString());
    }

    // --- DebateChannelBackend.post() dispatch tests ---

    @Test
    void subTaskRequest_withActiveSession_firesChannelAgentRequest() {
        UUID channelId = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);
        UUID correlationId = UUID.randomUUID();
        OutboundMessage message = subTaskRequestMessage(correlationId);

        DebateSession session = new DebateSession(
                channelId, channelId.toString(), "drafthouse/debate/d-" + channelId,
                "drafthouse-rev-" + channelId, "drafthouse-imp-" + channelId, null);
        when(debateRegistry.find(channelId)).thenReturn(Optional.of(session));
        when(channelAgentEvent.fireAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

        debateBackend.post(channelRef, message);

        ArgumentCaptor<ChannelAgentRequest> captor = ArgumentCaptor.forClass(ChannelAgentRequest.class);
        verify(channelAgentEvent).fireAsync(captor.capture());
        assertThat(captor.getValue().channelId()).isEqualTo(channelId);
        assertThat(captor.getValue().correlationId()).isEqualTo(correlationId.toString());
        assertThat(captor.getValue().message()).isSameAs(message);
    }

    @Test
    void nonSubTaskRequest_doesNotFireEvent() {
        UUID channelId = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);
        // NEUTRAL_SUMMARY is a different entryType — should not trigger dispatch
        OutboundMessage message = new OutboundMessage(
                UUID.randomUUID(), "drafthouse-subagent", MessageType.STATUS,
                DebateProtocol.META_SENTINEL + "entryType=NEUTRAL_SUMMARY|agent=REV\n\nSummary text",
                UUID.randomUUID(), null, io.casehub.platform.api.identity.ActorType.AGENT);

        debateBackend.post(channelRef, message);

        verifyNoInteractions(channelAgentEvent);
        verifyNoInteractions(debateRegistry);
    }

    @Test
    void subTaskRequest_withNoActiveSession_dropsEventAndDoesNotFire() {
        UUID channelId = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);
        OutboundMessage message = subTaskRequestMessage(UUID.randomUUID());

        when(debateRegistry.find(channelId)).thenReturn(Optional.empty());

        debateBackend.post(channelRef, message);

        verifyNoInteractions(channelAgentEvent);
    }

    private OutboundMessage subTaskRequestMessage(UUID correlationId) {
        String content = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|agent=REV|taskType=ARBITRATE|subTaskId=sub-1\n\n";
        return new OutboundMessage(
                UUID.randomUUID(), "drafthouse-orchestrator", MessageType.STATUS,
                content, correlationId, null, io.casehub.platform.api.identity.ActorType.AGENT);
    }
}
