package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.Resource;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.instance.Instance;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
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
    private ReviewerResolver resolver;
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
        DraftHouseConfig.Reviewer reviewerConfig = mock(DraftHouseConfig.Reviewer.class);
        when(config.reviewer()).thenReturn(reviewerConfig);
        when(reviewerConfig.maxDocChars()).thenReturn(100_000);

        resolver = mock(ReviewerResolver.class);
        ResolvedReviewer defaultReviewer = new ResolvedReviewer(
                "drafthouse-structural-reviewer", "Structural Reviewer", "mock instructions");
        when(resolver.resolve(isNull())).thenReturn(defaultReviewer);
        when(resolver.resolve(eq("drafthouse-structural-reviewer"))).thenReturn(defaultReviewer);

        tools = new DraftHouseMcpTools();
        tools.channelService = channelService;
        tools.channelGateway = channelGateway;
        tools.instanceService = instanceService;
        tools.messageService = messageService;
        tools.registry = registry;
        tools.config = config;
        tools.resolver = resolver;

        stubChannel = new Channel();
        stubChannel.id = UUID.randomUUID();
        stubChannel.name = "drafthouse/" + UUID.randomUUID();
        when(channelService.create(any(ChannelCreateRequest.class)))
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

        String result = tools.startReview(docA.toString(), docB.toString(), null);

        assertThat(result).contains(stubChannel.id.toString());

        ArgumentCaptor<ReviewSession> sessionCaptor = ArgumentCaptor.forClass(ReviewSession.class);
        verify(registry).put(sessionCaptor.capture());
        ReviewSession session = sessionCaptor.getValue();
        assertThat(session.channelId()).isEqualTo(stubChannel.id);
        assertThat(session.sessionId()).isEqualTo(stubChannel.id.toString());
        assertThat(session.channelName()).isEqualTo(stubChannel.name);
        assertThat(session.docAContent()).isEqualTo("Content A");
        assertThat(session.docBContent()).isEqualTo("Content B");
        assertThat(session.reviewer().agentId()).isEqualTo("drafthouse-structural-reviewer");
        assertThat(session.reviewer().name()).isEqualTo("Structural Reviewer");
        assertThat(session.reviewer().instructions()).isEqualTo("mock instructions");
        assertThat(session.selection()).isNull();
    }

    @Test
    void startReview_registryPutBeforeInitChannel() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        var order = inOrder(registry, channelGateway);
        tools.startReview(docA.toString(), docB.toString(), null);
        order.verify(registry).put(any());
        order.verify(channelGateway).initChannel(eq(stubChannel.id), any(ChannelRef.class));
    }

    @Test
    void startReview_docTooLarge_returnsError_noQhorusCalls() throws IOException {
        String huge = "x".repeat(100_001);
        Path docA = Files.writeString(tempDir.resolve("a.md"), huge);
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        String result = tools.startReview(docA.toString(), docB.toString(), null);

        assertThat(result).startsWith("error:");
        verifyNoInteractions(channelService, channelGateway, registry);
    }

    @Test
    void startReview_fileNotFound_returnsError_noQhorusCalls() {
        String result = tools.startReview("/nonexistent/path/doc.md", "/also/nonexistent.md", null);

        assertThat(result).startsWith("error:");
        verifyNoInteractions(channelService, channelGateway, registry);
    }

    @Test
    void startReview_channelServiceThrows_cleanupAttempted() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");
        when(channelService.create(any(ChannelCreateRequest.class)))
                .thenThrow(new RuntimeException("DB error"));

        String result = tools.startReview(docA.toString(), docB.toString(), null);

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
        verify(registry).updateSelection(eq(channelId), argThat(sel ->
                sel != null && sel.side() == DocumentSide.A && sel.selectedText().equals("selected text")));
    }

    @Test
    void updateSelection_nullSideAndText_clearsSelection() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.updateSelection(channelId.toString(), null, null);

        assertThat(result).contains("ok");
        verify(registry).updateSelection(channelId, null);
    }

    @Test
    void updateSelection_invalidSide_returnsError_noRegistryUpdate() {
        UUID channelId = UUID.randomUUID();
        when(registry.find(channelId)).thenReturn(Optional.of(minimalSession(channelId)));

        String result = tools.updateSelection(channelId.toString(), "LEFT", "text");

        assertThat(result).startsWith("error:");
        verify(registry, never()).updateSelection(any(UUID.class), any());
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

        verify(registry, never()).updateSelection(any(UUID.class), any());
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
        assertThat(d.sender()).isEqualTo(DraftHouseInstances.HUMAN_INSTANCE_ID);
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
        verify(channelService, never()).delete(any(UUID.class), anyBoolean());
    }

    @Test
    void endReview_withDeleteChannel_deletesChannel() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = minimalSession(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.endReview(channelId.toString(), true);

        assertThat(result).contains("ended");
        verify(registry).remove(channelId);
        verify(channelService).delete(session.channelId(), true);
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

    @Test
    void endReview_deregistersInstance() {
        UUID channelId = UUID.randomUUID();
        ReviewSession session = minimalSession(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.endReview(channelId.toString(), false);

        verify(instanceService).deregister(session.instanceId());
    }

    @Test
    void startReview_partialFailure_deregistersInstanceWhenChannelInitFails() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");
        doThrow(new RuntimeException("init failed"))
                .when(channelGateway).initChannel(any(), any());

        String result = tools.startReview(docA.toString(), docB.toString(), null);

        assertThat(result).startsWith("error:");
        String expectedInstanceId = "drafthouse-reviewer-" + stubChannel.id;
        verify(instanceService).deregister(expectedInstanceId);
    }

    @Test
    void startReview_withAgentId_usesSpecifiedAgent() throws IOException {
        ResolvedReviewer custom = new ResolvedReviewer(
                "drafthouse-content-reviewer", "Content Reviewer", "content instructions");
        when(resolver.resolve(eq("drafthouse-content-reviewer"))).thenReturn(custom);
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        String result = tools.startReview(docA.toString(), docB.toString(),
                "drafthouse-content-reviewer");

        assertThat(result).contains("\"agentId\":\"drafthouse-content-reviewer\"");
        assertThat(result).contains("\"name\":\"Content Reviewer\"");
    }

    @Test
    void startReview_withUnknownAgentId_returnsError() throws IOException {
        when(resolver.resolve(eq("unknown")))
                .thenThrow(new IllegalArgumentException("unknown reviewer agent: unknown"));
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        String result = tools.startReview(docA.toString(), docB.toString(), "unknown");

        assertThat(result).isEqualTo("error: unknown reviewer agent: unknown");
        verifyNoInteractions(channelService);
    }

    @Test
    void startReview_response_includesReviewerBlock() throws IOException {
        Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
        Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

        String result = tools.startReview(docA.toString(), docB.toString(), null);

        assertThat(result).contains("\"reviewer\":{");
        assertThat(result).contains("\"agentId\":\"drafthouse-structural-reviewer\"");
        assertThat(result).contains("\"name\":\"Structural Reviewer\"");
        assertThat(result).contains("\"instructions\":\"mock instructions\"");
    }

    // ── list_reviewers ────────────────────────────────────────────────────────

    @Test
    void listReviewers_returnsFormattedJson() {
        AgentDisposition disp1 = AgentDisposition.builder()
                .conflictMode("collaborative")
                .build();
        AgentCapability cap1 = AgentCapability.builder()
                .name("document-review")
                .tags(List.of("structural"))
                .build();
        AgentDescriptor desc1 = AgentDescriptor.builder()
                .agentId("drafthouse-structural-reviewer")
                .name("Structural Reviewer")
                .slot("document-reviewer")
                .disposition(disp1)
                .capabilities(List.of(cap1))
                .briefing("Reviews document structure and organization")
                .tenancyId(ReviewerDescriptorSeeder.TENANCY_ID)
                .build();

        AgentDisposition disp2 = AgentDisposition.builder()
                .conflictMode("adversarial")
                .build();
        AgentCapability cap2 = AgentCapability.builder()
                .name("document-review")
                .tags(List.of("content"))
                .build();
        AgentDescriptor desc2 = AgentDescriptor.builder()
                .agentId("drafthouse-content-reviewer")
                .name("Content Reviewer")
                .slot("document-reviewer")
                .disposition(disp2)
                .capabilities(List.of(cap2))
                .briefing("Reviews content accuracy and clarity")
                .tenancyId(ReviewerDescriptorSeeder.TENANCY_ID)
                .build();

        AgentDisposition disp3 = AgentDisposition.builder()
                .conflictMode("neutral")
                .build();
        AgentCapability cap3 = AgentCapability.builder()
                .name("document-review")
                .tags(List.of("style"))
                .build();
        AgentDescriptor desc3 = AgentDescriptor.builder()
                .agentId("drafthouse-style-reviewer")
                .name("Style Reviewer")
                .slot("document-reviewer")
                .disposition(disp3)
                .capabilities(List.of(cap3))
                .briefing("Reviews writing style and tone")
                .tenancyId(ReviewerDescriptorSeeder.TENANCY_ID)
                .build();

        AgentDisposition disp4 = AgentDisposition.builder()
                .conflictMode("collaborative")
                .build();
        AgentCapability cap4 = AgentCapability.builder()
                .name("document-review")
                .tags(List.of("technical"))
                .build();
        AgentDescriptor desc4 = AgentDescriptor.builder()
                .agentId("drafthouse-technical-reviewer")
                .name("Technical Reviewer")
                .slot("document-reviewer")
                .disposition(disp4)
                .capabilities(List.of(cap4))
                .briefing("Reviews technical accuracy and completeness")
                .tenancyId(ReviewerDescriptorSeeder.TENANCY_ID)
                .build();

        when(resolver.listAvailable()).thenReturn(List.of(desc1, desc2, desc3, desc4));

        String result = tools.listReviewers();

        assertThat(result).contains("\"agentId\":\"drafthouse-structural-reviewer\"");
        assertThat(result).contains("\"agentId\":\"drafthouse-content-reviewer\"");
        assertThat(result).contains("\"agentId\":\"drafthouse-style-reviewer\"");
        assertThat(result).contains("\"agentId\":\"drafthouse-technical-reviewer\"");
        assertThat(result).contains("\"name\":\"Structural Reviewer\"");
        assertThat(result).contains("\"name\":\"Content Reviewer\"");
        assertThat(result).contains("\"name\":\"Style Reviewer\"");
        assertThat(result).contains("\"name\":\"Technical Reviewer\"");
        assertThat(result).contains("\"slot\":\"document-reviewer\"");
        assertThat(result).contains("\"conflictMode\":\"collaborative\"");
        assertThat(result).contains("\"conflictMode\":\"adversarial\"");
        assertThat(result).contains("\"conflictMode\":\"neutral\"");
        assertThat(result).contains("\"capabilities\":[");
        assertThat(result).contains("\"name\":\"document-review\"");
        assertThat(result).contains("\"tags\":[\"structural\"]");
        assertThat(result).contains("\"tags\":[\"content\"]");
        assertThat(result).contains("\"tags\":[\"style\"]");
        assertThat(result).contains("\"tags\":[\"technical\"]");
        assertThat(result).contains("\"briefingSummary\":\"Reviews document structure and organization\"");
        assertThat(result).contains("\"briefingSummary\":\"Reviews content accuracy and clarity\"");
        assertThat(result).contains("\"briefingSummary\":\"Reviews writing style and tone\"");
        assertThat(result).contains("\"briefingSummary\":\"Reviews technical accuracy and completeness\"");
    }

    // ── get_reviewer_instructions ─────────────────────────────────────────────

    @Test
    void getReviewerInstructions_withResourcePath() {
        ResolvedReviewer reviewer = new ResolvedReviewer(
                "drafthouse-structural-reviewer",
                "Structural Reviewer",
                "full instructions for structural review");
        when(resolver.resolve(eq("drafthouse-structural-reviewer"), any(Resource[].class)))
                .thenReturn(reviewer);

        String result = tools.getReviewerInstructions(
                "drafthouse-structural-reviewer",
                "/path/to/spec.md");

        assertThat(result).contains("\"agentId\":\"drafthouse-structural-reviewer\"");
        assertThat(result).contains("\"name\":\"Structural Reviewer\"");
        assertThat(result).contains("\"instructions\":\"full instructions for structural review\"");

        ArgumentCaptor<Resource[]> captor = ArgumentCaptor.forClass(Resource[].class);
        verify(resolver).resolve(eq("drafthouse-structural-reviewer"), captor.capture());
        Resource[] resources = captor.getValue();
        assertThat(resources).hasSize(1);
        assertThat(resources[0].uri()).isEqualTo("/path/to/spec.md");
        assertThat(resources[0].label()).isEqualTo("spec");
        assertThat(resources[0].type()).isEqualTo("file");
    }

    @Test
    void getReviewerInstructions_withoutResourcePath() {
        ResolvedReviewer reviewer = new ResolvedReviewer(
                "drafthouse-content-reviewer",
                "Content Reviewer",
                "default content review instructions");
        when(resolver.resolve(eq("drafthouse-content-reviewer"), any(Resource[].class)))
                .thenReturn(reviewer);

        String result = tools.getReviewerInstructions("drafthouse-content-reviewer", null);

        assertThat(result).contains("\"agentId\":\"drafthouse-content-reviewer\"");
        assertThat(result).contains("\"name\":\"Content Reviewer\"");
        assertThat(result).contains("\"instructions\":\"default content review instructions\"");

        ArgumentCaptor<Resource[]> captor = ArgumentCaptor.forClass(Resource[].class);
        verify(resolver).resolve(eq("drafthouse-content-reviewer"), captor.capture());
        Resource[] resources = captor.getValue();
        assertThat(resources).isEmpty();
    }

    @Test
    void getReviewerInstructions_unknownAgent() {
        when(resolver.resolve(eq("unknown-agent"), any(Resource[].class)))
                .thenThrow(new IllegalArgumentException("unknown reviewer agent: unknown-agent"));

        String result = tools.getReviewerInstructions("unknown-agent", null);

        assertThat(result).isEqualTo("error: unknown reviewer agent: unknown-agent");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ReviewSession minimalSession(UUID channelId) {
        return new ReviewSession(
                channelId, channelId.toString(), "drafthouse/test",
                "drafthouse-reviewer-" + channelId,
                "Doc A", "Doc B", null,
                new ResolvedReviewer("drafthouse-structural-reviewer",
                        "Structural Reviewer", "mock instructions"));
    }
}
