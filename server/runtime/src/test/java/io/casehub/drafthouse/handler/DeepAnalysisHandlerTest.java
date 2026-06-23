package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.*;
import io.casehub.drafthouse.debate.*;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeepAnalysisHandlerTest {

    @Mock ProjectionService projectionService;
    @Mock DebateChannelProjection debateProjection;
    @Mock DebateSessionRegistry registry;
    @Mock MessageService messageService;
    @Mock io.casehub.qhorus.api.gateway.OutboundMessage outboundMessage;

    @TempDir Path tempDir;

    DeepAnalysisHandler handler;
    UUID channelId = UUID.randomUUID();
    Path specFile;

    private void setField(String name, Object value) throws Exception {
        var f = AbstractDebateSubAgentHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(handler, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        handler = new DeepAnalysisHandler();
        setField("projectionService", projectionService);
        setField("debateProjection", debateProjection);
        setField("registry", registry);
        setField("messageService", messageService);
        specFile = tempDir.resolve("spec.md");
        Files.writeString(specFile, "# My Spec\n\nSection content here.");
    }

    private ChannelAgentRequest requestFor(String pointId) {
        StringBuilder meta = new StringBuilder(DebateProtocol.META_SENTINEL)
                .append("entryType=SUB_TASK_REQUEST|agent=REV|taskType=DEEP_ANALYSIS|subTaskId=sub-1");
        if (pointId != null) meta.append("|pointId=").append(pointId);
        String content = meta + "\n\n";
        lenient().when(outboundMessage.content()).thenReturn(content);
        lenient().when(outboundMessage.correlationId()).thenReturn(null);
        return new ChannelAgentRequest(channelId, "sub-1", outboundMessage);
    }

    private DebateSession sessionWithSpec() {
        var session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-" + channelId, (String) null);
        session.addDocument(specFile.toString(), "spec");
        return session;
    }

    @Test
    void no_pointId_uses_no_section_indicated() {
        when(registry.find(channelId)).thenReturn(Optional.of(sessionWithSpec()));

        AgentTask task = handler.prepareTask(requestFor(null));

        assertThat(task.assembledInput()).contains("(no section indicated)");
        assertThat(task.assembledInput()).contains("My Spec");
    }

    @Test
    void with_pointId_having_location_uses_location_as_focus_hint() {
        when(registry.find(channelId)).thenReturn(Optional.of(sessionWithSpec()));
        var thread = List.of(new ThreadEntry("pt-1", AgentType.REV, 1, EntryType.RAISE, "Concern text."));
        var point = new ReviewPoint("pt-1",
                new PointClassification(Priority.P1, Scope.ISOLATED, "§3.2 Authentication"), thread, ReviewStatus.OPEN);
        var state = new ReviewState(Map.of("pt-1", point), List.of(), List.of(), Map.of());
        when(projectionService.project(eq(channelId), any())).thenReturn(new ProjectionResult<>(state, null));

        AgentTask task = handler.prepareTask(requestFor("pt-1"));

        assertThat(task.assembledInput()).contains("§3.2 Authentication");
        assertThat(task.assembledInput()).doesNotContain("(no section indicated)");
    }

    @Test
    void with_pointId_but_point_not_found_falls_back_to_no_section_indicated() {
        when(registry.find(channelId)).thenReturn(Optional.of(sessionWithSpec()));
        var emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        when(projectionService.project(eq(channelId), any())).thenReturn(new ProjectionResult<>(emptyState, null));

        AgentTask task = handler.prepareTask(requestFor("no-such-point"));

        assertThat(task.assembledInput()).contains("(no section indicated)");
    }

    @Test
    void spec_content_included_in_assembled_input() throws IOException {
        when(registry.find(channelId)).thenReturn(Optional.of(sessionWithSpec()));

        AgentTask task = handler.prepareTask(requestFor(null));

        assertThat(task.assembledInput()).contains("Section content here.");
    }

    @Test
    void missing_spec_path_throws() {
        DebateSession sessionNoSpec = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-" + channelId, (String) null);
        when(registry.find(channelId)).thenReturn(Optional.of(sessionNoSpec));

        assertThatThrownBy(() -> handler.prepareTask(requestFor(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document in the working set");
    }
}
