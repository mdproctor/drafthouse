package io.casehub.drafthouse.handler;

import io.casehub.drafthouse.ChannelAgentHandler;
import io.casehub.drafthouse.ChannelAgentRequest;
import io.casehub.drafthouse.DebateSessionRegistry;
import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.debate.*;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

abstract class AbstractDebateSubAgentHandler implements ChannelAgentHandler {

    private static final Logger LOG = Logger.getLogger(AbstractDebateSubAgentHandler.class.getName());

    @Inject ProjectionService projectionService;
    @Inject DebateChannelProjection debateProjection;
    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;

    /** The SubTaskType this handler processes. */
    abstract SubTaskType taskType();

    @Override
    public final boolean handles(ChannelAgentRequest request) {
        Map<String, String> meta = DebateProtocol.parseMeta(request.message().content());
        try {
            return SubTaskType.valueOf(meta.getOrDefault("taskType", "")) == taskType();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public final MessageDispatch buildResponse(UUID channelId, String senderId,
                                               String llmOutput, ChannelAgentRequest trigger) {
        Map<String, String> meta = DebateProtocol.parseMeta(trigger.message().content());
        String subTaskId = meta.getOrDefault("subTaskId", trigger.correlationId());
        String agent = meta.getOrDefault("agent", "UNKNOWN");
        String pointId = meta.get("pointId");
        Long inReplyTo = messageService.findByCorrelationId(subTaskId).map(m -> m.id).orElse(null);
        String round = meta.getOrDefault("round", "0");
        String encoded = DebateProtocol.META_SENTINEL
                + "entryType=SUB_TASK_FINDING|subTaskId=" + subTaskId
                + "|taskType=" + taskType().name()
                + "|agent=" + agent
                + "|round=" + round
                + (pointId != null ? "|pointId=" + pointId : "")
                + "\n\n" + llmOutput;
        return MessageDispatch.builder()
                .channelId(channelId).sender(senderId)
                .type(MessageType.RESPONSE).content(encoded)
                .correlationId(subTaskId).inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT).build();
    }

    // ── shared helpers ────────────────────────────────────────────────────────

    protected ReviewState currentState(UUID channelId) {
        return projectionService.project(channelId, debateProjection).state();
    }

    protected DebateSession requireSession(UUID channelId) {
        return registry.find(channelId).orElseThrow(() ->
            new IllegalArgumentException("No active debate session for channel " + channelId));
    }

    protected String requireSpecPath(DebateSession session) {
        return session.primary()
                .orElseThrow(() -> new IllegalArgumentException(taskType()
                        + " requires a document in the working set — start_debate must receive a spec path"))
                .path();
    }

    protected ReviewPoint requirePoint(ReviewState state, String pointId) {
        if (pointId == null)
            throw new IllegalArgumentException(taskType() + " requires a pointId");
        ReviewPoint p = state.points().get(pointId);
        if (p == null)
            throw new IllegalArgumentException(taskType() + ": pointId " + pointId
                    + " not found in projected state");
        return p;
    }

    protected String requirePointRaiseContent(ReviewState state, String pointId) {
        return requirePoint(state, pointId).thread().get(0).content();
    }

    protected String readSpec(String specPath) {
        try { return Files.readString(Path.of(specPath)); }
        catch (IOException e) {
            LOG.warning("Could not read spec at " + specPath + ": " + e.getMessage());
            return "(spec file could not be read)";
        }
    }

    protected Map<String, String> metaFrom(ChannelAgentRequest request) {
        return DebateProtocol.parseMeta(request.message().content());
    }
}
