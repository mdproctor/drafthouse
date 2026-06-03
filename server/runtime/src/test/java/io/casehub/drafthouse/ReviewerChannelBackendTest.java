package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;

class ReviewerChannelBackendTest {

    private static final UUID CHANNEL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private DataService dataService;
    private MessageService messageService;
    private DocumentReviewer llm;
    private ReviewerChannelBackend backend;
    private ChannelRef channelRef;

    @BeforeEach
    void setUp() {
        dataService = mock(DataService.class);
        messageService = mock(MessageService.class);
        llm = mock(DocumentReviewer.class);

        ReviewSession session = new ReviewSession(
                CHANNEL_ID, "sess-1", "drafthouse-reviewer-sess-1",
                "drafthouse/sess-1/doc-a", "drafthouse/sess-1/doc-b",
                null, null, "You are a reviewer.");
        backend = new ReviewerChannelBackend(session, dataService, messageService, llm, 100_000);
        channelRef = new ChannelRef(CHANNEL_ID, "drafthouse/sess-1");

        SharedData docA = new SharedData();
        docA.content = "Original text";
        SharedData docB = new SharedData();
        docB.content = "Revised text";
        when(dataService.getByKey("drafthouse/sess-1/doc-a")).thenReturn(Optional.of(docA));
        when(dataService.getByKey("drafthouse/sess-1/doc-b")).thenReturn(Optional.of(docB));

        Message queryMsg = new Message();
        queryMsg.id = 42L;
        when(messageService.findByCorrelationId(CORRELATION_ID.toString()))
                .thenReturn(Optional.of(queryMsg));
    }

    @Test
    void queryDispatches_response_onSuccess() {
        when(llm.review(any(), eq("Original text"), eq("Revised text"), any(), eq("Is this clear?")))
                .thenReturn(new ReviewResult(false, "The revision is clear."));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.RESPONSE);
        assertThat(d.inReplyTo()).isEqualTo(42L);
        assertThat(d.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(d.sender()).isEqualTo("drafthouse-reviewer-sess-1");
        assertThat(d.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(d.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(d.content()).isEqualTo("The revision is clear.");
    }

    @Test
    void queryDispatches_decline_whenReviewerDeclines() {
        when(llm.review(any(), any(), any(), any(), any()))
                .thenReturn(ReviewResult.decline("Out of scope."));

        backend.post(channelRef, query("What is the weather?"));

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.DECLINE);
        assertThat(d.content()).isEqualTo("Out of scope.");
        assertThat(d.inReplyTo()).isEqualTo(42L);
    }

    @Test
    void queryDispatches_decline_onLlmException_withSanitizedMessage() {
        when(llm.review(any(), any(), any(), any(), any()))
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
                CORRELATION_ID, null, ActorType.HUMAN));
        verifyNoInteractions(llm, messageService);
    }

    @Test
    void commandMessages_areIgnored() {
        backend.post(channelRef, new OutboundMessage(
                UUID.randomUUID(), "orchestrator", MessageType.COMMAND, "do something",
                CORRELATION_ID, null, ActorType.AGENT));
        verifyNoInteractions(llm, messageService);
    }

    @Test
    void queryWithNoSelection_passesEmptySelectionContext() {
        when(llm.review(any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "Looks good."));

        backend.post(channelRef, query("Is this clear?"));

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(llm).review(any(), any(), any(), eq(""), any());
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
        SharedData bigDoc = new SharedData();
        bigDoc.content = huge;
        when(dataService.getByKey("drafthouse/sess-1/doc-a")).thenReturn(Optional.of(bigDoc));

        backend.post(channelRef, query("Review this"));

        verifyNoInteractions(llm);
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MessageType.DECLINE);
        assertThat(captor.getValue().content()).isEqualTo("Documents exceed the maximum size for review.");
    }

    private OutboundMessage query(String content) {
        return new OutboundMessage(
                UUID.randomUUID(), "human:tester", MessageType.QUERY, content,
                CORRELATION_ID, null, ActorType.HUMAN);
    }
}
