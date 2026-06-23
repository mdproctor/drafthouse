package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentRegistry;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Seeds 4 reviewer AgentDescriptors into the registry at startup.
 * All reviewers share the same tenancy and slot but differ in personality traits.
 */
@Startup
@ApplicationScoped
public class ReviewerDescriptorSeeder {

    public static final String TENANCY_ID = "drafthouse";
    public static final String DEFAULT_REVIEWER_ID = "drafthouse-structural-reviewer";

    private final AgentRegistry registry;

    @Inject
    ReviewerDescriptorSeeder(AgentRegistry registry) {
        this.registry = registry;
    }

    @Startup
    void seed() {
        registry.register(structuralReviewer());
        registry.register(contentReviewer());
        registry.register(readabilityReviewer());
        registry.register(completenessReviewer());
    }

    private AgentDescriptor structuralReviewer() {
        return AgentDescriptor.builder()
                .agentId("drafthouse-structural-reviewer")
                .name("Structural Reviewer")
                .slot("document-reviewer")
                .disposition(AgentDisposition.builder()
                        .conflictMode("collaborative")
                        .ruleFollowing("strict")
                        .build())
                .briefing("You review documents for structural integrity. Focus on gaps, contradictions, missing sections, logical flow, and internal consistency. Flag where the argument breaks down or where sections don't connect.")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review")
                        .tags(List.of("structural"))
                        .build()))
                .tenancyId(TENANCY_ID)
                .build();
    }

    private AgentDescriptor contentReviewer() {
        return AgentDescriptor.builder()
                .agentId("drafthouse-content-reviewer")
                .name("Content Reviewer")
                .slot("document-reviewer")
                .disposition(AgentDisposition.builder()
                        .conflictMode("competing")
                        .riskAppetite("cautious")
                        .build())
                .briefing("You review content accuracy and depth. Challenge claims that lack evidence, flag overgeneralizations, and identify where details are missing or vague. Push for precision and substantiation.")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review")
                        .tags(List.of("content"))
                        .build()))
                .tenancyId(TENANCY_ID)
                .build();
    }

    private AgentDescriptor readabilityReviewer() {
        return AgentDescriptor.builder()
                .agentId("drafthouse-readability-reviewer")
                .name("Readability Reviewer")
                .slot("document-reviewer")
                .disposition(AgentDisposition.builder()
                        .conflictMode("accommodating")
                        .autonomy("directed")
                        .build())
                .briefing("You review for clarity and readability. Flag dense prose, jargon without explanation, unclear pronouns, ambiguous phrasing, and convoluted sentences. Suggest where simpler wording helps.")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review")
                        .tags(List.of("readability"))
                        .build()))
                .tenancyId(TENANCY_ID)
                .build();
    }

    private AgentDescriptor completenessReviewer() {
        return AgentDescriptor.builder()
                .agentId("drafthouse-completeness-reviewer")
                .name("Completeness Reviewer")
                .slot("document-reviewer")
                .disposition(AgentDisposition.builder()
                        .conflictMode("collaborative")
                        .ruleFollowing("strict")
                        .build())
                .briefing("You review for coverage and thoroughness. Identify missing edge cases, unaddressed alternatives, glossed-over implications, and sections that promise more than they deliver.")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review")
                        .tags(List.of("completeness"))
                        .build()))
                .tenancyId(TENANCY_ID)
                .build();
    }
}
