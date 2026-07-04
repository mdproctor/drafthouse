package io.casehub.drafthouse;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalContext;
import io.casehub.eidos.api.Resource;
import io.casehub.eidos.api.SystemPromptRenderer;

@ApplicationScoped
public class ReviewerResolver {

    @Inject AgentRegistry agentRegistry;
    @Inject SystemPromptRenderer systemPromptRenderer;

    public ResolvedReviewer resolve(String agentId, Resource... resources) {
        AgentDescriptor descriptor;
        String resolvedId;
        if (agentId != null && !agentId.isBlank()) {
            descriptor = agentRegistry.findById(agentId, ReviewerDescriptorSeeder.TENANCY_ID)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown reviewer agent: " + agentId));
            resolvedId = agentId;
        } else {
            descriptor = agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                    ReviewerDescriptorSeeder.TENANCY_ID)
                    .orElseThrow(() -> new IllegalStateException(
                            "no reviewer agents registered"));
            resolvedId = descriptor.agentId();
        }
        var context = AgentPromptContext.forFormat(SystemPromptRenderer.RenderFormat.MARKDOWN)
                .withGoal(GoalContext.of("Review document changes from a "
                        + descriptor.name().toLowerCase() + " perspective"))
                .withResources(List.of(resources));
        String instructions = systemPromptRenderer.render(descriptor, context).content();
        return new ResolvedReviewer(resolvedId, descriptor.name(), instructions);
    }

    public List<AgentDescriptor> listAvailable() {
        return agentRegistry.find(
                AgentQuery.bySlot(ReviewerDescriptorSeeder.SLOT,
                        ReviewerDescriptorSeeder.TENANCY_ID))
                .stream().map(AgentMatch::descriptor).toList();
    }

    public Optional<AgentDescriptor> findDescriptor(String agentId) {
        return agentRegistry.findById(agentId, ReviewerDescriptorSeeder.TENANCY_ID);
    }
}
