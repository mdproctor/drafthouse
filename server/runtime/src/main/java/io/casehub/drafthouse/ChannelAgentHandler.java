package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.qhorus.api.message.MessageDispatch;
import java.util.UUID;

/**
 * SPI for channel-reactive agent handlers.
 * Handlers must have non-overlapping handles() predicates — first-match routing.
 * Will be extracted to the patterns repo when a second application (devtown) adopts this pattern.
 */
public interface ChannelAgentHandler {

    /** Return true if this handler should process the request. */
    boolean handles(ChannelAgentRequest request);

    /**
     * Assemble focused LLM input. Deliberately minimal — no extraneous context.
     * @throws IllegalArgumentException if required inputs are absent.
     */
    AgentTask prepareTask(ChannelAgentRequest request);

    /**
     * Build the Qhorus MessageDispatch from the LLM output.
     * @throws AgentResultParseException if LLM output cannot be parsed.
     */
    MessageDispatch buildResponse(UUID channelId, String senderId,
                                  String llmOutput, ChannelAgentRequest trigger)
            throws AgentResultParseException;
}
