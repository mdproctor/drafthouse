package io.casehub.drafthouse;

import io.casehub.qhorus.api.gateway.OutboundMessage;
import java.util.UUID;

public record ChannelAgentRequest(
        UUID channelId,
        String correlationId,     // subTaskId — ID of the triggering SUB_TASK_REQUEST message
        OutboundMessage message   // full trigger message; handlers parse META from message.content()
) {}
