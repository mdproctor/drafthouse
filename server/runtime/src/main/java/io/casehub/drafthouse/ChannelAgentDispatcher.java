package io.casehub.drafthouse;

import io.casehub.blocks.channel.AgentTask;
import io.casehub.blocks.channel.ChannelAgentHandler;
import io.casehub.blocks.channel.ChannelAgentRequest;
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

@ApplicationScoped
public class ChannelAgentDispatcher extends io.casehub.blocks.channel.ChannelAgentDispatcher {

    static final String SUBAGENT_INSTANCE_ID = "drafthouse-subagent";

    private MessageService messageService;
    private InstanceService instanceService;

    ChannelAgentDispatcher() { this.messageService = null; this.instanceService = null; }

    @Inject
    ChannelAgentDispatcher(DebateAgentProvider debateAgentProvider,
                           MessageService messageService,
                           @Any Instance<ChannelAgentHandler> handlers,
                           InstanceService instanceService) {
        this(debateAgentProvider, messageService, (Iterable<ChannelAgentHandler>) handlers, instanceService);
    }

    ChannelAgentDispatcher(DebateAgentProvider debateAgentProvider,
                           MessageService messageService,
                           Iterable<ChannelAgentHandler> handlers,
                           InstanceService instanceService) {
        super(debateAgentProvider::analyse, messageService::dispatch, handlers, SUBAGENT_INSTANCE_ID);
        this.messageService = messageService;
        this.instanceService = instanceService;
    }

    @PostConstruct
    void registerSenderInstance() {
        if (instanceService == null) return;
        instanceService.register(SUBAGENT_INSTANCE_ID,
                "DraftHouse sub-agent (focused analysis)",
                List.of("document-debate-subagent"));
    }

    public void onChannelAgentRequest(@ObservesAsync ChannelAgentRequest request) {
        dispatch(request);
    }

    @Override
    protected void onError(ChannelAgentRequest request, String fixedReason) {
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
