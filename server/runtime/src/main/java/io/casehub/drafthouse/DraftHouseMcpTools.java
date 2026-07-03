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

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.Resource;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
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

    private static final Logger LOG = Logger.getLogger(DraftHouseMcpTools.class.getName());

    @Inject ChannelService channelService;
    @Inject ChannelGateway channelGateway;
    @Inject InstanceService instanceService;
    @Inject MessageService messageService;
    @Inject ReviewSessionRegistry registry;
    @Inject DraftHouseConfig config;
    @Inject ReviewerResolver resolver;

    @PostConstruct
    void registerHumanInstance() {
        instanceService.register(DraftHouseInstances.HUMAN_INSTANCE_ID, "DraftHouse human reviewer",
                List.of("document-review-human"));
    }

    @Tool(name = "start_review",
          description = "Start a document review session. Returns JSON with sessionId "
              + "(use for all subsequent calls), channel, and reviewer (agentId, name, "
              + "instructions). Use list_reviewers to see available agents.")
    public String startReview(
            @ToolArg(description = "Absolute path to document A (the 'before' version)") String docAPath,
            @ToolArg(description = "Absolute path to document B (the 'after' version)") String docBPath,
            @ToolArg(description = "Eidos agent ID for the reviewer. Omit for default "
                    + "reviewer. Use list_reviewers to see available agents.")
            String agentId) {

        ResolvedReviewer reviewer;
        try {
            reviewer = resolver.resolve(agentId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }

        String docAContent = readFile(docAPath);
        if (docAContent == null) return "error: could not read document A: " + docAPath;

        String docBContent = readFile(docBPath);
        if (docBContent == null) return "error: could not read document B: " + docBPath;

        if (docAContent.length() > config.reviewer().maxDocChars()) {
            return "error: document A exceeds maximum size of " + config.reviewer().maxDocChars() + " characters";
        }
        if (docBContent.length() > config.reviewer().maxDocChars()) {
            return "error: document B exceeds maximum size of " + config.reviewer().maxDocChars() + " characters";
        }

        String channelSlug = "r-" + UUID.randomUUID();
        String channelName = "drafthouse/" + channelSlug;

        String instanceId = null;
        Channel channel = null;
        try {
            channel = channelService.create(ChannelCreateRequest.builder(channelName)
                    .description("DraftHouse review session")
                    .semantic(ChannelSemantic.APPEND).build());

            String sessionId = channel.id().toString();
            String resolvedChannelName = channel.name();
            instanceId = "drafthouse-reviewer-" + sessionId;
            instanceService.register(instanceId, reviewer.name() + " " + sessionId,
                    List.of("document-review"));

            ReviewSession session = new ReviewSession(
                    channel.id(), sessionId, resolvedChannelName, instanceId,
                    docAContent, docBContent, null, reviewer);

            registry.put(session);
            channelGateway.initChannel(channel.id(), new ChannelRef(channel.id(), resolvedChannelName));

            return "{\"sessionId\":\"" + sessionId + "\",\"channel\":\"" + resolvedChannelName
                    + "\",\"reviewer\":{\"agentId\":" + jsonString(reviewer.agentId())
                    + ",\"name\":" + jsonString(reviewer.name())
                    + ",\"instructions\":" + jsonString(reviewer.instructions()) + "}}";

        } catch (Exception e) {
            LOG.warning("start_review failed: " + e.getMessage() + " — attempting cleanup");
            if (channel != null) {
                if (instanceId != null) {
                    try { instanceService.deregister(instanceId); } catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
                }
                try { registry.remove(channel.id()); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                try { channelService.delete(channel.id(), true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
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

        if (side == null && selectedText == null) {
            registry.updateSelection(channelId, null);
            return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ok\"}";
        }

        DocumentSide docSide;
        try {
            docSide = DocumentSide.valueOf(side);
        } catch (IllegalArgumentException | NullPointerException e) {
            return "error: invalid side value '" + side + "' — must be 'A' or 'B'";
        }

        if (selectedText == null) {
            return "error: side and selectedText must both be provided or both be null";
        }

        registry.updateSelection(channelId, new SelectionScope(docSide, 0, 0, selectedText));
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
                .sender(DraftHouseInstances.HUMAN_INSTANCE_ID)
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

        try { instanceService.deregister(session.instanceId()); }
        catch (Exception e) { LOG.warning("end_review: instance deregister failed: " + e.getMessage()); }

        if (deleteChannel) {
            try {
                channelService.delete(session.channelId(), true);
            } catch (Exception e) {
                LOG.warning("end_review: channel delete failed for " + session.channelName()
                        + ": " + e.getMessage());
            }
        }

        return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ended\",\"channelDeleted\":"
                + deleteChannel + "}";
    }

    @Tool(name = "list_reviewers",
          description = "List available reviewer agents. Each agent has a distinct review perspective "
                  + "defined by its disposition and briefing. Use the agentId with start_review or start_debate.")
    public String listReviewers() {
        var descriptors = resolver.listAvailable();
        if (descriptors.isEmpty()) return "[]";

        var sb = new StringBuilder("[");
        for (int i = 0; i < descriptors.size(); i++) {
            if (i > 0) sb.append(",");
            var d = descriptors.get(i);
            sb.append("{\"agentId\":").append(jsonString(d.agentId()));
            sb.append(",\"name\":").append(jsonString(d.name()));
            sb.append(",\"slot\":").append(jsonString(d.slot()));

            if (d.disposition() != null) {
                sb.append(",\"disposition\":{");
                boolean first = true;
                for (DispositionAxis axis : DispositionAxis.values()) {
                    var val = d.disposition().get(axis);
                    if (val.isPresent()) {
                        if (!first) sb.append(",");
                        sb.append("\"").append(axis.jsonKey()).append("\":").append(jsonString(val.get()));
                        first = false;
                    }
                }
                sb.append("}");
            }

            if (!d.capabilities().isEmpty()) {
                sb.append(",\"capabilities\":[");
                for (int j = 0; j < d.capabilities().size(); j++) {
                    if (j > 0) sb.append(",");
                    var cap = d.capabilities().get(j);
                    sb.append("{\"name\":").append(jsonString(cap.name()));
                    if (cap.tags() != null && !cap.tags().isEmpty()) {
                        sb.append(",\"tags\":[");
                        for (int k = 0; k < cap.tags().size(); k++) {
                            if (k > 0) sb.append(",");
                            sb.append(jsonString(cap.tags().get(k)));
                        }
                        sb.append("]");
                    }
                    sb.append("}");
                }
                sb.append("]");
            }

            if (d.briefing() != null) {
                String summary = d.briefing().length() > 200
                        ? d.briefing().substring(0, 200) + "..."
                        : d.briefing();
                sb.append(",\"briefingSummary\":").append(jsonString(summary));
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Tool(name = "get_reviewer_instructions",
          description = "Render full reviewer instructions for an agent, optionally "
              + "in the context of a resource.")
    public String getReviewerInstructions(
            @ToolArg(description = "Eidos agent ID for the reviewer") String agentId,
            @ToolArg(description = "Optional resource path for contextual rendering")
            String resourcePath) {

        if (agentId == null || agentId.isBlank()) return "error: agentId is required";

        ResolvedReviewer reviewer;
        try {
            Resource[] resources = resourcePath != null && !resourcePath.isBlank()
                    ? new Resource[]{new Resource(resourcePath, "spec", "file")}
                    : new Resource[0];
            reviewer = resolver.resolve(agentId, resources);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        return "{\"agentId\":" + jsonString(reviewer.agentId())
                + ",\"name\":" + jsonString(reviewer.name())
                + ",\"instructions\":" + jsonString(reviewer.instructions()) + "}";
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

    static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
