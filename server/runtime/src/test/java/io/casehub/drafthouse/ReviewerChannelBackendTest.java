package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.casehub.blocks.conversation.ConversationPoint;
import io.casehub.blocks.conversation.ConversationState;
import io.casehub.blocks.conversation.PointClassification;
import io.casehub.blocks.conversation.Priority;
import io.casehub.blocks.conversation.ThreadEntry;
import io.casehub.drafthouse.debate.AgentType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;

class ReviewerChannelBackendTest {

    private static final UUID CHANNEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String CORRELATION_ID = "00000000-0000-0000-0000-000000000002";

    private ReviewSessionRegistry registry;
    private MessageService messageService;
    private DocumentReviewer llm;
    private ProjectionService projectionService;
    private ChannelProjection<ConversationState> projection;
    private ReviewerChannelBackend backend;
    private ChannelRef channelRef;
    private ReviewSession session;

    @BeforeEach
    void setUp() {
        registry = mock(ReviewSessionRegistry.class);
        messageService = mock(MessageService.class);
        llm = mock(DocumentReviewer.class);
        projectionService = mock(ProjectionService.class);
        projection = mock(ChannelProjection.class);
        when(projectionService.project(CHANNEL_ID, projection))
                .thenReturn(new ProjectionResult<>(new ConversationState(Map.of(), List.of(), List.of(), Map.of()), null));

        session = new ReviewSession(
                CHANNEL_ID, CHANNEL_ID.toString(), "drafthouse/sess-1",
                "drafthouse-reviewer-" + CHANNEL_ID,
                "Original text", "Revised text",
                null, new ResolvedReviewer("test-id", "Test Reviewer", "You are a reviewer."));

        backend = new ReviewerChannelBackend(registry, CHANNEL_ID, messageService, llm, 100_000,
                projectionService, projection);
        channelRef = new ChannelRef(CHANNEL_ID, "drafthouse/sess-1");

        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(session));

