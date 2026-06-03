package io.casehub.drafthouse;

import java.util.Map;
import java.util.logging.Logger;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.message.MessageService;

public class ReviewerChannelBackend implements ChannelBackend {

    static final String BACKEND_ID = "drafthouse-reviewer";
    static final String BACKEND_TYPE = "agent";

    private static final Logger LOG = Logger.getLogger(ReviewerChannelBackend.class.getName());

    private final ReviewSession session;
    private final DataService dataService;
    private final MessageService messageService;
    private final DocumentReviewer llm;
    private final int maxDocChars;

    ReviewerChannelBackend(ReviewSession session, DataService dataService,
                           MessageService messageService, DocumentReviewer llm,
                           int maxDocChars) {
        this.session = session;
        this.dataService = dataService;
        this.messageService = messageService;
        this.llm = llm;
        this.maxDocChars = maxDocChars;
    }

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}

    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        if (message.type() != MessageType.QUERY) return;

        Long inReplyTo = messageService
                .findByCorrelationId(message.correlationId().toString())
                .map(m -> m.id)
                .orElse(null);
        if (inReplyTo == null) {
            LOG.warning("Could not resolve inReplyTo for correlationId " + message.correlationId()
                    + " on channel " + channel.name() + " — skipping dispatch");
            return;
        }

        String docA = dataService.getByKey(session.docAKey()).map(d -> d.content).orElse("");
        String docB = dataService.getByKey(session.docBKey()).map(d -> d.content).orElse("");

        if (docA.length() > maxDocChars || docB.length() > maxDocChars) {
            dispatch(channel, message, inReplyTo,
                    ReviewResult.decline("Documents exceed the maximum size for review."));
            return;
        }

        String selectionContext = buildSelectionContext(session);

        ReviewResult result;
        try {
            result = llm.review(session.personality(), docA, docB, selectionContext, message.content());
        } catch (Exception e) {
            result = ReviewResult.decline("Reviewer encountered an error.");
        }

        dispatch(channel, message, inReplyTo, result);
    }

    private void dispatch(ChannelRef channel, OutboundMessage message, Long inReplyTo, ReviewResult result) {
        MessageType type = result.declined() ? MessageType.DECLINE : MessageType.RESPONSE;
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
        if (session.selectionSide() == null || session.selectionText() == null) return "";
        return "Selected text (Document " + session.selectionSide().name() + "): "
                + session.selectionText();
    }
}
