package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.SystemPromptRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewerResolverTest {

    private AgentRegistry agentRegistry;
    private SystemPromptRenderer systemPromptRenderer;
    private ReviewerResolver resolver;

    private AgentDescriptor structuralDescriptor;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        systemPromptRenderer = mock(SystemPromptRenderer.class);

        resolver = new ReviewerResolver();
        resolver.agentRegistry = agentRegistry;
        resolver.systemPromptRenderer = systemPromptRenderer;

        structuralDescriptor = AgentDescriptor.builder()
                .agentId(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID)
                .name("Structural Reviewer")
                .slot(ReviewerDescriptorSeeder.SLOT)
                .tenancyId(ReviewerDescriptorSeeder.TENANCY_ID)
                .build();

        when(agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.of(structuralDescriptor));
        when(systemPromptRenderer.render(any(), any()))
                .thenReturn(new SystemPromptRenderer.RenderedPrompt(
                        "rendered instructions", SystemPromptRenderer.RenderFormat.MARKDOWN,
                        null, null, false));
    }

    @Test
    void resolve_withExplicitAgentId_returnsResolvedReviewer() {
        when(agentRegistry.findById("drafthouse-structural-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.of(structuralDescriptor));

        ResolvedReviewer result = resolver.resolve("drafthouse-structural-reviewer");

        assertThat(result.agentId()).isEqualTo("drafthouse-structural-reviewer");
        assertThat(result.name()).isEqualTo("Structural Reviewer");
        assertThat(result.instructions()).isEqualTo("rendered instructions");
    }

    @Test
    void resolve_withNullAgentId_usesDefaultReviewer() {
        ResolvedReviewer result = resolver.resolve(null);

        assertThat(result.agentId()).isEqualTo(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID);
        assertThat(result.name()).isEqualTo("Structural Reviewer");
    }

    @Test
    void resolve_withBlankAgentId_usesDefaultReviewer() {
        ResolvedReviewer result = resolver.resolve("  ");

        assertThat(result.agentId()).isEqualTo(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID);
    }

    @Test
    void resolve_withUnknownAgentId_throwsIllegalArgument() {
        when(agentRegistry.findById("unknown", ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown reviewer agent: unknown");
    }

    @Test
    void resolve_withNoAgentsRegistered_throwsIllegalState() {
        when(agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no reviewer agents registered");
    }

    @Test
    void resolve_passesResourcesToRenderer() {
        var resource = new io.casehub.eidos.api.Resource("spec.md", "spec", "file");
        resolver.resolve(null, resource);

        ArgumentCaptor<AgentPromptContext> ctxCaptor =
                ArgumentCaptor.forClass(AgentPromptContext.class);
        verify(systemPromptRenderer).render(eq(structuralDescriptor), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().resources()).containsExactly(resource);
    }

    @Test
    void resolve_goalIncludesAgentName() {
        resolver.resolve(null);

        ArgumentCaptor<AgentPromptContext> ctxCaptor =
                ArgumentCaptor.forClass(AgentPromptContext.class);
        verify(systemPromptRenderer).render(any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().goal()).isPresent();
        assertThat(ctxCaptor.getValue().goal().get().description())
                .contains("structural reviewer");
    }

    @Test
    void listAvailable_returnsAllReviewerAgents() {
        when(agentRegistry.find(AgentQuery.bySlot(ReviewerDescriptorSeeder.SLOT,
                ReviewerDescriptorSeeder.TENANCY_ID)))
                .thenReturn(List.of(new AgentMatch(structuralDescriptor, null)));

        List<AgentDescriptor> result = resolver.listAvailable();

        assertThat(result).containsExactly(structuralDescriptor);
    }

    @Test
    void findDescriptor_knownAgent_returnsDescriptor() {
        when(agentRegistry.findById("drafthouse-structural-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.of(structuralDescriptor));

        Optional<AgentDescriptor> result =
                resolver.findDescriptor("drafthouse-structural-reviewer");

        assertThat(result).contains(structuralDescriptor);
    }

    @Test
    void findDescriptor_unknownAgent_returnsEmpty() {
        when(agentRegistry.findById("nonexistent", ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.empty());

        Optional<AgentDescriptor> result = resolver.findDescriptor("nonexistent");

        assertThat(result).isEmpty();
    }
}
