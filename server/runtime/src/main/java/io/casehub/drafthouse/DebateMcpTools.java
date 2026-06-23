package io.casehub.drafthouse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.Resource;
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
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.casehub.drafthouse.debate.AgentType;
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.drafthouse.debate.SubTaskStatus;
import io.casehub.drafthouse.debate.SummaryRenderer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * MCP tool surface for debate sessions.
 * Any AgentType (REV, IMP, SUPERVISOR, MODERATOR, SELECTOR) may post via these tools.
 *
 * Error handling: all errors returned as "error: ..." strings per mcp-tool-error-strings.md.
 * Session cleanup order: registry.remove() first, then channel.delete()
 * — prevents a live session handle pointing at a deleted channel.
 */
@ApplicationScoped
public class DebateMcpTools {

    private static final Logger LOG = Logger.getLogger(DebateMcpTools.class.getName());

    private static final String VALID_ROLES = Arrays.stream(AgentType.values())
            .map(Enum::name).collect(Collectors.joining(", "));

    @Inject ChannelService channelService;
    @Inject ChannelGateway channelGateway;
    @Inject InstanceService instanceService;
    @Inject MessageService messageService;
    @Inject ProjectionService projectionService;
    @Inject DebateSessionRegistry registry;
    @Inject DebateChannelProjection debateProjection;
    @Inject DraftHouseConfig config;
    @Inject DebateEventResource debateEventResource;
    @Inject ReviewerResolver resolver;

