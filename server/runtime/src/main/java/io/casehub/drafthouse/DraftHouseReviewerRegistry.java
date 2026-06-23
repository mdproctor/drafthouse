package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class DraftHouseReviewerRegistry implements AgentRegistry {

    private final ConcurrentHashMap<String, AgentDescriptor> store = new ConcurrentHashMap<>();

    @Override
    public void register(AgentDescriptor descriptor) {
        store.put(descriptor.agentId(), descriptor);
    }

    @Override
    public Optional<AgentDescriptor> findById(String agentId, String tenancyId) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        return Optional.ofNullable(store.get(agentId))
                .filter(d -> d.tenancyId().equals(tenancyId));
    }

    @Override
    public List<AgentDescriptor> find(AgentQuery query) {
        return store.values().stream()
                .filter(d -> d.tenancyId().equals(query.tenancyId()))
                .filter(d -> query.slot() == null || Objects.equals(d.slot(), query.slot()))
                .filter(d -> query.capabilityName() == null
                        || d.capabilities().stream().anyMatch(c -> Objects.equals(c.name(), query.capabilityName())))
                .toList();
    }
}