        Message queryMsg = Message.builder().id(42L).build();
        when(messageService.findByCorrelationId(CORRELATION_ID.toString()))
                .thenReturn(Optional.of(queryMsg));
    }

    @Test
    void queryDispatches_done_whenReviewerAgrees() {
        when(llm.review(any(), eq("Original text"), eq("Revised text"), any(), any(), eq("Is this clear?")))
                .thenReturn(ReviewResult.agree("The revision is clear."));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.DONE);
        assertThat(d.inReplyTo()).isEqualTo(42L);
        assertThat(d.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(d.sender()).isEqualTo(session.instanceId());
        assertThat(d.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(d.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(d.content()).isEqualTo("The revision is clear.");
    }

    @Test
    void queryDispatches_response_whenReviewerQualifies() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.qualify("I partially agree but have concerns."));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.RESPONSE);
        assertThat(captor.getValue().content()).isEqualTo("I partially agree but have concerns.");
    }

    @Test
    void queryDispatches_decline_whenReviewerDeclines() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.decline("Out of scope."));

        backend.post(channelRef, query("What is the weather?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Out of scope.");
    }

    @Test
    void queryDispatches_decline_onLlmException_withSanitizedMessage() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("sk-ant-api03-SECRET-KEY"));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.DECLINE);
        assertThat(d.content()).isEqualTo("Reviewer encountered an error.");
        assertThat(d.content()).doesNotContain("sk-ant-api03-SECRET-KEY");
    }

    @Test
    void nonQueryMessages_areIgnored() {
        backend.post(channelRef, new OutboundMessage(
                UUID.randomUUID(), "user", MessageType.EVENT, "ping",
                CORRELATION_ID, null, ActorType.HUMAN, List.of()));
        verifyNoInteractions(llm, messageService);
    }

    @Test
    void queryWithNoSelection_passesEmptySelectionContext() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.agree("Looks good."));

        backend.post(channelRef, query("Is this clear?"));

        verify(llm).review(any(), any(), any(), eq(""), any(), any());
    }

    @Test
    void queryWithSelection_passesSelectionContext() {
        ReviewSession withSelection = session.withSelection(new SelectionScope(DocumentSide.B, 0, 0, "key paragraph"));
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(withSelection));
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.agree("Noted."));

        backend.post(channelRef, query("Is this clear?"));

        verify(llm).review(any(), any(), any(),
                eq("Selected text (Document B): key paragraph"), any(), any());
    }

    @Test
    void liveSessionRead_reflectsSelectionUpdatedAfterConstruction() {
        ReviewSession updated = session.withSelection(new SelectionScope(DocumentSide.A, 0, 0, "updated selection"));
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(updated));
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.agree("OK"));

        backend.post(channelRef, query("Review?"));

        verify(llm).review(any(), any(), any(),
                eq("Selected text (Document A): updated selection"), any(), any());
    }

    @Test
    void endedSession_silentlyIgnoresQuery() {
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.empty());

        backend.post(channelRef, query("Is this clear?"));

        verifyNoInteractions(llm);
        verify(messageService, never()).dispatch(any());
    }

    @Test
    void inReplyToNotFound_skipsDispatch() {
        when(messageService.findByCorrelationId(CORRELATION_ID.toString()))
                .thenReturn(Optional.empty());

        backend.post(channelRef, query("Is this clear?"));

        verify(messageService, never()).dispatch(any());
        verifyNoInteractions(llm);
    }

    @Test
    void documentsExceedingMaxSize_dispatchDecline_withoutCallingLlm() {
        String huge = "x".repeat(100_001);
        ReviewSession bigSession = new ReviewSession(
                CHANNEL_ID, CHANNEL_ID.toString(), "drafthouse/sess-1",
                session.instanceId(), huge, "Revised text",
                null, new ResolvedReviewer("test-id", "Test Reviewer", "You are a reviewer."));
        when(registry.find(CHANNEL_ID)).thenReturn(Optional.of(bigSession));

        backend.post(channelRef, query("Review this"));

        verifyNoInteractions(llm);
        verifyNoInteractions(projectionService);
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Documents exceed the maximum size for review.");
    }

    @Test
    void queryDispatch_callsProjectionBeforeLlm() {
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.agree("Response."));

        backend.post(channelRef, query("Question?"));

        InOrder order = inOrder(projectionService, llm);
        order.verify(projectionService).project(CHANNEL_ID, projection);
        order.verify(llm).review(any(), any(), any(), any(), any(), any());
    }

    @Test
    void queryDispatch_passesRenderedHistoryToLlm() {
        ConversationState stateWithHistory = buildAgreedState();
        when(projectionService.project(CHANNEL_ID, projection))
                .thenReturn(new ProjectionResult<>(stateWithHistory, 42L));
        when(llm.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.agree("Response."));

        backend.post(channelRef, query("Current question?"));

        ArgumentCaptor<String> historyCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).review(any(), any(), any(), any(), historyCaptor.capture(), any());
        assertThat(historyCaptor.getValue())
                .contains("Q: Prior question?")
                .contains("A: Prior answer.");
    }

    private static ConversationState buildAgreedState() {
        var thread = new java.util.ArrayList<ThreadEntry>();
        thread.add(new ThreadEntry("P1", "REV", 0, "RAISE", "Prior question?"));
        thread.add(new ThreadEntry(null, "IMP", 0, "AGREE", "Prior answer."));
        var point = new ConversationPoint(
                "P1",
                new PointClassification(Priority.LOW, "ISOLATED", null),
                thread,
                "AGREED");
        return new ConversationState(Map.of("P1", point), List.of(), List.of(), Map.of());
    }

    @Test
    void nonQueryMessage_doesNotCallProjectionOrLlm() {
        OutboundMessage eventMsg = outboundMessage(MessageType.EVENT, "Some event", CORRELATION_ID);
        backend.post(channelRef, eventMsg);
        verifyNoInteractions(projectionService);
        verifyNoInteractions(llm);
    }

    private OutboundMessage query(String content) {
        return new OutboundMessage(
                UUID.randomUUID(), "human:tester", MessageType.QUERY, content,
                CORRELATION_ID, null, ActorType.HUMAN, List.of());
    }

    private OutboundMessage outboundMessage(MessageType type, String content, String correlationId) {
        return new OutboundMessage(
                UUID.randomUUID(), "user", type, content,
                correlationId, null, ActorType.HUMAN, List.of());
    }
}
