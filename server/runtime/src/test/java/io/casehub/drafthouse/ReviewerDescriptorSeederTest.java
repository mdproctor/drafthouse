package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReviewerDescriptorSeederTest {

    @Test
    void seed_registers4Descriptors_intoRegistry() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();

        var reviewers = registry.find(
                AgentQuery.bySlot("document-reviewer", ReviewerDescriptorSeeder.TENANCY_ID));
        assertThat(reviewers).hasSize(4);
        assertThat(reviewers).extracting("agentId").containsExactlyInAnyOrder(
                "drafthouse-structural-reviewer",
                "drafthouse-content-reviewer",
                "drafthouse-readability-reviewer",
                "drafthouse-completeness-reviewer");
    }

    @Test
    void seed_structuralReviewer_hasCorrectDisposition() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();

        var descriptor = registry.findById("drafthouse-structural-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID).orElseThrow();
        assertThat(descriptor.name()).isEqualTo("Structural Reviewer");
        assertThat(descriptor.slot()).isEqualTo("document-reviewer");
        assertThat(descriptor.disposition().conflictMode()).isEqualTo("collaborative");
        assertThat(descriptor.disposition().ruleFollowing()).isEqualTo("strict");
        assertThat(descriptor.briefing()).isNotBlank();
        assertThat(descriptor.capabilities()).hasSize(1);
        assertThat(descriptor.capabilities().get(0).name()).isEqualTo("document-review");
    }

    @Test
    void seed_contentReviewer_hasCompetingConflictMode() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();

        var descriptor = registry.findById("drafthouse-content-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID).orElseThrow();
        assertThat(descriptor.disposition().conflictMode()).isEqualTo("competing");
        assertThat(descriptor.disposition().riskAppetite()).isEqualTo("cautious");
    }

    @Test
    void seed_isIdempotent() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();
        seeder.seed();

        var reviewers = registry.find(
                AgentQuery.bySlot("document-reviewer", ReviewerDescriptorSeeder.TENANCY_ID));
        assertThat(reviewers).hasSize(4);
    }

    @Test
    void defaultReviewerId_isStructural() {
        assertThat(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID)
                .isEqualTo("drafthouse-structural-reviewer");
    }
}
