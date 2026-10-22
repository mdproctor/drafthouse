package io.casehub.drafthouse;

import io.casehub.agency.channel.ChannelAgentRequest;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ChannelBackend for debate channels.
 *
 * Fence role (retained): prevents ReviewerChannelBackendFactory from attaching an LLM backend
 * to debate channels.
 *
 * Dispatch role (added): fires ChannelAgentRequest CDI event when a SUB_TASK_REQUEST message
 * arrives — all other message types remain no-ops.
 *
 * Production: CDI uses the no-arg constructor + field injection.
 * Tests: use the package-private two-arg constructor to pass mocks directly.
 */
@ApplicationScoped
public class DebateChannelBackend implements ChannelBackend {

    static final String BACKEND_ID   = "drafthouse-debate";
    static final String BACKEND_TYPE = "agent";

    private static final Logger LOG = Logger.getLogger(DebateChannelBackend.class.getName());

    @Inject Event<ChannelAgentRequest> channelAgentEvent;
    @Inject DebateSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;

    /** CDI no-arg constructor. */
    public DebateChannelBackend() {}

    /** Test constructor — pass mocks directly. */
    DebateChannelBackend(Event<ChannelAgentRequest> channelAgentEvent,
                         DebateSessionRegistry registry,
                         WebSocketEventBus eventBus) {
        this.channelAgentEvent = channelAgentEvent;
        this.registry = registry;
        this.eventBus = eventBus;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        Map<String, String> meta = DebateProtocol.parseMeta(message.content());

        // Thread messages → thread event path
        if (meta.containsKey("threadId")) {
            io.casehub.drafthouse.debate.ThreadStreamEntry threadEntry =
                    io.casehub.drafthouse.debate.ThreadStreamEntry.from(message);
            if (threadEntry != null) {
                eventBus.pushThreadEntries(channel.id(), java.util.List.of(threadEntry));
                String action = meta.get("threadAction");
                if ("START".equals(action)) {
                    var anchorMap = threadEntry.anchor() != null
                                    ? java.util.Map.of(
                            "side", threadEntry.anchor().side(),
                            "startLine", threadEntry.anchor().startLine(),
                            "endLine", threadEntry.anchor().endLine(),
                            "selectedText", threadEntry.anchor().selectedText())
                                    : java.util.Map.<String, Object>of();
                    eventBus.pushMetadata(channel.id(), "thread-created",
                                          java.util.Map.of("threadId", meta.get("threadId"),
                                                           "anchor", anchorMap,
                                                           "createdBy", threadEntry.agentRole() != null ? threadEntry.agentRole() : ""));
                } else if ("RESOLVE".equals(action)) {
                    eventBus.pushMetadata(channel.id(), "thread-resolved",
                                          java.util.Map.of("threadId", meta.get("threadId")));
                }
            }
            return;
        }

        // Existing debate entry path
        io.casehub.drafthouse.debate.DebateStreamEntry entry =
                io.casehub.drafthouse.debate.DebateStreamEntry.from(message);
        if (entry != null) {
            eventBus.pushDebateEntries(channel.id(), java.util.List.of(entry));
        }

        // SUB_TASK_REQUEST dispatch
        if ("SUB_TASK_REQUEST".equals(meta.get("entryType"))) {
            DebateSession session = registry.find(channel.id()).orElse(null);
            if (session != null) {
                String correlationId = message.correlationId() != null
                                       ? message.correlationId() : UUID.randomUUID().toString();
                channelAgentEvent.fireAsync(new ChannelAgentRequest(
                        channel.id(), correlationId, message, null));
            } else {
                LOG.warning("DebateChannelBackend: SUB_TASK_REQUEST on " + channel.id()
                            + " — no active session, dropped");
            }
            return;
        }

        // Autonomous trigger — check on every non-SUB_TASK, non-thread message
        DebateSession session = registry.find(channel.id()).orElse(null);
        if (session == null || !session.isAutonomous()) {return;}

        // FLAG_HUMAN from external source → terminate running orchestrator
        if ("FLAG_HUMAN".equals(meta.get("entryType"))
            && session.orchestrator() != null) {
            session.orchestrator().terminate();
            return;
        }

        // First qualifying message → start converse() on virtual thread
        if (session.orchestrator() != null && session.markConverseStarted()) {
            io.casehub.qhorus.api.message.MessageView triggeringMessage = new io.casehub.qhorus.api.message.MessageView(
                    null, channel.id(), message.sender(), message.type(),
                    message.content(), message.payload(), message.correlationId(), message.inReplyTo(),
                    message.target(), message.topic(), message.artefactRefs(),
                    message.senderActorType(), java.time.Instant.now(), null, 0);

            Thread.startVirtualThread(() -> {
                try {
                    var outcome = session.orchestrator()
                                         .converse(triggeringMessage)
                                         .await().indefinitely();
                    handleCompletion(channel.id(), session, outcome);
                } catch (Exception e) {
                    LOG.warning("Autonomous debate failed on " + channel.id() + ": " + e.getMessage());
                    handleFailure(channel.id(), session, e);
                }
            });
        }
    }

    private void handleCompletion(UUID channelId, DebateSession session,
                                  io.casehub.agency.conversation.orchestration.ConversationOutcome outcome) {
        String reason = switch (outcome.terminationDecision()) {
            case io.casehub.agency.agentic.termination.TerminationDecision.Complete c -> c.result() != null ? c.result().toString() : "completed";
            case io.casehub.agency.agentic.termination.TerminationDecision.Escalate e -> "escalated: " + e.reason();
            case io.casehub.agency.agentic.termination.TerminationDecision.Failed f -> "failed: " + f.reason();
            default -> "unknown";
        };
        eventBus.pushMetadata(channelId, "autonomous-completed",
                              java.util.Map.of("reason", reason,
                                               "dispatchCount", outcome.dispatchCount(),
                                               "durationMs", outcome.elapsed().toMillis()));
        session.setOrchestrator(null);
    }

    private void handleFailure(UUID channelId, DebateSession session, Exception e) {
        eventBus.pushMetadata(channelId, "autonomous-failed",
                              java.util.Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        session.setOrchestrator(null);
    }

}
