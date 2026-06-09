package io.casehub.drafthouse;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

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
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

/**
 * MCP tool surface for debate sessions.
 * Agents (REV and IMP) post structured debate entries via these tools.
 *
 * Error handling: all errors returned as "error: ..." strings per mcp-tool-error-strings.md.
 * Session cleanup order: registry.remove() first, then channel.delete()
 * — prevents a live session handle pointing at a deleted channel.
 */
@ApplicationScoped
public class DebateMcpTools {

    private static final Logger LOG = Logger.getLogger(DebateMcpTools.class.getName());

    @Inject ChannelService channelService;
    @Inject ChannelGateway channelGateway;
    @Inject InstanceService instanceService;
    @Inject MessageService messageService;
    @Inject ProjectionService projectionService;
    @Inject DebateSessionRegistry registry;
    @Inject DebateChannelProjection debateProjection;

    @Tool(name = "start_debate",
          description = "Start a peer-to-peer debate session between a reviewer (REV) and implementer (IMP) agent. Returns JSON with debateSessionId (use for all subsequent calls), channel name, and specPath.")
    public String startDebate(
            @ToolArg(description = "Absolute path to the spec file being debated") String specPath) {

        String debateSlug = "d-" + UUID.randomUUID();
        String channelName = "drafthouse/debate/" + debateSlug;

        String revInstanceId = null;
        String impInstanceId = null;
        Channel channel = null;
        try {
            channel = channelService.create(channelName, "DraftHouse debate session",
                    ChannelSemantic.APPEND, null);

            String debateSessionId = channel.id.toString();
            String resolvedName = channel.name;
            revInstanceId = "drafthouse-rev-" + debateSessionId;
            impInstanceId = "drafthouse-imp-" + debateSessionId;

            instanceService.register(revInstanceId, "DraftHouse debate reviewer " + debateSessionId,
                    List.of("document-debate-rev"));
            instanceService.register(impInstanceId, "DraftHouse debate implementer " + debateSessionId,
                    List.of("document-debate-imp"));

            DebateSession session = new DebateSession(
                    channel.id, debateSessionId, resolvedName, revInstanceId, impInstanceId, specPath);

            registry.put(session);
            channelGateway.initChannel(channel.id, new ChannelRef(channel.id, resolvedName));

            return "{\"debateSessionId\":\"" + debateSessionId + "\",\"channel\":\"" + resolvedName
                    + "\",\"specPath\":\"" + specPath + "\"}";

        } catch (Exception e) {
            LOG.warning("start_debate failed: " + e.getMessage() + " — attempting cleanup");
            if (channel != null) {
                if (revInstanceId != null) {
                    try { instanceService.deregister(revInstanceId); } catch (Exception ce) { LOG.warning("cleanup rev instance: " + ce.getMessage()); }
                }
                if (impInstanceId != null) {
                    try { instanceService.deregister(impInstanceId); } catch (Exception ce) { LOG.warning("cleanup imp instance: " + ce.getMessage()); }
                }
                try { registry.remove(channel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                try { channelService.delete(channel.id, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
            }
            return "error: " + e.getMessage();
        }
    }

    @Tool(name = "raise_point",
          description = "Raise a new debate point. Returns JSON with pointId — use this in subsequent respond_to calls to cite this point.")
    public String raisePoint(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV (reviewer) or IMP (implementer)") String agentRole,
            @ToolArg(description = "Current debate round number (integer, starting at 1)") int round,
            @ToolArg(description = "The point being raised") String content,
            @ToolArg(description = "Priority: P1 (blocking), P2 (important), P3 (minor)") String priority,
            @ToolArg(description = "Scope: ISOLATED (single instance) or SYSTEMIC (pattern)") String scope,
            @ToolArg(description = "Optional location: spec section, heading, or free-form. Null to omit.") String location) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }

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
                .sender(sender(session, agentRole))
                .type(MessageType.QUERY)
                .content(encodedContent)
                .correlationId(pointId)
                .actorType(ActorType.AGENT)
                .build());

        return "{\"pointId\":\"" + pointId + "\",\"status\":\"dispatched\"}";
    }

    @Tool(name = "respond_to",
          description = "Respond to a debate point. entryType must be: agree, dispute, qualify, or counter.")
    public String respondTo(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
            @ToolArg(description = "Current debate round number") int round,
            @ToolArg(description = "The pointId returned by raise_point") String pointId,
            @ToolArg(description = "Response type: agree, dispute, qualify, counter") String entryType,
            @ToolArg(description = "Your response content") String content) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }

        MessageType qhorusType = switch (entryType) {
            case "agree"   -> MessageType.DONE;
            case "dispute" -> MessageType.DECLINE;
            case "qualify", "counter" -> MessageType.RESPONSE;
            default -> null;
        };
        if (qhorusType == null) {
            return "error: invalid entryType '" + entryType + "' — must be agree, dispute, qualify, or counter";
        }

        Long inReplyTo = messageService.findByCorrelationId(pointId).map(m -> m.id).orElse(null);
        if (inReplyTo == null) return "error: point not found: " + pointId;