    @Tool(name = "start_debate",
          description = "Start a debate session. Any agent role may participate: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR. Returns JSON with debateSessionId (use for all subsequent calls), channel name, specPath, and reviewer.")
    public String startDebate(
            @ToolArg(description = "Absolute path to the spec file being debated") String specPath,
            @ToolArg(description = "Eidos agent ID for the reviewer (e.g. 'drafthouse-structural-reviewer'). "
                    + "Omit for default reviewer. Use list_reviewers to see available agents.")
            String agentId) {

        String debateSlug = "d-" + UUID.randomUUID();
        String channelName = "drafthouse/debate/" + debateSlug;

        Channel channel = null;
        DebateSession session = null;
        try {
            ResolvedReviewer reviewer;
            try {
                reviewer = resolver.resolve(agentId, new Resource(specPath, "spec", "file"));
            } catch (IllegalArgumentException | IllegalStateException e) {
                return "error: " + e.getMessage();
            }

            channel = channelService.create(channelName, "DraftHouse debate session",
                    ChannelSemantic.APPEND, null);

            String debateSessionId = channel.id.toString();
            String resolvedName = channel.name;

            session = new DebateSession(channel.id, debateSessionId, resolvedName, reviewer.agentId());
            session.addDocument(specPath, "spec");
            registry.put(session);

            // Register REV and IMP eagerly; all other roles lazy-register on first use via sender()
            sender(session, AgentType.REV);
            sender(session, AgentType.IMP);

            channelGateway.initChannel(channel.id, new ChannelRef(channel.id, resolvedName));

            try {
                long specSize = Files.size(Path.of(specPath));
                session.contextTracker().addInitialContribution(specSize);
            } catch (Exception e) {
                LOG.fine("Could not size spec file for context tracking: " + e.getMessage());
            }

            return "{\"debateSessionId\":\"" + debateSessionId + "\",\"channel\":\"" + resolvedName
                    + "\",\"specPath\":" + DraftHouseMcpTools.jsonString(specPath)
                    + ",\"reviewer\":{\"agentId\":" + DraftHouseMcpTools.jsonString(reviewer.agentId())
                    + ",\"name\":" + DraftHouseMcpTools.jsonString(reviewer.name())
                    + ",\"instructions\":" + DraftHouseMcpTools.jsonString(reviewer.instructions()) + "}}";

        } catch (Exception e) {
            LOG.warning("start_debate failed: " + e.getMessage() + " — attempting cleanup");
            if (channel != null) {
                if (session != null) {
                    session.participants().values().forEach(id -> {
                        try { instanceService.deregister(id); } catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
                    });
                    try { registry.remove(channel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                }
                try { channelService.delete(channel.id, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
            }
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "raise_point",
          description = "Raise a new debate point. Returns JSON with pointId — use this in subsequent respond_to calls to cite this point.")
    public String raisePoint(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR") String agentRole,
            @ToolArg(description = "Current debate round number (integer, starting at 1)") int round,
            @ToolArg(description = "The point being raised") String content,
            @ToolArg(description = "Priority: P1 (blocking), P2 (important), P3 (minor)") String priority,
            @ToolArg(description = "Scope: ISOLATED (single instance) or SYSTEMIC (pattern)") String scope,
            @ToolArg(description = "Optional location: spec section, heading, or free-form. Null to omit.") String location) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        AgentType role = parseRole(agentRole);
        if (role == null) return roleError(agentRole);

        String pointId = UUID.randomUUID().toString();
        StringBuilder meta = new StringBuilder(DebateProtocol.META_SENTINEL)
                .append("entryType=RAISE|agent=").append(agentRole)
                .append("|round=").append(round)
                .append("|priority=").append(priority)
                .append("|scope=").append(scope);
        if (location != null && !location.isBlank()) {
            meta.append("|location=").append(location);
        }
        String encodedContent = meta + "\n\n" + content;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, role))
                .type(MessageType.QUERY)
                .content(encodedContent)
                .correlationId(pointId)
                .actorType(ActorType.AGENT)
                .build());

        trackAndPush(session, encodedContent.length());
        return "{\"pointId\":\"" + pointId + "\",\"status\":\"dispatched\"}";
    }

    @Tool(name = "respond_to",
          description = "Respond to a debate point. entryType must be: agree, dispute, qualify, counter, or declined.")
    public String respondTo(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR") String agentRole,
            @ToolArg(description = "Current debate round number") int round,
            @ToolArg(description = "The pointId returned by raise_point") String pointId,
            @ToolArg(description = "Response type: agree, dispute, qualify, counter, declined") String entryType,
            @ToolArg(description = "Your response content") String content) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        AgentType role = parseRole(agentRole);
        if (role == null) return roleError(agentRole);

        MessageType qhorusType = switch (entryType) {
            case "agree"   -> MessageType.DONE;
            case "dispute" -> MessageType.DECLINE;
            case "declined" -> MessageType.DECLINE;
            case "qualify", "counter" -> MessageType.RESPONSE;
            default -> null;
        };
        if (qhorusType == null) {
            return "error: invalid entryType '" + entryType + "' — must be agree, dispute, qualify, counter, or declined";
        }

        Long inReplyTo = messageService.findByCorrelationId(pointId).map(m -> m.id).orElse(null);
        if (inReplyTo == null) return "error: point not found: " + pointId;

        String encodedContent = DebateProtocol.META_SENTINEL + "entryType=" + entryType.toUpperCase()
                + "|agent=" + agentRole + "|round=" + round + "\n\n" + content;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, role))
                .type(qhorusType)
                .content(encodedContent)
                .correlationId(pointId)
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());

        trackAndPush(session, encodedContent.length());
        return "{\"status\":\"dispatched\"}";
    }

    @Tool(name = "flag_human",
          description = "Flag a debate point for human review. Signals that the agents cannot resolve the point without human input.")
    public String flagHuman(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR") String agentRole,
            @ToolArg(description = "Current debate round number") int round,
            @ToolArg(description = "The pointId being flagged") String pointId,
            @ToolArg(description = "Reason for escalating to human") String reason) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        AgentType role = parseRole(agentRole);
        if (role == null) return roleError(agentRole);

        Long inReplyTo = messageService.findByCorrelationId(pointId).map(m -> m.id).orElse(null);
        if (inReplyTo == null) return "error: point not found: " + pointId;

        String encodedContent = DebateProtocol.META_SENTINEL + "entryType=FLAG_HUMAN|agent=" + agentRole
                + "|round=" + round + "\n\n" + reason;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, role))
                .type(MessageType.HANDOFF)
                .content(encodedContent)
                .target(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .correlationId(pointId)
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());

        trackAndPush(session, encodedContent.length());
        return "{\"status\":\"dispatched\"}";
    }

    @Tool(name = "get_debate_summary",
          description = "Get the current debate summary as JSON with 'summary' (markdown) and optional 'reviewer' fields.")
    public String getDebateSummary(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        var result = projectionService.project(session.channelId(), debateProjection);
        String summary = debateProjection.render(result);

        summary = appendWorkingSet(summary, session);

        SelectionScope sel = session.currentSelection();
        if (sel != null) {
            StringBuilder sb = new StringBuilder(summary);
            sb.append("\n\n## Active Selection\n");
            sb.append("**Document ").append(sel.side().name()).append("**");
            if (sel.startLine() > 0) {
                sb.append(", lines ").append(sel.startLine()).append("–").append(sel.endLine());
            }
            sb.append(":\n> ").append(sel.selectedText()).append("\n");
            summary = sb.toString();
        }

        String reviewerJson = "";
        if (session.agentId() != null) {
            var name = resolver.findDescriptor(session.agentId()).map(AgentDescriptor::name).orElse(null);
            if (name != null) {
                reviewerJson = ",\"reviewer\":{\"agentId\":" + DraftHouseMcpTools.jsonString(session.agentId())
                        + ",\"name\":" + DraftHouseMcpTools.jsonString(name) + "}";
            }
        }
        return "{\"summary\":" + DraftHouseMcpTools.jsonString(summary) + reviewerJson + "}";
    }

    @Tool(name = "end_debate",
          description = "End a debate session. Pass deleteChannel=true to remove the Qhorus channel.")
    public String endDebate(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Whether to delete the Qhorus channel (default: false)") boolean deleteChannel) {

        UUID channelId;
        try {
            channelId = UUID.fromString(debateSessionId);
        } catch (IllegalArgumentException e) {
            return "error: invalid session id format: " + debateSessionId;
        }

        DebateSession session = registry.find(channelId).orElse(null);
        if (session == null) {
            return "{\"debateSessionId\":\"" + debateSessionId + "\",\"status\":\"not-found\"}";
        }

        registry.remove(channelId);

        session.participants().values().forEach(instanceId -> {
            try { instanceService.deregister(instanceId); }
            catch (Exception e) { LOG.warning("end_debate: deregister failed: " + e.getMessage()); }
        });

        if (deleteChannel) {
            try {
                channelService.delete(session.channelId(), true);
            } catch (Exception e) {
                LOG.warning("end_debate: channel delete failed for " + session.channelName()
                        + ": " + e.getMessage());
            }
        }

        return "{\"debateSessionId\":\"" + debateSessionId + "\",\"status\":\"ended\",\"channelDeleted\":"
                + deleteChannel + "}";
    }

    @Tool(name = "post_memo",
          description = "Write a per-round reasoning memo to the debate channel. Call after your last "
                  + "raise/respond of a round to record working hypotheses, patterns noticed, and why "
                  + "concessions feel solid vs provisional.")
    public String postMemo(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR") String agentRole,
            @ToolArg(description = "Current round number") int round,
            @ToolArg(description = "Your reasoning memo content") String content) {
        try {
            DebateSession session = resolveSession(debateSessionId);
            if (session == null) return sessionError(debateSessionId);
            AgentType role = parseRole(agentRole);
            if (role == null) return roleError(agentRole);
            String encoded = DebateProtocol.META_SENTINEL
                    + "entryType=MEMO|agent=" + agentRole + "|round=" + round
                    + "\n\n" + Objects.requireNonNullElse(content, "");
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(session.channelId())
                    .sender(sender(session, role))
                    .type(MessageType.STATUS)
                    .content(encoded)
                    .actorType(ActorType.AGENT)
                    .build());
            trackAndPush(session, encoded.length());
            return "{\"status\":\"dispatched\"}";
        } catch (Exception e) {
            LOG.warning("post_memo failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "request_subagent",
          description = "Dispatch a fresh-context sub-agent for focused analysis. Finding appears in "
                  + "get_debate_summary (⏳ while pending). You may continue raising/responding while it runs. "
                  + "taskType: VERIFY | ARBITRATE | DEEP_ANALYSIS | CONSISTENCY_CHECK | NEUTRAL_SUMMARY | CUSTOM. "
                  + "customInput: for CUSTOM — the full context; for CONSISTENCY_CHECK — the proposed resolution text.")
    public String requestSubagent(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR") String agentRole,
            @ToolArg(description = "Sub-task type") String taskType,
            @ToolArg(description = "pointId from raise_point. Null for NEUTRAL_SUMMARY or CUSTOM.") String pointId,
            @ToolArg(description = "Current debate round number") int round,
            @ToolArg(description = "For CUSTOM: full context. For CONSISTENCY_CHECK: proposed resolution. Null otherwise.") String customInput) {
        try {
            DebateSession session = resolveSession(debateSessionId);
            if (session == null) return sessionError(debateSessionId);
            AgentType role = parseRole(agentRole);
            if (role == null) return roleError(agentRole);
            String subTaskId = UUID.randomUUID().toString();
            StringBuilder header = new StringBuilder(DebateProtocol.META_SENTINEL)
                    .append("entryType=SUB_TASK_REQUEST")
                    .append("|agent=").append(agentRole)
                    .append("|taskType=").append(Objects.requireNonNullElse(taskType, "CUSTOM"))
                    .append("|subTaskId=").append(subTaskId)
                    .append("|round=").append(round);
            if (pointId != null && !pointId.isBlank()) header.append("|pointId=").append(pointId);
            String encoded = header + "\n\n" + Objects.requireNonNullElse(customInput, "");
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(session.channelId())
                    .sender(sender(session, role))
                    .type(MessageType.QUERY)
                    .content(encoded)
                    .correlationId(subTaskId)
                    .actorType(ActorType.AGENT)
                    .build());
            trackAndPush(session, encoded.length());
            return "{\"subTaskId\":\"" + subTaskId + "\",\"status\":\"dispatched\"}";
        } catch (Exception e) {
            LOG.warning("request_subagent failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "get_debate_summary_at_round",
          description = "Get the debate summary as it stood at the end of round N. Only messages with "
                  + "round ≤ N are included. Use to preview a prior state before restart_from_round, "
                  + "or to inspect any round on a live session. "
                  + "Note: always use the ORIGINAL session's ID to inspect prior rounds — a restarted "
                  + "session's channel contains no prior debate content.")
    public String getDebateSummaryAtRound(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Maximum round to include (must be ≥ 1)") int round) {
        if (round < 1) return "error: round must be ≥ 1 (got " + round + ")";
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);
        var bounded = new DebateChannelProjection.RoundBoundedProjection(round, debateProjection);
        var result = projectionService.project(session.channelId(), bounded);
        return renderBounded(result.state(), round);
    }

    @Tool(name = "restart_from_round",
          description = "Create a new debate session branching from the state at round N. "
                  + "The new session starts with the debate history up to and including round N "
                  + "as its bootstrap context. Rounds after N from the original are not visible "
                  + "in the new session. Sub-agent findings from rounds ≤ N are included; "
                  + "findings from later rounds remain in the original session only. "
                  + "The original session stays live — call end_debate on originalDebateSessionId "
                  + "when done with it.")
    public String restartFromRound(
            @ToolArg(description = "debateSessionId of the original session") String debateSessionId,
            @ToolArg(description = "Branch from this round's state (must be ≥ 1). "
                    + "Pass the last completed round to resume; pass an earlier round to redo from there.") int round) {
        if (round < 1) return "error: round must be ≥ 1 (got " + round + ")";

        DebateSession original = resolveSession(debateSessionId);
        if (original == null) return sessionError(debateSessionId);

        // Bounded projection for summary and finding counts
        var bounded = new DebateChannelProjection.RoundBoundedProjection(round, debateProjection);
        var boundedResult = projectionService.project(original.channelId(), bounded);
        var fullResult = projectionService.project(original.channelId(), debateProjection);

        String summary = renderBounded(boundedResult.state(), round);
        int findingsComplete = (int) boundedResult.state().subTaskFindings().values().stream()
                .filter(f -> f.status() == SubTaskStatus.COMPLETE)
                .count();
        int findingsPending = (int) boundedResult.state().subTaskFindings().values().stream()
                .filter(f -> f.status() == SubTaskStatus.PENDING)
                .count();
        int findingsInOriginalOnly = fullResult.state().subTaskFindings().size()
                - boundedResult.state().subTaskFindings().size();
        int pointCount = boundedResult.state().points().size();

        String debateSlug = "d-" + UUID.randomUUID();
        String channelName = "drafthouse/debate/" + debateSlug;
        Channel newChannel = null;
        DebateSession newSession = null;
        try {
            newChannel = channelService.create(channelName,
                    "DraftHouse debate session (restarted from round " + round + ")",
                    ChannelSemantic.APPEND, null);
            String newSessionId = newChannel.id.toString();

            newSession = DebateSession.branchFrom(original, newChannel.id, newSessionId, newChannel.name);
            registry.put(newSession);
            channelGateway.initChannel(newChannel.id, new ChannelRef(newChannel.id, newChannel.name));

            // Extract sender registration before builder — makes the registration site unambiguous
            String markerSender = sender(newSession, AgentType.REV); // registers REV for the new session
            String markerContent = DebateProtocol.META_SENTINEL
                    + "entryType=RESTART_CONTEXT"
                    + "|originChannelId=" + original.channelId()
                    + "|originRound=" + round
                    + "\n\n" + summary;
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(newChannel.id)
                    .sender(markerSender)
                    .type(MessageType.STATUS)
                    .content(markerContent)
                    .actorType(ActorType.AGENT)
                    .build());

            newSession.contextTracker().addInitialContribution(markerContent.length());
            debateEventResource.pushContextSnapshot(newSession.channelId(),
                    newSession.contextTracker().snapshot(
                            config.context().windowSizeChars(),
                            config.context().thresholdPercent()));

            String roundRange = round == 1 ? "1" : "1–" + round;
            String findingNote = findingsInOriginalOnly > 0
                    ? " " + findingsInOriginalOnly + " finding(s) from later rounds remain in the original session only."
                    : "";
            String specPathJson = DraftHouseMcpTools.jsonString(original.primaryPath());
            String reviewerJson = "";
            if (original.agentId() != null) {
                ResolvedReviewer restartReviewer = resolver.resolve(
                        original.agentId(), new Resource(original.primaryPath(), "spec", "file"));
                reviewerJson = ",\"reviewer\":{\"agentId\":" + DraftHouseMcpTools.jsonString(restartReviewer.agentId())
                        + ",\"name\":" + DraftHouseMcpTools.jsonString(restartReviewer.name())
                        + ",\"instructions\":" + DraftHouseMcpTools.jsonString(restartReviewer.instructions()) + "}";
            }
            return """
                    {"newDebateSessionId":"%s","originalDebateSessionId":"%s","specPath":%s,\
                    "summary":%s,"contextCarried":{"roundsIncluded":"%s","pointCount":%d,\
                    "findingsComplete":%d,"findingsPending":%d,"findingsInOriginalOnly":%d}%s,\
                    "message":"New session ready. Rounds %s from the original are visible here.%s \
                    Call end_debate on originalDebateSessionId when done with it."}""".formatted(
                    newSessionId, debateSessionId, specPathJson,
                    DraftHouseMcpTools.jsonString(summary), roundRange, pointCount,
                    findingsComplete, findingsPending, findingsInOriginalOnly,
                    reviewerJson, roundRange, findingNote);

        } catch (Exception e) {
            LOG.warning("restart_from_round failed: " + e.getMessage() + " — attempting cleanup");
            if (newChannel != null) {
                if (newSession != null) {
                    newSession.participants().values().forEach(id -> {
                        try { instanceService.deregister(id); } catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
                    });
                    try { registry.remove(newChannel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                }
                try { channelService.delete(newChannel.id, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
            }
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "report_context",
          description = "Report current context window usage for a debate session. "
                      + "Call periodically (e.g. every 2-3 rounds) to improve the accuracy "
                      + "of the context meter. Returns advisory warning when threshold exceeded.")
    public String reportContext(
            @ToolArg(description = "Debate session ID") String debateSessionId,
            @ToolArg(description = "Context usage as percentage (0-100)") double usagePercent) {
        try {
            DebateSession session = resolveSession(debateSessionId);
            if (session == null) return sessionError(debateSessionId);

            session.contextTracker().reportAgentUsage(usagePercent);
            ContextSnapshot snap = session.contextTracker().snapshot(
                    config.context().windowSizeChars(),
                    config.context().thresholdPercent());
            debateEventResource.pushContextSnapshot(session.channelId(), snap);

            if (snap.thresholdExceeded()) {
                return "{\"status\":\"warning\",\"effectivePercent\":" + snap.effectivePercent()
                        + ",\"message\":\"Context usage at " + String.format("%.1f", snap.effectivePercent())
                        + "% — consider committing state and restarting session\"}";
            }
            return "{\"status\":\"ok\",\"effectivePercent\":" + snap.effectivePercent() + "}";
        } catch (Exception e) {
            LOG.warning("report_context failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "add_document",
          description = "Add a document to the debate session's working set. Returns error if path already exists.")
    public String addDocument(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Absolute path to the document") String path,
            @ToolArg(description = "Label for this document (e.g. 'spec', 'impl', 'test')") String label) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        boolean added = session.addDocument(path, label);
        if (!added) {
            return "error: path already in document set: " + path;
        }
        registry.persist(session);
        debateEventResource.pushDocumentsChanged(session.channelId(), session);
        int count = session.documents().size();
        return "{\"status\":\"added\",\"documentCount\":" + count + "}";
    }

    @Tool(name = "remove_document",
          description = "Remove a document from the working set. Cannot remove the primary (first) document.")
    public String removeDocument(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Path of the document to remove") String path) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        try {
            boolean comparisonCleared = session.removeDocument(path);
            registry.persist(session);
            debateEventResource.pushDocumentsChanged(session.channelId(), session);
            if (comparisonCleared) {
                debateEventResource.pushComparisonChanged(session.channelId(), null);
            }
            int count = session.documents().size();
            return "{\"status\":\"removed\",\"documentCount\":" + count + "}";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "list_documents",
          description = "List all documents in the working set and the current comparison pair.")
    public String listDocuments(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        return DocumentSetJson.documentsAndComparisonToJson(session);
    }

    @Tool(name = "set_comparison",
          description = "Set which two documents the browser diff viewer should compare. Both paths must be in the working set.")
    public String setComparison(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Path for the A (left) side") String pathA,
            @ToolArg(description = "Path for the B (right) side") String pathB) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        try {
            session.setComparison(pathA, pathB);
            registry.persist(session);
            debateEventResource.pushComparisonChanged(session.channelId(), session.currentComparison());
            return "{\"status\":\"set\",\"pathA\":" + DraftHouseMcpTools.jsonString(pathA)
                    + ",\"pathB\":" + DraftHouseMcpTools.jsonString(pathB) + "}";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "export_debate_summary",
          description = "Export the current debate summary to a markdown file on disk. Creates parent directories if needed.")
    public String exportDebateSummary(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Absolute path for the output markdown file") String outputPath) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        try {
            var result = projectionService.project(session.channelId(), debateProjection);
            String summary = debateProjection.render(result);

            SelectionScope sel = session.currentSelection();
            if (sel != null) {
                StringBuilder sb = new StringBuilder(summary);
                sb.append("\n\n## Active Selection\n");
                sb.append("**Document ").append(sel.side().name()).append("**");
                if (sel.startLine() > 0) {
                    sb.append(", lines ").append(sel.startLine()).append("–").append(sel.endLine());
                }
                sb.append(":\n> ").append(sel.selectedText()).append("\n");
                summary = sb.toString();
            }

            summary = appendWorkingSet(summary, session);

            if (session.agentId() != null) {
                var name = resolver.findDescriptor(session.agentId()).map(AgentDescriptor::name).orElse(null);
                if (name != null) {
                    summary = "**Reviewer:** " + name + " (" + session.agentId() + ")\n\n" + summary;
                }
            }

            java.nio.file.Path path = java.nio.file.Path.of(outputPath);
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            byte[] bytes = summary.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(path, bytes);

            return "{\"status\":\"exported\",\"path\":" + DraftHouseMcpTools.jsonString(path.toAbsolutePath().toString())
                    + ",\"bytes\":" + bytes.length + "}";
        } catch (Exception e) {
            LOG.warning("export_debate_summary failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void trackAndPush(DebateSession session, long contentChars) {
        session.contextTracker().addContribution(contentChars);
        try {
            debateEventResource.pushContextSnapshot(session.channelId(),
                    session.contextTracker().snapshot(
                            config.context().windowSizeChars(),
                            config.context().thresholdPercent()));
        } catch (Exception e) {
            LOG.warning("Context push failed for " + session.debateSessionId() + ": " + e.getMessage());
        }
    }

    private String appendWorkingSet(String summary, DebateSession session) {
        var docs = session.documents();
        if (docs.size() <= 1) return summary;
        StringBuilder sb = new StringBuilder(summary);
        sb.append("\n\n## Working Set\n");
        for (var doc : docs) {
            sb.append("- **").append(doc.label()).append("** — `").append(doc.path()).append("`\n");
        }
        var cp = session.currentComparison();
        if (cp != null) {
            String labelA = docs.stream().filter(d -> d.path().equals(cp.pathA()))
                    .map(DocumentEntry::label).findFirst().orElse(cp.pathA());
            String labelB = docs.stream().filter(d -> d.path().equals(cp.pathB()))
                    .map(DocumentEntry::label).findFirst().orElse(cp.pathB());
            sb.append("\n**Comparing:** ").append(labelA).append(" ↔ ").append(labelB).append("\n");
        }
        return sb.toString();
    }

    /** Renders a bounded state, returning a custom message when the state has no debate content. */
    private String renderBounded(ReviewState state, int round) {
        if (state.points().isEmpty() && state.memos().isEmpty() && state.subTaskFindings().isEmpty()) {
            return "No debate activity up to round " + round + ".";
        }
        return new SummaryRenderer().render(state);
    }

    private DebateSession resolveSession(String debateSessionId) {
        try {
            UUID channelId = UUID.fromString(debateSessionId);
            return registry.find(channelId).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String sessionError(String debateSessionId) {
        try {
            UUID.fromString(debateSessionId);
            return "error: no active debate session for: " + debateSessionId;
        } catch (IllegalArgumentException e) {
            return "error: invalid session id format: " + debateSessionId;
        }
    }

    /** Parses an agentRole string, returning null for unknown values. */
    private static AgentType parseRole(final String agentRole) {
        try {
            return AgentType.valueOf(agentRole);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String roleError(final String agentRole) {
        return "error: invalid agentRole '" + agentRole + "' — must be one of: " + VALID_ROLES;
    }

    /**
     * Returns the Qhorus instance ID for the given role, registering it on first use.
     * The registration is idempotent — InstanceService.register() is an upsert.
     */
    private String sender(final DebateSession session, final AgentType role) {
        String existing = session.instanceIdFor(role);
        String instanceId = session.registerIfAbsent(role, () -> {
            final String id = DebateSession.instanceId(role, session.debateSessionId());
            instanceService.register(id,
                    "DraftHouse " + role.name().toLowerCase() + " " + session.debateSessionId(),
                    List.of("document-debate-" + role.name().toLowerCase()));
            return id;
        });
        if (existing == null) {
            registry.persist(session);
        }
        return instanceId;
    }
}
