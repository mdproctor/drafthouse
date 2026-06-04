package io.casehub.drafthouse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * MCP tool surface for DraftHouse document review sessions.
 *
 * File path policy: docAPath and docBPath are read from the local filesystem with no
 * path restriction — DraftHouse is a local-only tool and this is intentional, consistent
 * with FileResource. Harden with a base-directory restriction before any networked
 * deployment.
 *
 * Session handle: the sessionId returned by start_review is channel.id.toString().
 * All subsequent calls parse it as UUID for O(1) registry lookup.
 */
@ApplicationScoped
public class DraftHouseMcpTools {

    static final String HUMAN_INSTANCE_ID = "drafthouse-human";

    private static final Logger LOG = Logger.getLogger(DraftHouseMcpTools.class.getName());

    @Inject ChannelService channelService;
    @Inject ChannelGateway channelGateway;
    @Inject InstanceService instanceService;
    @Inject MessageService messageService;
    @Inject ReviewSessionRegistry registry;
    @Inject DraftHouseConfig config;

    @PostConstruct
    void registerHumanInstance() {
        instanceService.register(HUMAN_INSTANCE_ID, "DraftHouse human reviewer",
                List.of("document-review-human"));
    }

    @Tool(name = "start_review",
          description = "Start a document review session. Returns JSON with sessionId (use for all subsequent calls) and channel name.")
    public String startReview(
            @ToolArg(description = "Absolute path to document A (the 'before' version)") String docAPath,
            @ToolArg(description = "Absolute path to document B (the 'after' version)") String docBPath) {

        String docAContent = readFile(docAPath);
        if (docAContent == null) return "error: could not read document A: " + docAPath;

        String docBContent = readFile(docBPath);
        if (docBContent == null) return "error: could not read document B: " + docBPath;

        if (docAContent.length() > config.maxDocChars()) {
            return "error: document A exceeds maximum size of " + config.maxDocChars() + " characters";
        }
        if (docBContent.length() > config.maxDocChars()) {
            return "error: document B exceeds maximum size of " + config.maxDocChars() + " characters";
        }

        String channelSlug = UUID.randomUUID().toString();
        String channelName = "drafthouse/" + channelSlug;

        Channel channel = null;
        try {
            channel = channelService.create(channelName, "DraftHouse review session",
                    ChannelSemantic.APPEND, null);

            String sessionId = channel.id.toString();
            // Use channel.name from the returned Channel — Qhorus is the canonical owner of the name.
            String resolvedChannelName = channel.name;
            String instanceId = "drafthouse-reviewer-" + sessionId;
            instanceService.register(instanceId, "DraftHouse reviewer " + sessionId,
                    List.of("document-review"));

            ReviewSession session = new ReviewSession(
                    channel.id, sessionId, resolvedChannelName, instanceId,
                    docAContent, docBContent, null, null, config.personality());

            // MUST put session in registry before initChannel — onChannelInitialised()
            // reads from the registry synchronously during the CDI event.
            registry.put(session);
            channelGateway.initChannel(channel.id, new ChannelRef(channel.id, resolvedChannelName));

            return "{\"sessionId\":\"" + sessionId + "\",\"channel\":\"" + resolvedChannelName + "\"}";

        } catch (Exception e) {
            LOG.warning("start_review failed: " + e.getMessage() + " — attempting cleanup");
            if (channel != null) {
                try { registry.remove(channel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                try { channelService.delete(channelName, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
            }
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "update_selection",
          description = "Update the selected text in the review session. Pass null for side and selectedText to clear the selection.")
    public String updateSelection(
            @ToolArg(description = "sessionId returned by start_review") String sessionId,
            @ToolArg(description = "Document side: 'A' or 'B'. Null to clear selection.") String side,
            @ToolArg(description = "Selected text. Null to clear selection.") String selectedText) {

        UUID channelId;
        try {
            channelId = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid sessionId format: " + sessionId;
        }

        if (registry.find(channelId).isEmpty()) {
            return "error: no active session for sessionId: " + sessionId;
        }

        DocumentSide docSide;
        if (side == null) {
            docSide = null;
        } else {
            try {
                docSide = DocumentSide.valueOf(side);
            } catch (IllegalArgumentException e) {
                return "error: invalid side value '" + side + "' — must be 'A' or 'B'";
            }
        }

        if ((docSide == null) != (selectedText == null)) {
            return "error: side and selectedText must both be provided or both be null";
        }

        registry.updateSelection(channelId, docSide, selectedText);
        return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ok\"}";
    }

    @Tool(name = "query_review",
          description = "Send a question or review request to the document reviewer. The reviewer responds asynchronously via the Qhorus channel.")
    public String queryReview(
            @ToolArg(description = "sessionId returned by start_review") String sessionId,
            @ToolArg(description = "The question or review request") String question) {

        UUID channelId;
        try {
            channelId = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid sessionId format: " + sessionId;
        }

        if (registry.find(channelId).isEmpty()) {
            return "error: no active session for sessionId: " + sessionId;
        }

        String correlationId = UUID.randomUUID().toString();
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(HUMAN_INSTANCE_ID)
                .type(MessageType.QUERY)
                .content(question)
                .correlationId(correlationId)
                .actorType(ActorType.HUMAN)
                .build());

        return "{\"sessionId\":\"" + sessionId + "\",\"correlationId\":\"" + correlationId
                + "\",\"status\":\"dispatched\"}";
    }

    @Tool(name = "end_review",
          description = "End a review session. Pass deleteChannel=true to fully remove the Qhorus channel.")
    public String endReview(
            @ToolArg(description = "sessionId returned by start_review") String sessionId,
            @ToolArg(description = "Whether to delete the Qhorus channel (default: false)") boolean deleteChannel) {

        UUID channelId;
        try {
            channelId = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid sessionId format: " + sessionId;
        }

        Optional<ReviewSession> sessionOpt = registry.find(channelId);
        if (sessionOpt.isEmpty()) {
            return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"not-found\"}";
        }

        ReviewSession session = sessionOpt.get();
        registry.remove(channelId);

        if (deleteChannel) {
            try {
                channelService.delete(session.channelName(), true);
            } catch (Exception e) {
                LOG.warning("end_review: channel delete failed for " + session.channelName()
                        + ": " + e.getMessage());
            }
        }

        return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ended\",\"channelDeleted\":"
                + deleteChannel + "}";
    }

    private String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            LOG.warning("Could not read file " + path + ": " + e.getMessage());
            return null;
        }
    }
}
