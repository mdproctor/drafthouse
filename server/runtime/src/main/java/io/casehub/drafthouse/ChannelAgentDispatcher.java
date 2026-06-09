package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * CDI event observer that dispatches ChannelAgentRequest events to the matching
 * ChannelAgentHandler, invokes the DebateAgentProvider, and posts the result.
 *
 * Production: instantiated by CDI via the @Inject constructor.
 * Tests: use the package-private Iterable constructor directly (pass null for instanceService;
 * @PostConstruct is never called in tests so the null is never dereferenced).
 */
@ApplicationScoped
public class ChannelAgentDispatcher {

    static final String SUBAGENT_INSTANCE_ID = "drafthouse-subagent";
    private static final Logger LOG = Logger.getLogger(ChannelAgentDispatcher.class.getName());

    private final DebateAgentProvider debateAgentProvider;
    private final MessageService messageService;
    private final Iterable<ChannelAgentHandler> handlers;
    private final InstanceService instanceService;

    /** CDI constructor — all dependencies injected. */
    @Inject
    ChannelAgentDispatcher(DebateAgentProvider debateAgentProvider,
                           MessageService messageService,
                           @Any Instance<ChannelAgentHandler> handlers,
                           InstanceService instanceService) {
        this(debateAgentProvider, messageService, (Iterable<ChannelAgentHandler>) handlers, instanceService);
    }

    /** Test constructor — instanceService may be null (PostConstruct not called in tests). */
    ChannelAgentDispatcher(DebateAgentProvider debateAgentProvider,
                           MessageService messageService,
                           Iterable<ChannelAgentHandler> handlers,
                           InstanceService instanceService) {
        this.debateAgentProvider = debateAgentProvider;
        this.messageService = messageService;
        this.handlers = handlers;
        this.instanceService = instanceService;
    }

    @PostConstruct
    void registerSenderInstance() {
        if (instanceService == null) return; // test constructor path — @PostConstruct never called by CDI
        // InstanceService.register() is an upsert — idempotent on restart, no prior deregister needed
        instanceService.register(SUBAGENT_INSTANCE_ID,
                "DraftHouse sub-agent (focused analysis)",
                List.of("document-debate-subagent"));
    }

    public void onChannelAgentRequest(@ObservesAsync ChannelAgentRequest request) {
        ChannelAgentHandler handler = null;
        for (ChannelAgentHandler h : handlers) {
            if (h.handles(request)) {
                handler = h;
                break;
            }
        }

        if (handler == null) {
            LOG.warning("ChannelAgentDispatcher: no handler matched on channel "
                    + request.channelId() + " — dispatching error");
            dispatchError(request, "No handler matched this sub-task request.");
            return;
        }

        try {
            AgentTask task = handler.prepareTask(request);
            String llmOutput = debateAgentProvider.analyse(task);
            try {
                MessageDispatch response = handler.buildResponse(
                        request.channelId(), SUBAGENT_INSTANCE_ID, llmOutput, request);
                messageService.dispatch(response);
            } catch (AgentResultParseException e) {
                LOG.warning("ChannelAgentDispatcher: parse failure [" + request.correlationId()
                        + "]: " + e.getClass().getSimpleName() + " — " + e.getMessage());
                dispatchError(request, "Sub-agent returned an unreadable result.");
            }
        } catch (Exception e) {
            LOG.warning("ChannelAgentDispatcher: sub-agent failed [" + request.correlationId()
                    + "]: " + e.getClass().getSimpleName() + " — " + e.getMessage());
            dispatchError(request, "Sub-agent analysis failed.");
        }
    }

    // NEVER pass exception messages — see qhorus-dispatch-exception-sanitization.md
    private void dispatchError(ChannelAgentRequest request, String fixedReason) {
        Map<String, String> meta = DebateProtocol.parseMeta(request.message().content());
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_ERROR|subTaskId=" + request.correlationId()
                + "|taskType=" + meta.getOrDefault("taskType", "UNKNOWN")
                + "|agent=" + meta.getOrDefault("agent", "UNKNOWN")
                + "\n\n" + fixedReason;
        Long inReplyTo = messageService.findByCorrelationId(request.correlationId())
                .map(m -> m.id)
                .orElse(null);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(request.channelId())
                .sender(SUBAGENT_INSTANCE_ID)
                .type(MessageType.STATUS)
                .content(encoded)
                .correlationId(request.correlationId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());
    }
}
