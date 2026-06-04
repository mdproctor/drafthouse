package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;

class DraftHouseMcpToolsTest {

    @TempDir Path tempDir;

    private ChannelService channelService;
    private ChannelGateway channelGateway;
    private InstanceService instanceService;
    private MessageService messageService;
    private ReviewSessionRegistry registry;
    private DraftHouseConfig config;
    private DraftHouseMcpTools tools;

    private Channel stubChannel;
    private Instance stubInstance;

    @BeforeEach
    void setUp() {
        channelService = mock(ChannelService.class);
        channelGateway = mock(ChannelGateway.class);
        instanceService = mock(InstanceService.class);
        messageService = mock(MessageService.class);
        registry = mock(ReviewSessionRegistry.class);
        config = mock(DraftHouseConfig.class);

        tools = new DraftHouseMcpTools();
        tools.channelService = channelService;
        tools.channelGateway = channelGateway;
        tools.instanceService = instanceService;
        tools.messageService = messageService;
        tools.registry = registry;
        tools.config = config;

        when(config.maxDocChars()).thenReturn(100_000);
        when(config.personality()).thenReturn("You are a reviewer.");

        stubChannel = new Channel();
        stubChannel.id = UUID.randomUUID();
        stubChannel.name = "drafthouse/" + UUID.randomUUID();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(stubChannel);

        stubInstance = new Instance();
        stubInstance.id = UUID.randomUUID();
        when(instanceService.register(anyString(), anyString(), any())).thenReturn(stubInstance);
    }

    // ── start_review ─────────────────────────────────────────────────────────

    @Test
    void startReview_happyPath_returnsSessionIdAndCreatesSession() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "Content A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "Content B");

        String result = tools.startReview(docA.toString(), docB.toString());

        assertThat(result).contains(stubChannel.id.toString());

