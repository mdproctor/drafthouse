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

    @Tool(name = "start_debate",
          description = "Start a debate session. Any agent role may participate: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR. Returns JSON with debateSessionId (use for all subsequent calls), channel name, and specPath.")
    public String startDebate(
            @ToolArg(description = "Absolute path to the spec file being debated") String specPath) {

        String debateSlug = "d-" + UUID.randomUUID();
        String channelName = "drafthouse/debate/" + debateSlug;

        Channel channel = null;
        DebateSession session = null;
        try {
            channel = channelService.create(channelName, "DraftHouse debate session",
                    ChannelSemantic.APPEND, null);

            String debateSessionId = channel.id.toString();
            String resolvedName = channel.name;

            session = new DebateSession(channel.id, debateSessionId, resolvedName, specPath);
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
                    + "\",\"specPath\":" + jsonString(specPath) + "}";

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
          description = "Get the current debate summary as markdown. Shows all points with their status, thread, and classifications.")
    public String getDebateSummary(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

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
            return sb.toString();
        }
        return summary;
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

            newSession = new DebateSession(newChannel.id, newSessionId, newChannel.name, original.specPath());
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
            String specPathJson = jsonString(original.specPath());
            return """
                    {"newDebateSessionId":"%s","originalDebateSessionId":"%s","specPath":%s,\
                    "summary":%s,"contextCarried":{"roundsIncluded":"%s","pointCount":%d,\
                    "findingsComplete":%d,"findingsPending":%d,"findingsInOriginalOnly":%d},\
                    "message":"New session ready. Rounds %s from the original are visible here.%s \
                    Call end_debate on originalDebateSessionId when done with it."}""".formatted(
                    newSessionId, debateSessionId, specPathJson,
                    jsonString(summary), roundRange, pointCount,
                    findingsComplete, findingsPending, findingsInOriginalOnly,
                    roundRange, findingNote);

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

    /** Renders a bounded state, returning a custom message when the state has no debate content. */
    private String renderBounded(ReviewState state, int round) {
        if (state.points().isEmpty() && state.memos().isEmpty() && state.subTaskFindings().isEmpty()) {
            return "No debate activity up to round " + round + ".";
        }
        return new SummaryRenderer().render(state);
    }

    /** Escapes a string for inclusion as a JSON string value (RFC 8259 §7). */
    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
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
        return session.registerIfAbsent(role, () -> {
            final String instanceId = DebateSession.instanceId(role, session.debateSessionId());
            instanceService.register(instanceId,
                    "DraftHouse " + role.name().toLowerCase() + " " + session.debateSessionId(),
                    List.of("document-debate-" + role.name().toLowerCase()));
            return instanceId;
        });
    }
}
