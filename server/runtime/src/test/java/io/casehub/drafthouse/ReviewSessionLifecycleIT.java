package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ReviewSessionLifecycleIT {

    @Inject ReviewerChannelBackendFactory factory;
    @Inject ChannelService channelService;
    @Inject ChannelGateway gateway;
    @Inject MessageService messageService;
    @Inject DataService dataService;
    @Inject InstanceService instanceService;

    @InjectMock DocumentReviewer documentReviewer;

    @BeforeEach
    void setUp() {
        when(documentReviewer.review(any(), any(), any(), any(), any()))
                .thenReturn(new ReviewResult(false, "Good revision."));
    }

    @Test
    void query_dispatchesResponse_andResponseIsFound() {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String channelName = "drafthouse/" + sessionId;
        String instanceId = "drafthouse-reviewer-" + sessionId;

        Channel channel = channelService.create(channelName, "Test session", ChannelSemantic.APPEND, null);
        instanceService.register(instanceId, "Test reviewer", List.of("document-review"));
        dataService.store("drafthouse/" + sessionId + "/doc-a", null, instanceId, "Original text", false, true);
        dataService.store("drafthouse/" + sessionId + "/doc-b", null, instanceId, "Revised text", false, true);

        ReviewSession session = new ReviewSession(
                channel.id, sessionId, instanceId,
                "drafthouse/" + sessionId + "/doc-a",
                "drafthouse/" + sessionId + "/doc-b",
                null, null, "You are a reviewer.");
        factory.put(session);

        gateway.initChannel(channel.id, new ChannelRef(channel.id, channelName));

        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id)
                .sender("human:tester")
                .type(MessageType.QUERY)
                .content("Is this revision clear?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        // fanOut runs the backend on a virtual thread — poll until the RESPONSE appears or timeout.
        Optional<Message> response = Optional.empty();
        for (int i = 0; i < 40 && response.isEmpty(); i++) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            response = messageService.findResponseByCorrelationId(channel.id, correlationId);
        }
        assertThat(response).isPresent();
        assertThat(response.get().messageType).isEqualTo(MessageType.RESPONSE);
        assertThat(response.get().sender).isEqualTo(instanceId);
        assertThat(response.get().content).isEqualTo("Good revision.");
    }

    @Test
    void startupRecovery_skipsChannelWithNoSession() {
        Channel channel = channelService.create(
                "drafthouse/orphan-" + UUID.randomUUID(), "Orphan", ChannelSemantic.APPEND, null);

        gateway.initChannel(channel.id, new ChannelRef(channel.id, channel.name));

        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id)
                .sender("human:tester")
                .type(MessageType.QUERY)
                .content("Hello?")
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        Optional<Message> response = messageService.findResponseByCorrelationId(channel.id, correlationId);
        assertThat(response).isEmpty();
    }

    @Test
    void factory_find_returnsSession_afterPut() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, "s", "i", "k-a", "k-b", null, null, "personality");
        factory.put(session);
        assertThat(factory.find(channelId)).contains(session);
    }

    @Test
    void factory_remove_clearsSession() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, "s", "i", "k-a", "k-b", null, null, "personality");
        factory.put(session);
        factory.remove(channelId);
        assertThat(factory.find(channelId)).isEmpty();
    }

    @Test
    void factory_updateSelection_replacesSelectionFields() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = new ReviewSession(
                channelId, "s", "i", "k-a", "k-b", null, null, "personality");
        factory.put(session);
        factory.updateSelection(channelId, DocumentSide.A, "selected text");
        ReviewSession updated = factory.find(channelId).orElseThrow();
        assertThat(updated.selectionSide()).isEqualTo(DocumentSide.A);
        assertThat(updated.selectionText()).isEqualTo("selected text");
    }
}
