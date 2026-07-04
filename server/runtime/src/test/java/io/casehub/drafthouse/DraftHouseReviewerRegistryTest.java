package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DraftHouseReviewerRegistryTest {

    private DraftHouseReviewerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DraftHouseReviewerRegistry();
    }

    @Test
    void register_and_findById_roundTrips() {
        var descriptor = testDescriptor("test-agent");
        registry.register(descriptor);
        assertThat(registry.findById("test-agent", "drafthouse"))
                .isPresent()
                .get().extracting(AgentDescriptor::name).isEqualTo("Test Agent");
    }

    @Test
    void findById_wrongTenancy_returnsEmpty() {
        registry.register(testDescriptor("test-agent"));
        assertThat(registry.findById("test-agent", "other-tenancy")).isEmpty();
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertThat(registry.findById("no-such-agent", "drafthouse")).isEmpty();
    }

    @Test
    void find_bySlot_returnsMatchingDescriptors() {
        registry.register(testDescriptor("a1"));
        registry.register(AgentDescriptor.builder()
                .agentId("a2").name("Other").slot("other-slot").tenancyId("drafthouse").build());

        var results = registry.find(AgentQuery.bySlot("document-reviewer", "drafthouse"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).descriptor().agentId()).isEqualTo("a1");
    }

    @Test
    void find_byCapability_returnsMatchingDescriptors() {
        registry.register(testDescriptor("a1"));
        var results = registry.find(AgentQuery.byCapability("document-review", "drafthouse"));
        assertThat(results).hasSize(1);
    }

    @Test
    void register_sameId_overwrites() {
        registry.register(testDescriptor("a1"));
        var updated = AgentDescriptor.builder()
                .agentId("a1").name("Updated").slot("document-reviewer").tenancyId("drafthouse").build();
        registry.register(updated);
        assertThat(registry.findById("a1", "drafthouse").get().name()).isEqualTo("Updated");
    }

    private static AgentDescriptor testDescriptor(String agentId) {
        return AgentDescriptor.builder()
                .agentId(agentId)
                .name("Test Agent")
                .slot("document-reviewer")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review").tags(List.of("structural")).build()))
                .disposition(AgentDisposition.builder()
                        .conflictMode("collaborative").ruleFollowing("strict").build())
                .tenancyId("drafthouse")
                .build();
    }
}
