package io.casehub.drafthouse;

import io.casehub.agency.channel.ChannelAgentRequest;
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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebateChannelBackendFactoryTest {

    private ChannelGateway gateway;
    private DebateChannelBackend debateBackend;
    private DebateChannelBackendFactory debateFactory;
    private ReviewerChannelBackendFactory reviewerFactory;
    private ReviewSessionRegistry reviewRegistry;
    private DebateSessionRegistry debateRegistry;
    @SuppressWarnings("unchecked")
    private Event<ChannelAgentRequest> channelAgentEvent = mock(Event.class);
    @SuppressWarnings("unchecked")
    private WebSocketEventBus eventBus = mock(WebSocketEventBus.class);

    @BeforeEach
    void setUp() {
        gateway = mock(ChannelGateway.class);
        debateRegistry = mock(DebateSessionRegistry.class);
        debateBackend = new DebateChannelBackend(channelAgentEvent, debateRegistry, eventBus);

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
                channelId, channelId.toString(), "drafthouse/debate/d-" + channelId, (String) null);
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
                DebateProtocol.META_SENTINEL + "entryType=NEUTRAL_SUMMARY|role=REV\n\nSummary text",
                UUID.randomUUID().toString(), null, io.casehub.platform.api.identity.ActorType.AGENT, java.util.List.of(), null);

        debateBackend.post(channelRef, message);

        verifyNoInteractions(channelAgentEvent);
    }

    @Test
    void subTaskRequest_nullCorrelationId_fallsBackToGeneratedUuid() {
        UUID channelId = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);
        // correlationId is null — backend must generate a UUID fallback
        OutboundMessage message = subTaskRequestMessage(null);

        DebateSession session = new DebateSession(
                channelId, channelId.toString(), "drafthouse/debate/d-" + channelId, (String) null);
        when(debateRegistry.find(channelId)).thenReturn(Optional.of(session));
        when(channelAgentEvent.fireAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

        debateBackend.post(channelRef, message);

        ArgumentCaptor<ChannelAgentRequest> captor = ArgumentCaptor.forClass(ChannelAgentRequest.class);
        verify(channelAgentEvent).fireAsync(captor.capture());
        assertThat(captor.getValue().correlationId()).isNotNull();
        assertThat(captor.getValue().correlationId()).isNotEmpty();
        // Must be a valid UUID
        assertThat(java.util.UUID.fromString(captor.getValue().correlationId())).isNotNull();
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

    @Test
    void post_pushesAllMessageTypesToEventBus() {
        UUID channelId = UUID.randomUUID();
        ChannelRef ref = new ChannelRef(channelId, "debate-channel");
        OutboundMessage msg = new OutboundMessage(
                UUID.randomUUID(), "rev-agent", MessageType.QUERY,
                DebateProtocol.META_SENTINEL + "entryType=RAISE|role=REV|round=1|priority=HIGH\n\nTest content",
                null, null, io.casehub.platform.api.identity.ActorType.AGENT, java.util.List.of(), null);
        debateBackend.post(ref, msg);
        verify(eventBus).pushDebateEntries(eq(channelId), anyList());
    }

    private OutboundMessage subTaskRequestMessage(UUID correlationId) {
        String content = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|role=REV|taskType=ARBITRATE|subTaskId=sub-1\n\n";
        return new OutboundMessage(
                UUID.randomUUID(), "drafthouse-orchestrator", MessageType.STATUS,
                content, correlationId != null ? correlationId.toString() : null, null, io.casehub.platform.api.identity.ActorType.AGENT, java.util.List.of(), null);
    }

    @Test
    void autonomousSession_firstMessage_triggersConverseOnVirtualThread() throws Exception {
        UUID       channelId  = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);

        DebateSession session = new DebateSession(
                channelId, channelId.toString(), channelRef.name(), null);
        session.setAutonomous(true);

        var orchestrator = mock(io.casehub.agency.conversation.orchestration.ConversationOrchestrator.class);
        var outcome = new io.casehub.agency.conversation.orchestration.ConversationOutcome(
                null, new io.casehub.agency.agentic.termination.TerminationDecision.Complete("All agreed"),
                java.util.List.of(), 4, java.time.Duration.ofSeconds(10));
        when(orchestrator.converse(any())).thenReturn(
                io.smallrye.mutiny.Uni.createFrom().item(outcome));
        session.setOrchestrator(orchestrator);

        when(debateRegistry.find(channelId)).thenReturn(Optional.of(session));

        OutboundMessage message = new OutboundMessage(
                UUID.randomUUID(), "rev-agent", MessageType.QUERY,
                DebateProtocol.META_SENTINEL + "entryType=RAISE|role=REV|round=1|priority=P1\n\nTest point",
                UUID.randomUUID().toString(), null,
                io.casehub.platform.api.identity.ActorType.AGENT, java.util.List.of(), null);

        debateBackend.post(channelRef, message);

        Thread.sleep(500);

        verify(orchestrator).converse(any(io.casehub.qhorus.api.message.MessageView.class));
    }

    @Test
    void autonomousSession_secondMessage_doesNotRetrigger() throws Exception {
        UUID       channelId  = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);

        DebateSession session = new DebateSession(
                channelId, channelId.toString(), channelRef.name(), null);
        session.setAutonomous(true);

        var                                 orchestrator = mock(io.casehub.agency.conversation.orchestration.ConversationOrchestrator.class);
        java.util.concurrent.CountDownLatch latch        = new java.util.concurrent.CountDownLatch(1);
        when(orchestrator.converse(any())).thenReturn(
                io.smallrye.mutiny.Uni.createFrom().item(() -> {
                    try {latch.await();} catch (InterruptedException e) {Thread.currentThread().interrupt();}
                    return new io.casehub.agency.conversation.orchestration.ConversationOutcome(
                            null, new io.casehub.agency.agentic.termination.TerminationDecision.Complete("done"),
                            java.util.List.of(), 2, java.time.Duration.ofSeconds(5));
                }));
        session.setOrchestrator(orchestrator);

        when(debateRegistry.find(channelId)).thenReturn(Optional.of(session));

        OutboundMessage msg1 = new OutboundMessage(
                UUID.randomUUID(), "rev-agent", MessageType.QUERY,
                DebateProtocol.META_SENTINEL + "entryType=RAISE|role=REV|round=1|priority=P1\n\nFirst",
                UUID.randomUUID().toString(), null,
                io.casehub.platform.api.identity.ActorType.AGENT, java.util.List.of(), null);
        OutboundMessage msg2 = new OutboundMessage(
                UUID.randomUUID(), "imp-agent", MessageType.RESPONSE,
                DebateProtocol.META_SENTINEL + "entryType=COUNTER|role=IMP|round=1\n\nSecond",
                UUID.randomUUID().toString(), null,
                io.casehub.platform.api.identity.ActorType.AGENT, java.util.List.of(), null);

        debateBackend.post(channelRef, msg1);
        debateBackend.post(channelRef, msg2);

        latch.countDown();
        Thread.sleep(500);

        verify(orchestrator, times(1)).converse(any());
    }

    @Test
    void nonAutonomousSession_doesNotTriggerConverse() {
        UUID       channelId  = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);

        DebateSession session = new DebateSession(
                channelId, channelId.toString(), channelRef.name(), null);

        when(debateRegistry.find(channelId)).thenReturn(Optional.of(session));

        OutboundMessage message = new OutboundMessage(
                UUID.randomUUID(), "rev-agent", MessageType.QUERY,
                DebateProtocol.META_SENTINEL + "entryType=RAISE|role=REV|round=1|priority=P1\n\nTest",
                UUID.randomUUID().toString(), null,
                io.casehub.platform.api.identity.ActorType.AGENT, java.util.List.of(), null);

        debateBackend.post(channelRef, message);

        assertThat(session.orchestrator()).isNull();
    }

    @Test
    void autonomousSession_flagHuman_terminatesOrchestrator() {
        UUID       channelId  = UUID.randomUUID();
        ChannelRef channelRef = new ChannelRef(channelId, "drafthouse/debate/d-" + channelId);

        DebateSession session = new DebateSession(
                channelId, channelId.toString(), channelRef.name(), null);
        session.setAutonomous(true);
        session.markConverseStarted();

        var orchestrator = mock(io.casehub.agency.conversation.orchestration.ConversationOrchestrator.class);
        session.setOrchestrator(orchestrator);

        when(debateRegistry.find(channelId)).thenReturn(Optional.of(session));

        OutboundMessage flagMsg = new OutboundMessage(
                UUID.randomUUID(), "human-user", MessageType.HANDOFF,
                DebateProtocol.META_SENTINEL + "entryType=FLAG_HUMAN|role=REV|round=2\n\nNeeds human review",
                UUID.randomUUID().toString(), null,
                io.casehub.platform.api.identity.ActorType.HUMAN, java.util.List.of(), null);

        debateBackend.post(channelRef, flagMsg);

        verify(orchestrator).terminate();
    }
}