        String encodedContent = DebateProtocol.META_SENTINEL + "entryType=" + entryType.toUpperCase()
                + "|agent=" + agentRole + "|round=" + round + "\n\n" + content;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, agentRole))
                .type(qhorusType)
                .content(encodedContent)
                .correlationId(pointId)
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());

        return "{\"status\":\"dispatched\"}";
    }

    @Tool(name = "flag_human",
          description = "Flag a debate point for human review. Signals that the agents cannot resolve the point without human input.")
    public String flagHuman(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
            @ToolArg(description = "Current debate round number") int round,
            @ToolArg(description = "The pointId being flagged") String pointId,
            @ToolArg(description = "Reason for escalating to human") String reason) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        if (!"REV".equals(agentRole) && !"IMP".equals(agentRole)) {
            return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
        }

        Long inReplyTo = messageService.findByCorrelationId(pointId).map(m -> m.id).orElse(null);
        if (inReplyTo == null) return "error: point not found: " + pointId;

        String encodedContent = DebateProtocol.META_SENTINEL + "entryType=FLAG_HUMAN|agent=" + agentRole
                + "|round=" + round + "\n\n" + reason;

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender(session, agentRole))
                .type(MessageType.HANDOFF)
                .content(encodedContent)
                .target(DraftHouseInstances.HUMAN_INSTANCE_ID)
                .correlationId(pointId)
                .inReplyTo(inReplyTo)
                .actorType(ActorType.AGENT)
                .build());

        return "{\"status\":\"dispatched\"}";
    }

    @Tool(name = "get_debate_summary",
          description = "Get the current debate summary as markdown. Shows all points with their status, thread, and classifications.")
    public String getDebateSummary(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        var result = projectionService.project(session.channelId(), debateProjection);
        return debateProjection.render(result);
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

        try { instanceService.deregister(session.revInstanceId()); }
        catch (Exception e) { LOG.warning("end_debate: rev instance deregister failed: " + e.getMessage()); }
        try { instanceService.deregister(session.impInstanceId()); }
        catch (Exception e) { LOG.warning("end_debate: imp instance deregister failed: " + e.getMessage()); }

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
            @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
            @ToolArg(description = "Current round number") int round,
            @ToolArg(description = "Your reasoning memo content") String content) {
        try {
            DebateSession session = resolveSession(debateSessionId);
            if (session == null) return sessionError(debateSessionId);
            if (!"REV".equals(agentRole) && !"IMP".equals(agentRole))
                return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
            String encoded = DebateProtocol.META_SENTINEL
                    + "entryType=MEMO|agent=" + agentRole + "|round=" + round
                    + "\n\n" + Objects.requireNonNullElse(content, "");
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(session.channelId())
                    .sender(sender(session, agentRole))
                    .type(MessageType.STATUS)
                    .content(encoded)
                    .actorType(ActorType.AGENT)
                    .build());
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
            @ToolArg(description = "Your agent role: REV or IMP") String agentRole,
            @ToolArg(description = "Sub-task type") String taskType,
            @ToolArg(description = "pointId from raise_point. Null for NEUTRAL_SUMMARY or CUSTOM.") String pointId,
            @ToolArg(description = "For CUSTOM: full context. For CONSISTENCY_CHECK: proposed resolution. Null otherwise.") String customInput) {
        try {
            DebateSession session = resolveSession(debateSessionId);
            if (session == null) return sessionError(debateSessionId);
            if (!"REV".equals(agentRole) && !"IMP".equals(agentRole))
                return "error: invalid agentRole '" + agentRole + "' — must be REV or IMP";
            String subTaskId = UUID.randomUUID().toString();
            StringBuilder header = new StringBuilder(DebateProtocol.META_SENTINEL)
                    .append("entryType=SUB_TASK_REQUEST")
                    .append("|agent=").append(agentRole)
                    .append("|taskType=").append(Objects.requireNonNullElse(taskType, "CUSTOM"))
                    .append("|subTaskId=").append(subTaskId);
            if (pointId != null && !pointId.isBlank()) header.append("|pointId=").append(pointId);
            String encoded = header + "\n\n" + Objects.requireNonNullElse(customInput, "");
            messageService.dispatch(MessageDispatch.builder()
                    .channelId(session.channelId())
                    .sender(sender(session, agentRole))
                    .type(MessageType.QUERY)
                    .content(encoded)
                    .correlationId(subTaskId)
                    .actorType(ActorType.AGENT)
                    .build());
            return "{\"subTaskId\":\"" + subTaskId + "\",\"status\":\"dispatched\"}";
        } catch (Exception e) {
            LOG.warning("request_subagent failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private String sender(DebateSession session, String agentRole) {
        return switch (agentRole) {
            case "REV" -> session.revInstanceId();
            case "IMP" -> session.impInstanceId();
            default    -> throw new IllegalArgumentException("Unknown agentRole: " + agentRole);
        };
    }
}