        ArgumentCaptor<ReviewSession> sessionCaptor = ArgumentCaptor.forClass(ReviewSession.class);
        verify(registry).put(sessionCaptor.capture());
        ReviewSession session = sessionCaptor.getValue();
        assertThat(session.channelId()).isEqualTo(stubChannel.id);
        assertThat(session.sessionId()).isEqualTo(stubChannel.id.toString());
        assertThat(session.channelName()).isEqualTo(stubChannel.name);
        assertThat(session.docAContent()).isEqualTo("Content A");
        assertThat(session.docBContent()).isEqualTo("Content B");
        assertThat(session.personality()).isEqualTo("You are a reviewer.");
        assertThat(session.selectionSide()).isNull();
        assertThat(session.selectionText()).isNull();
    }

    @Test
    void startReview_registryPutBeforeInitChannel() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        var order = inOrder(registry, channelGateway);
        tools.startReview(docA.toString(), docB.toString());
        order.verify(registry).put(any());
        order.verify(channelGateway).initChannel(eq(stubChannel.id), any(ChannelRef.class));
    }

    @Test
    void startReview_docTooLarge_returnsError_noQhorusCalls() throws IOException {
        String huge = "x".repeat(100_001);
        Path docA = Files.writeString(tempDir.resolve("a.md"), huge);
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        String result = tools.startReview(docA.toString(), docB.toString());

        assertThat(result).startsWith("error:");
        verifyNoInteractions(channelService, channelGateway, registry);
    }

    @Test
    void startReview_fileNotFound_returnsError_noQhorusCalls() {
        String result = tools.startReview("/nonexistent/path/doc.md", "/also/nonexistent.md");

        assertThat(result).startsWith("error:");
        verifyNoInteractions(channelService, channelGateway, registry);
    }

    @Test
    void startReview_channelServiceThrows_cleanupAttempted() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");
        when(channelService.create(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        String result = tools.startReview(docA.toString(), docB.toString());

        assertThat(result).startsWith("error:");
        verify(registry, never()).put(any());
    }

    // ── update_selection ──────────────────────────────────────────────────────

    @Test
    void updateSelection_happyPath_updatesRegistry() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = minimalSession(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.updateSelection(channelId.toString(), "A", "selected text");

        assertThat(result).contains("ok");
        verify(registry).updateSelection(channelId, DocumentSide.A, "selected text");
    }

    @Test
    void updateSelection_nullSideAndText_clearsSelection() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.updateSelection(channelId.toString(), null, null);

        assertThat(result).contains("ok");
        verify(registry).updateSelection(channelId, null, null);
    }

    @Test
    void updateSelection_invalidSide_returnsError_noRegistryUpdate() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.updateSelection(channelId.toString(), "LEFT", "text");

        assertThat(result).startsWith("error:");
        verify(registry, never()).updateSelection(any(), any(), any());
    }

    @Test
    void updateSelection_sessionNotFound_returnsError() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.empty());

        String result = tools.updateSelection(channelId.toString(), "A", "text");

        assertThat(result).startsWith("error:");
    }

    @Test
    void updateSelection_invalidSessionId_returnsError() {
        String result = tools.updateSelection("not-a-uuid", "A", "text");
        assertThat(result).startsWith("error:");
    }

    @Test
    void updateSelection_halfNullInput_returnsError() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        // side provided but no text
        String result1 = tools.updateSelection(channelId.toString(), "A", null);
        assertThat(result1).startsWith("error:");

        // text provided but no side
        String result2 = tools.updateSelection(channelId.toString(), null, "some text");
        assertThat(result2).startsWith("error:");

        verify(registry, never()).updateSelection(any(), any(), any());
    }

    // ── query_review ──────────────────────────────────────────────────────────

    @Test
    void queryReview_happyPath_dispatchesQuery() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.queryReview(channelId.toString(), "Is this revision clear?");

        assertThat(result).contains("dispatched");

        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch d = captor.getValue();
        assertThat(d.type()).isEqualTo(MessageType.QUERY);
        assertThat(d.channelId()).isEqualTo(channelId);
        assertThat(d.content()).isEqualTo("Is this revision clear?");
        assertThat(d.sender()).isEqualTo(DraftHouseMcpTools.HUMAN_INSTANCE_ID);
        assertThat(d.correlationId()).isNotBlank();
    }

    @Test
    void queryReview_sessionNotFound_returnsError() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.empty());

        String result = tools.queryReview(channelId.toString(), "Question?");

        assertThat(result).startsWith("error:");
        verifyNoInteractions(messageService);
    }

    @Test
    void queryReview_invalidSessionId_returnsError() {
        String result = tools.queryReview("bad-uuid", "Question?");
        assertThat(result).startsWith("error:");
    }

    // ── end_review ────────────────────────────────────────────────────────────

    @Test
    void endReview_happyPath_removesSession() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.endReview(channelId.toString(), false);

        assertThat(result).contains("ended");
        verify(registry).remove(channelId);
        verify(channelService, never()).delete(anyString(), anyBoolean());
    }

    @Test
    void endReview_withDeleteChannel_deletesChannel() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = minimalSession(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.endReview(channelId.toString(), true);

        assertThat(result).contains("ended");
        verify(registry).remove(channelId);
        verify(channelService).delete(session.channelName(), true);
    }

    @Test
    void endReview_sessionNotFound_returnsNotFound_idempotent() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.empty());

        String result = tools.endReview(channelId.toString(), false);

        assertThat(result).contains("not-found");
        verify(registry, never()).remove(any());
    }

    @Test
    void endReview_invalidSessionId_returnsError() {
        String result = tools.endReview("not-a-uuid", false);
        assertThat(result).startsWith("error:");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ReviewSession minimalSession(UUID channelId) {
        return new ReviewSession(
                channelId, channelId.toString(), "drafthouse/test",
                "drafthouse-reviewer-" + channelId,
                "Doc A", "Doc B", null, null, "You are a reviewer.");
    }
}
