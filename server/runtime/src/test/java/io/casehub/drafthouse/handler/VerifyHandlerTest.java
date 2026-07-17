package io.casehub.drafthouse.handler;

import io.casehub.blocks.channel.ChannelAgentRequest;
import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.conversation.ConversationState;
import io.casehub.blocks.conversation.ConversationPoint;
import io.casehub.blocks.conversation.ThreadEntry;
import io.casehub.blocks.conversation.PointClassification;
import io.casehub.blocks.conversation.Priority;
import io.casehub.drafthouse.*;
import io.casehub.drafthouse.debate.*;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerifyHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    VerifyHandler handler;
    UUID channelId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        handler = new VerifyHandler();
        setField("projectionService", projectionService);
        setField("debateProjection", debateProjection);
        setField("registry", registry);
        setField("messageService", messageService);
    }

    private void setField(String name, Object value) throws Exception {
        var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(handler, value);
    }

    private DebateSession sessionWithSpec(String specPath) {
        var session = new DebateSession(channelId, channelId.toString(), "ch", (String) null);
        if (specPath != null) session.addDocument(specPath, "spec");
        return session;
    }

    private ChannelAgentRequest requestFor(String pointId) {
        String content = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|role=REV|taskType=VERIFY|subTaskId=sub-1"
                + (pointId != null ? "|pointId=" + pointId : "")
                + "\n\n";
        lenient().when(outboundMessage.content()).thenReturn(content);
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
        return new ChannelAgentRequest(channelId, "sub-1", outboundMessage, null);
    }

    private void setupState(String pointId, String raiseContent) {
        var thread = List.of(new ThreadEntry(pointId, null, null, "REV", 1, "RAISE", raiseContent));
        var point = new ConversationPoint(pointId, null,
                new PointClassification(Priority.HIGH, "ISOLATED", null),
                thread, "OPEN");
        var state = new ConversationState(Map.of(pointId, point), List.of(), List.of(), Map.of());
        lenient().when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(state, null));
    }

    @Test
    void assembles_claim_and_spec_content(@TempDir Path dir) throws IOException {
        Path specFile = dir.resolve("spec.md");
        java.nio.file.Files.writeString(specFile, "# The Spec\nThis is the spec content.");
        when(registry.find(channelId)).thenReturn(Optional.of(sessionWithSpec(specFile.toString())));
        setupState("pt-1", "The claim content.");
        AgentTask task = handler.prepareTask(requestFor("pt-1"));
        assertThat(task.assembledInput()).contains("The claim content.");
        assertThat(task.assembledInput()).contains("The Spec");
    }

    @Test
    void throws_on_empty_document_set() {
        when(registry.find(channelId)).thenReturn(Optional.of(
                new DebateSession(channelId, channelId.toString(), "ch", (String) null)));
        setupState("pt-1", "Some claim.");
        assertThatThrownBy(() -> handler.prepareTask(requestFor("pt-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document in the working set");
    }

    @Test
    void throws_on_null_pointId() {
        when(registry.find(channelId)).thenReturn(Optional.of(sessionWithSpec("/some/spec.md")));
        var state = new ConversationState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        assertThatThrownBy(() -> handler.prepareTask(requestFor(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pointId");
    }

    @Test
    void buildResponse_propagatesRoundFromTriggerMeta() throws Exception {
        // The SUB_TASK_REQUEST trigger includes round=3 — the finding must carry it
        String triggerContent = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_REQUEST|role=REV|taskType=VERIFY|subTaskId=sub-1|round=3|pointId=pt-1"
                + "\n\n";
        when(outboundMessage.content()).thenReturn(triggerContent);
        var request = new ChannelAgentRequest(channelId, "sub-1", outboundMessage, null);
        io.casehub.qhorus.api.message.Message stubMsg = io.casehub.qhorus.api.message.Message.builder()
                .id(42L)
                .build();
        when(messageService.findByCorrelationId("sub-1")).thenReturn(Optional.of(stubMsg));

        MessageDispatch dispatch = handler.buildResponse(channelId, "sender-id", "The finding.", request);

        assertThat(dispatch.content()).contains("entryType=SUB_TASK_FINDING");
        assertThat(dispatch.content()).contains("round=3");
    }

    @Test
    void does_not_include_thread_history_beyond_raise(@TempDir Path dir) throws IOException {
        // Invariant: VERIFY must include only the raise content, not any subsequent thread entries
        Path specFile = dir.resolve("spec.md");
        java.nio.file.Files.writeString(specFile, "# The Spec");
        when(registry.find(channelId)).thenReturn(Optional.of(sessionWithSpec(specFile.toString())));
        var thread = List.of(
                new ThreadEntry("pt-1", null, null, "REV", 1, "RAISE", "The claim."),
                new ThreadEntry(null, null, null, "IMP", 2, "DISPUTE", "Other agent content.")
        );
        var point = new ConversationPoint("pt-1", null,
                new PointClassification(Priority.HIGH, "ISOLATED", null), thread, "DISPUTED");
        var state = new ConversationState(Map.of("pt-1", point), List.of(), List.of(), Map.of());
        when(projectionService.project(any(), any())).thenReturn(new ProjectionResult<>(state, null));
        AgentTask task = handler.prepareTask(requestFor("pt-1"));
        assertThat(task.assembledInput()).contains("The claim.");
        assertThat(task.assembledInput()).doesNotContain("Other agent content.");
    }

}
