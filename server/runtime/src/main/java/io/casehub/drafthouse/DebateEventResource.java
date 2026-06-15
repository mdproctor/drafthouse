package io.casehub.drafthouse;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
@Path("/api/debate")
public class DebateEventResource {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DebateEventResource.class.getName());

    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;
    @Inject DraftHouseConfig config;
    @Inject ObjectMapper mapper;

    private final ConcurrentHashMap<UUID, String> pendingContextSnapshots = new ConcurrentHashMap<>();

    record SessionInfo(String debateSessionId, String channelName, String specPath) {}

    public void pushContextSnapshot(UUID channelId, ContextSnapshot snapshot) {
        try {
            String json = serializeContextSnapshot(snapshot, false);
            if (json != null) {
                pendingContextSnapshots.put(channelId, json);
            }
        } catch (Exception e) {
            LOG.warning("Failed to serialize context snapshot: " + e.getMessage());
        }
    }

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<SessionInfo> activeSessions() {
        return registry.activeSessions().stream()
                .map(s -> new SessionInfo(s.debateSessionId(), s.channelName(), s.specPath()))
                .toList();
    }

    @GET
    @Path("/{debateSessionId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @io.smallrye.common.annotation.Blocking
    public Multi<String> events(@PathParam("debateSessionId") String debateSessionId) {
        UUID channelId;
        try {
            channelId = UUID.fromString(debateSessionId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Invalid session id: " + debateSessionId);
        }

        DebateSession session = registry.find(channelId).orElse(null);
        if (session == null) {
            throw new NotFoundException("No active debate session: " + debateSessionId);
        }

        AtomicLong lastSentId = new AtomicLong(0L);

        Multi<String> initialContext = Multi.createFrom().item(
                () -> serializeContextSnapshot(
                        session.contextTracker().snapshot(
                                config.context().windowSizeChars(),
                                config.context().thresholdPercent()),
                        true)
        ).filter(Objects::nonNull);

        Multi<String> catchUp = Multi.createFrom().uni(
                Uni.createFrom().item(() -> {
                    List<Message> messages = messageService.pollAfter(channelId, 0L, 500);
                    return serializeMessages(messages, lastSentId);
                })
        ).filter(Objects::nonNull);

        Multi<String> live = Multi.createFrom().ticks().every(Duration.ofMillis(500))
                .onItem().transformToMultiAndConcatenate(tick -> {
                    String pendingCtx = pendingContextSnapshots.remove(channelId);
                    String entries;
                    try {
                        List<Message> messages = messageService.pollAfter(
                                channelId, lastSentId.get(), 50);
                        entries = messages.isEmpty() ? "{\"type\":\"heartbeat\"}"
                                : serializeMessages(messages, lastSentId);
                    } catch (Exception e) {
                        LOG.warning("SSE tick failed for " + debateSessionId + ": " + e.getMessage());
                        entries = "{\"type\":\"heartbeat\"}";
                    }
                    if (pendingCtx != null && entries != null) {
                        return Multi.createFrom().items(pendingCtx, entries);
                    } else if (pendingCtx != null) {
                        return Multi.createFrom().item(pendingCtx);
                    } else if (entries != null) {
                        return Multi.createFrom().item(entries);
                    } else {
                        return Multi.createFrom().item("{\"type\":\"heartbeat\"}");
                    }
                });

        return Multi.createBy().concatenating().streams(initialContext, catchUp, live);
    }

    private String serializeMessages(List<Message> messages, AtomicLong lastSentId) {
        List<DebateStreamEntry> entries = messages.stream()
                .map(DebateStreamEntry::from)
                .filter(Objects::nonNull)
                .toList();

        if (!messages.isEmpty()) {
            long maxId = messages.stream()
                    .mapToLong(m -> m.id)
                    .max()
                    .orElse(lastSentId.get());
            lastSentId.set(maxId);
        }

        if (entries.isEmpty()) return null;

        try {
            return mapper.writeValueAsString(entries);
        } catch (Exception e) {
            LOG.warning("Failed to serialize debate events: " + e.getMessage());
            return null;
        }
    }

    private String serializeContextSnapshot(ContextSnapshot snapshot, boolean includeWindowSize) {
        try {
            StringBuilder sb = new StringBuilder("{\"type\":\"context-usage\"");
            sb.append(",\"serverContributionChars\":").append(snapshot.serverContributionChars());
            if (includeWindowSize) {
                sb.append(",\"windowSizeChars\":").append(snapshot.windowSizeChars());
            }
            if (snapshot.agentReportedPercent() != null) {
                sb.append(",\"agentReportedPercent\":").append(snapshot.agentReportedPercent());
            } else {
                sb.append(",\"agentReportedPercent\":null");
            }
            sb.append(",\"effectivePercent\":").append(snapshot.effectivePercent());
            sb.append(",\"messageCount\":").append(snapshot.messageCount());
            sb.append(",\"thresholdExceeded\":").append(snapshot.thresholdExceeded());
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            LOG.warning("Failed to build context snapshot JSON: " + e.getMessage());
            return null;
        }
    }
}
