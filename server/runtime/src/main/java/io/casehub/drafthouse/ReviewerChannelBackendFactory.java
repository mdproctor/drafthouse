package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.message.MessageService;

/**
 * Registers a ReviewerChannelBackend with the Qhorus gateway when a drafthouse channel
 * is initialised. Session storage is delegated to the injected ReviewSessionRegistry.
 */
@ApplicationScoped
public class ReviewerChannelBackendFactory {

    @Inject ReviewSessionRegistry registry;
    @Inject ChannelGateway gateway;
    @Inject MessageService messageService;
    @Inject DocumentReviewer llm;
    @Inject DraftHouseConfig config;

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/")) return;
        if (registry.find(event.channelId()).isEmpty()) return;
        ReviewerChannelBackend backend = new ReviewerChannelBackend(
                registry, event.channelId(), messageService, llm, config.reviewer().maxDocChars());
        gateway.deregisterBackend(event.channelId(), ReviewerChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), backend, ReviewerChannelBackend.BACKEND_TYPE);
    }
}
