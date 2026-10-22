package io.casehub.drafthouse;

import io.casehub.agency.core.AgentRef;
import io.casehub.agency.core.AgentResult;
import io.casehub.agency.deliberation.conversation.ConversationPoint;
import io.casehub.agency.deliberation.conversation.ConversationState;
import io.casehub.agency.deliberation.conversation.PointClassification;
import io.casehub.agency.deliberation.conversation.Priority;
import io.casehub.agency.deliberation.conversation.orchestration.AgentParticipant;
import io.casehub.agency.perception.observation.ObservationResult;
import io.casehub.agency.perception.observation.PartitionedDrain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class DebatePromptAssemblerTest {

    private static final ConversationState EMPTY_STATE =
            new ConversationState(Map.of(), List.of(), List.of(), Map.of());

    private static final PartitionedDrain<String> EMPTY_DRAIN =
            new PartitionedDrain<>(ObservationResult.empty(0), Map.of());

    private static AgentParticipant participant(String name, String role) {
        var ref = AgentRef.external(name, ignored ->
                CompletableFuture.completedFuture(AgentResult.success(null, "")));
        return new AgentParticipant(ref, role, "You are a " + role + ".");
    }

    @Test
    void assemble_includesDocumentContent() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        session.addDocument("/tmp/before.md", "before");
        session.addDocument("/tmp/after.md", "after");
        session.setComparison("/tmp/before.md", "/tmp/after.md");

        Map<String, String> files = Map.of(
                "/tmp/before.md", "# Original\nLine 1",
                "/tmp/after.md", "# Revised\nLine 1 changed");

        var assembler = new DebatePromptAssembler(() -> session, files::get);
        String prompt = assembler.assemble(participant("rev", "REV"), EMPTY_DRAIN, EMPTY_STATE);

        assertThat(prompt)
                .contains("# Original")
                .contains("# Revised")
                .contains("/tmp/before.md")
                .contains("/tmp/after.md");
    }

    @Test
    void assemble_doesNotIncludeSystemPrompt() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        var assembler = new DebatePromptAssembler(() -> session, path -> "");

        String prompt = assembler.assemble(participant("rev", "REV"), EMPTY_DRAIN, EMPTY_STATE);

        assertThat(prompt).doesNotContain("You are a REV.");
    }

    @Test
    void assemble_includesSelectionScope() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        session.updateSelection(new SelectionScope(DocumentSide.A, 10, 20, "selected text here"));

        var assembler = new DebatePromptAssembler(() -> session, path -> "");
        String prompt = assembler.assemble(participant("rev", "REV"), EMPTY_DRAIN, EMPTY_STATE);

        assertThat(prompt)
                .contains("selected text here")
                .contains("10")
                .contains("20");
    }

    @Test
    void assemble_includesConversationHistory() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        var obsResult = new ObservationResult(
                "Previous findings: point P1 raised by IMP",
                List.of(), 3, 0, null);
        var drain = new PartitionedDrain<String>(obsResult, Map.of());

        var assembler = new DebatePromptAssembler(() -> session, path -> "");
        String prompt = assembler.assemble(participant("rev", "REV"), drain, EMPTY_STATE);

        assertThat(prompt).contains("Previous findings: point P1 raised by IMP");
    }

    @Test
    void assemble_includesOpenPointsSummary() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        var stateWithPoints = new ConversationState(
                Map.of("p1", mock_point(), "p2", mock_point()),
                List.of(), List.of(), Map.of());

        var assembler = new DebatePromptAssembler(() -> session, path -> "");
        String prompt = assembler.assemble(participant("rev", "REV"), EMPTY_DRAIN, stateWithPoints);

        assertThat(prompt).contains("2");
    }

    @Test
    void assemble_minimalState_returnsNonNull() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        var assembler = new DebatePromptAssembler(() -> session, path -> "");

        String prompt = assembler.assemble(participant("rev", "REV"), EMPTY_DRAIN, EMPTY_STATE);

        assertThat(prompt).isNotNull();
    }

    private static ConversationPoint mock_point() {
        return new ConversationPoint("p", null,
                new PointClassification(Priority.LOW, null, null),
                List.of(), "OPEN");
    }
}
