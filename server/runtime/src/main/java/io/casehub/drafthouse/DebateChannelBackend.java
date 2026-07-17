package io.casehub.drafthouse;

import io.casehub.blocks.channel.ChannelAgentRequest;
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
        // Push all message types to WebSocket watchers
        io.casehub.drafthouse.debate.DebateStreamEntry entry =
                io.casehub.drafthouse.debate.DebateStreamEntry.from(message);
        if (entry != null) {
            eventBus.pushDebateEntries(channel.id(), java.util.List.of(entry));
        }

        // Still fire CDI event for SUB_TASK_REQUEST (agent dispatch — orthogonal)
        Map<String, String> meta = DebateProtocol.parseMeta(message.content());
        if (!"SUB_TASK_REQUEST".equals(meta.get("entryType"))) return;

        DebateSession session = registry.find(channel.id()).orElse(null);
        if (session == null) {
            LOG.warning("DebateChannelBackend: SUB_TASK_REQUEST on " + channel.id()
                    + " — no active session, dropped");
            return;
        }

        String correlationId = message.correlationId() != null
                ? message.correlationId() : UUID.randomUUID().toString();
        channelAgentEvent.fireAsync(new ChannelAgentRequest(channel.id(), correlationId, message, null));
    }
}
