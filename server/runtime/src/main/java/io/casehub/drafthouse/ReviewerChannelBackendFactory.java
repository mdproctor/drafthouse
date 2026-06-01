package io.casehub.drafthouse;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;

@ApplicationScoped
public class ReviewerChannelBackendFactory implements ReviewSessionRegistry {

    @Inject ChannelGateway gateway;
    @Inject DataService dataService;
    @Inject MessageService messageService;
    @Inject DocumentReviewer llm;
    @Inject DraftHouseConfig config;

    private final ConcurrentHashMap<UUID, ReviewSession> sessions = new ConcurrentHashMap<>();

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/")) return;
        ReviewSession session = sessions.get(event.channelId());
        if (session == null) return;
        ReviewerChannelBackend backend = new ReviewerChannelBackend(
                session, dataService, messageService, llm, config.maxDocChars());
        gateway.deregisterBackend(event.channelId(), ReviewerChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), backend, ReviewerChannelBackend.BACKEND_TYPE);
    }

    @Override
    public Optional<ReviewSession> find(UUID channelId) {
        return Optional.ofNullable(sessions.get(channelId));
    }

    @Override
    public void put(ReviewSession session) {
        sessions.put(session.channelId(), session);
    }

    @Override
    public void remove(UUID channelId) {
        sessions.remove(channelId);
    }

    @Override
    public void updateSelection(UUID channelId, DocumentSide side, String text) {
        sessions.computeIfPresent(channelId, (id, s) -> s.withSelection(side, text));
    }
}
