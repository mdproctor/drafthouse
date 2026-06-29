package io.casehub.drafthouse;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import io.casehub.blocks.conversation.ConversationState;
import io.casehub.drafthouse.debate.ReviewConversationRenderer;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;

public class ReviewerChannelBackend implements ChannelBackend {

    static final String BACKEND_ID = "drafthouse-reviewer";
    static final String BACKEND_TYPE = "agent";

    private static final Logger LOG = Logger.getLogger(ReviewerChannelBackend.class.getName());

    private final ReviewSessionRegistry registry;
    private final UUID channelId;
    private final MessageService messageService;
    private final DocumentReviewer llm;
    private final int maxDocChars;
    private final ProjectionService projectionService;
    private final ChannelProjection<ConversationState> projection;
    private final ReviewConversationRenderer conversationRenderer = new ReviewConversationRenderer();

    ReviewerChannelBackend(ReviewSessionRegistry registry, UUID channelId,
                           MessageService messageService, DocumentReviewer llm,
                           int maxDocChars, ProjectionService projectionService,
                           ChannelProjection<ConversationState> projection) {
        this.registry = registry;
        this.channelId = channelId;
        this.messageService = messageService;
        this.llm = llm;
        this.maxDocChars = maxDocChars;
        this.projectionService = projectionService;
        this.projection = projection;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        if (message.type() != MessageType.QUERY) return;

        ReviewSession session = registry.find(channelId).orElse(null);
        if (session == null) {
            LOG.warning("ReviewerChannelBackend.post() called but session not found for channel "
                    + channelId + " — session may have ended");
            return;
        }

        Long inReplyTo = messageService
                .findByCorrelationId(message.correlationId().toString())
                .map(m -> m.id)
                .orElse(null);
        if (inReplyTo == null) {
            LOG.warning("Could not resolve inReplyTo for correlationId " + message.correlationId()
                    + " on channel " + channel.name() + " — skipping dispatch");
            return;
        }

        if (session.docAContent().length() > maxDocChars
                || session.docBContent().length() > maxDocChars) {
            dispatch(channel, message, inReplyTo, session,
                    ReviewResult.decline("Documents exceed the maximum size for review."));
            return;
        }

        String selectionContext = buildSelectionContext(session);

        ReviewResult result;
        try {
            var historyResult = projectionService.project(channelId, projection);
            String reviewHistory = conversationRenderer.render(historyResult.state());
            result = llm.review(session.reviewer().instructions(), session.docAContent(),
                    session.docBContent(), selectionContext, reviewHistory, message.content());
        } catch (Exception e) {
            LOG.warning("Backend error on channel " + channel.name() + ": " + e.getMessage());
            result = ReviewResult.decline("Reviewer encountered an error.");
        }
        dispatch(channel, message, inReplyTo, session, result);
    }

    private void dispatch(ChannelRef channel, OutboundMessage message,
                          Long inReplyTo, ReviewSession session, ReviewResult result) {
        MessageType type = switch (result.outcome()) {
            case AGREE   -> MessageType.DONE;
            case QUALIFY -> MessageType.RESPONSE;
            case DECLINE -> MessageType.DECLINE;
        };
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channel.id())
                .sender(session.instanceId())
                .type(type)
                .content(result.content())
                .inReplyTo(inReplyTo)
                .correlationId(message.correlationId().toString())
                .actorType(ActorType.AGENT)
                .build());
    }

    private static String buildSelectionContext(ReviewSession session) {
        if (session.selection() == null) return "";
        return "Selected text (Document " + session.selection().side().name() + "): "
                + session.selection().selectedText();
    }
}
