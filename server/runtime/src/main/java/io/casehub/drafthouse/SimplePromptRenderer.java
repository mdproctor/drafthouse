package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.Resource;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * @DefaultBean mock SystemPromptRenderer — renders AgentDescriptor to MARKDOWN
 * without LLM enrichment or vocabulary resolution. Returns null hashes and enriched=false.
 */
@ApplicationScoped
@DefaultBean
public class SimplePromptRenderer implements SystemPromptRenderer {

    @Override
    public RenderedPrompt render(AgentDescriptor descriptor, AgentPromptContext context) {
        StringBuilder content = new StringBuilder();

        // Name and Agent ID
        content.append("# ").append(descriptor.name()).append("\n");
        content.append("**Agent ID:** ").append(descriptor.agentId()).append("\n\n");

        // Role
        content.append("## Role\n");
        content.append(descriptor.slot()).append("\n\n");

        // Capabilities
        if (!descriptor.capabilities().isEmpty()) {
            content.append("## Capabilities\n");
            for (AgentCapability cap : descriptor.capabilities()) {
                content.append("- **").append(cap.name()).append("**");
                if (cap.inputTypes() != null && !cap.inputTypes().isEmpty()) {
                    content.append(": accepts ");
                    content.append(String.join(", ", cap.inputTypes()));
                    if (cap.outputTypes() != null && !cap.outputTypes().isEmpty()) {
                        content.append(" → ");
                        content.append(String.join(", ", cap.outputTypes()));
                    }
                }
                content.append("\n");
            }
            content.append("\n");
        }

        // How You Operate (disposition — only non-null axes)
        AgentDisposition disp = descriptor.disposition();
        if (hasAnyDispositionAxis(disp)) {
            content.append("## How You Operate\n");
            disp.get(DispositionAxis.SOCIAL_ORIENTATION).ifPresent(v ->
                content.append("- Social orientation: ").append(v).append("\n"));
            disp.get(DispositionAxis.RULE_FOLLOWING).ifPresent(v ->
                content.append("- Rule following: ").append(v).append("\n"));
            disp.get(DispositionAxis.RISK_APPETITE).ifPresent(v ->
                content.append("- Risk appetite: ").append(v).append("\n"));
            disp.get(DispositionAxis.AUTONOMY).ifPresent(v ->
                content.append("- Autonomy: ").append(v).append("\n"));
            disp.get(DispositionAxis.CONFLICT_MODE).ifPresent(v ->
                content.append("- Conflict mode: ").append(v).append("\n"));
            content.append("- Can delegate: ").append(disp.delegation() ? "yes" : "no").append("\n");
            content.append("\n");
        }

        // Operating Principles
        if (descriptor.briefing() != null) {
            content.append("## Operating Principles\n");
            content.append(descriptor.briefing()).append("\n\n");
        }

        // Data Handling (only if at least one field is non-null)
        if (descriptor.jurisdiction() != null || descriptor.dataHandlingPolicy() != null) {
            content.append("## Data Handling\n");
            if (descriptor.jurisdiction() != null) {
                content.append("Jurisdiction: ").append(descriptor.jurisdiction()).append("\n");
            }
            if (descriptor.dataHandlingPolicy() != null) {
                content.append("Policy: ").append(descriptor.dataHandlingPolicy()).append("\n");
            }
            content.append("\n");
        }

        // Current Goal
        context.goal().ifPresent(goal -> {
            content.append("## Current Goal\n");
            content.append(goal.description()).append("\n");
            for (String subGoal : goal.subGoals()) {
                content.append("- ").append(subGoal).append("\n");
            }
            content.append("\n");
        });

        // Resources
        if (!context.resources().isEmpty()) {
            content.append("## Resources\n");
            for (Resource res : context.resources()) {
                content.append("- **").append(res.label() != null ? res.label() : res.uri()).append("**: ");
                content.append(res.uri());
                if (res.type() != null) content.append(" (").append(res.type()).append(")");
                content.append("\n");
            }
            content.append("\n");
        }

        // Context
        if (context.situationalContext() != null) {
            content.append("## Context\n");
            content.append(context.situationalContext()).append("\n");
        }

        return new RenderedPrompt(
            content.toString(),
            RenderFormat.MARKDOWN,
            null,  // no descriptor hash
            null,  // no context hash
            false  // not enriched
        );
    }

    private boolean hasAnyDispositionAxis(AgentDisposition disp) {
        return disp.get(DispositionAxis.SOCIAL_ORIENTATION).isPresent()
            || disp.get(DispositionAxis.RULE_FOLLOWING).isPresent()
            || disp.get(DispositionAxis.RISK_APPETITE).isPresent()
            || disp.get(DispositionAxis.AUTONOMY).isPresent()
            || disp.get(DispositionAxis.CONFLICT_MODE).isPresent();
    }
}
