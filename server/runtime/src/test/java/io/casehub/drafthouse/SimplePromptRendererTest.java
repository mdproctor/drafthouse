package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.GoalContext;
import io.casehub.eidos.api.Resource;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.SystemPromptRenderer.RenderedPrompt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SimplePromptRendererTest {

    private final SimplePromptRenderer renderer = new SimplePromptRenderer();

    @Test
    void render_includesNameAndRole() {
        AgentDescriptor descriptor = reviewerDescriptor();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).contains("# Structural Reviewer");
        assertThat(result.content()).contains("## Role");
        assertThat(result.content()).contains("document-reviewer");
    }

    @Test
    void render_includesCapabilities() {
        AgentDescriptor descriptor = reviewerDescriptor();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).contains("## Capabilities");
        assertThat(result.content()).contains("**analyze-structure**");
        assertThat(result.content()).contains("markdown-document");
        assertThat(result.content()).contains("structure-report");
    }

    @Test
    void render_includesDisposition_onlyNonNullAxes() {
        AgentDescriptor descriptor = reviewerDescriptor();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).contains("## How You Operate");
        assertThat(result.content()).contains("Conflict mode: collaborative");
        assertThat(result.content()).contains("Rule following: strict");
        // Should NOT contain axes that are null
        assertThat(result.content()).doesNotContain("Social orientation:");
        assertThat(result.content()).doesNotContain("Risk appetite:");
        assertThat(result.content()).doesNotContain("Autonomy:");
    }

    @Test
    void render_includesBriefing() {
        AgentDescriptor descriptor = reviewerDescriptor();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).contains("## Operating Principles");
        assertThat(result.content()).contains("You focus on structural integrity.");
    }

    @Test
    void render_omitsDataHandling_whenBothNull() {
        AgentDescriptor descriptor = AgentDescriptor.builder()
            .agentId("reviewer-01")
            .name("Structural Reviewer")
            .slot("document-reviewer")
            .capabilities(List.of())
            .disposition(AgentDisposition.builder()
                .delegation(true)
                .build())
            .briefing("Briefing.")
            .tenancyId("test-tenant")
            .build();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).doesNotContain("## Data Handling");
    }

    @Test
    void render_includesDataHandling_whenPresent() {
        AgentDescriptor descriptor = AgentDescriptor.builder()
            .agentId("reviewer-01")
            .name("Structural Reviewer")
            .slot("document-reviewer")
            .capabilities(List.of())
            .disposition(AgentDisposition.builder()
                .delegation(true)
                .build())
            .briefing("Briefing.")
            .jurisdiction("EU-GDPR")
            .dataHandlingPolicy("anonymize-pii")
            .tenancyId("test-tenant")
            .build();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).contains("## Data Handling");
        assertThat(result.content()).contains("Jurisdiction: EU-GDPR");
        assertThat(result.content()).contains("Policy: anonymize-pii");
    }

    @Test
    void render_includesGoalAndResources_whenProvided() {
        AgentDescriptor descriptor = reviewerDescriptor();
        GoalContext goal = new GoalContext(
            "Review document for structural consistency",
            List.of("Check heading hierarchy", "Validate cross-references"),
            null
        );
        Resource resource = new Resource("file:///docs/sample.md", "Sample Document", "markdown");
        AgentPromptContext context = new AgentPromptContext(
            Optional.of(goal),
            List.of(resource),
            "This is a peer review session.",
            RenderFormat.MARKDOWN
        );

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).contains("## Current Goal");
        assertThat(result.content()).contains("Review document for structural consistency");
        assertThat(result.content()).contains("Check heading hierarchy");
        assertThat(result.content()).contains("Validate cross-references");

        assertThat(result.content()).contains("## Resources");
        assertThat(result.content()).contains("**Sample Document**");
        assertThat(result.content()).contains("file:///docs/sample.md");
        assertThat(result.content()).contains("(markdown)");

        assertThat(result.content()).contains("## Context");
        assertThat(result.content()).contains("This is a peer review session.");
    }

    @Test
    void render_omitsGoalAndResources_whenFormatOnly() {
        AgentDescriptor descriptor = reviewerDescriptor();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.content()).doesNotContain("## Current Goal");
        assertThat(result.content()).doesNotContain("## Resources");
        assertThat(result.content()).doesNotContain("## Context");
    }

    @Test
    void render_returnsNullHashes_notEnriched() {
        AgentDescriptor descriptor = reviewerDescriptor();
        AgentPromptContext context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN);

        RenderedPrompt result = renderer.render(descriptor, context);

        assertThat(result.format()).isEqualTo(RenderFormat.MARKDOWN);
        assertThat(result.descriptorHash()).isNull();
        assertThat(result.contextHash()).isNull();
        assertThat(result.enriched()).isFalse();
    }

    private AgentDescriptor reviewerDescriptor() {
        AgentCapability capability = AgentCapability.builder()
            .name("analyze-structure")
            .inputTypes(List.of("markdown-document"))
            .outputTypes(List.of("structure-report"))
            .tags(List.of("review"))
            .build();

        AgentDisposition disposition = AgentDisposition.builder()
            .conflictMode("collaborative")
            .ruleFollowing("strict")
            .delegation(true)
            .build();

        return AgentDescriptor.builder()
            .agentId("reviewer-01")
            .name("Structural Reviewer")
            .slot("document-reviewer")
            .capabilities(List.of(capability))
            .disposition(disposition)
            .briefing("You focus on structural integrity.")
            .jurisdiction("EU-GDPR")
            .dataHandlingPolicy("anonymize-pii")
            .tenancyId("test-tenant")
            .build();
    }
}
