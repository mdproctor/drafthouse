package io.casehub.drafthouse;

import io.casehub.agency.channel.ChannelMessageMeta;
import io.casehub.agency.conversation.ConversationProtocol;
import io.casehub.drafthouse.debate.AgentType;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.drafthouse.debate.ThreadProjection;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class ThreadMcpTools {

    private static final Logger LOG = Logger.getLogger(ThreadMcpTools.class.getName());
    private static final String VALID_ROLES = Arrays.stream(AgentType.values())
                                                    .map(Enum::name).collect(Collectors.joining(", "));

    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;
    @Inject InstanceService instanceService;
    @Inject ProjectionService projectionService;
    @Inject ThreadProjection threadProjection;

    @Tool(name = "start_thread",
          description = "Start a selection-scoped conversation thread on a debate session. "
                        + "Returns threadId and any nearby existing threads.")
    public String startThread(
            @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR | HUMAN") String agentRole,
            @ToolArg(description = "Document side: A or B") String side,
            @ToolArg(description = "Start line of the selection") int startLine,
            @ToolArg(description = "End line of the selection") int endLine,
            @ToolArg(description = "The selected text") String selectedText,
            @ToolArg(description = "Initial thread comment") String content) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        AgentType role = parseRole(agentRole);
        if (role == null) return roleError(agentRole);

        DocumentSide docSide;
        try { docSide = DocumentSide.valueOf(side); }
        catch (IllegalArgumentException e) { return "error: invalid side: " + side; }

        SelectionScope anchor;
        try { anchor = new SelectionScope(docSide, startLine, endLine, selectedText); }
        catch (IllegalArgumentException e) { return "error: " + e.getMessage(); }

        String threadId = session.startThread(anchor);
        registry.persist(session);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", threadId);
        meta.put("threadAction", "START");
        meta.put(ConversationProtocol.ROLE, agentRole);
        meta.put("side", side);
        meta.put("startLine", String.valueOf(startLine));
        meta.put("endLine", String.valueOf(endLine));
        meta.put("selectedText", selectedText);
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, content);

        String sender = DebateParticipants.ensureSender(session, role, instanceService, registry);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender)
                .type(MessageType.QUERY)
                .content(encoded)
                .correlationId(threadId)
                .actorType(role == AgentType.HUMAN ? ActorType.HUMAN : ActorType.AGENT)
                .build());

        var nearby = session.findThreadsNear(anchor).stream()
                .filter(t -> !t.threadId().equals(threadId))
                .map(t -> "{\"threadId\":\"" + t.threadId()
                           + "\",\"status\":\"" + t.status()
                           + "\",\"startLine\":" + t.anchor().startLine()
                           + ",\"endLine\":" + t.anchor().endLine() + "}")
                .collect(Collectors.joining(","));

        return "{\"threadId\":\"" + threadId + "\",\"status\":\"created\""
               + ",\"nearbyThreads\":[" + nearby + "]}";
    }

    @Tool(name = "reply_to_thread",
          description = "Reply to an existing selection-scoped thread.")
    public String replyToThread(
            @ToolArg(description = "debateSessionId") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR | HUMAN") String agentRole,
            @ToolArg(description = "threadId from start_thread") String threadId,
            @ToolArg(description = "Reply content") String content) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        AgentType role = parseRole(agentRole);
        if (role == null) return roleError(agentRole);

        SelectionThread thread = session.threads().get(threadId);
        if (thread == null) return "error: thread not found: " + threadId;
        if (thread.status() == ThreadStatus.RESOLVED) return "error: thread is resolved: " + threadId;

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", threadId);
        meta.put("threadAction", "REPLY");
        meta.put(ConversationProtocol.ROLE, agentRole);
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, content);

        String sender = DebateParticipants.ensureSender(session, role, instanceService, registry);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender)
                .type(MessageType.RESPONSE)
                .content(encoded)
                .correlationId(threadId)
                .actorType(role == AgentType.HUMAN ? ActorType.HUMAN : ActorType.AGENT)
                .build());

        return "{\"status\":\"dispatched\"}";
    }

    @Tool(name = "resolve_thread",
          description = "Resolve (close) a selection-scoped thread. Any participant can resolve.")
    public String resolveThread(
            @ToolArg(description = "debateSessionId") String debateSessionId,
            @ToolArg(description = "Your agent role: REV | IMP | SUPERVISOR | MODERATOR | SELECTOR | HUMAN") String agentRole,
            @ToolArg(description = "threadId to resolve") String threadId) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        AgentType role = parseRole(agentRole);
        if (role == null) return roleError(agentRole);

        try { session.resolveThread(threadId); }
        catch (IllegalArgumentException e) { return "error: " + e.getMessage(); }
        registry.persist(session);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", threadId);
        meta.put("threadAction", "RESOLVE");
        meta.put(ConversationProtocol.ROLE, agentRole);
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, "");

        String sender = DebateParticipants.ensureSender(session, role, instanceService, registry);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender)
                .type(MessageType.DONE)
                .content(encoded)
                .correlationId(threadId)
                .actorType(role == AgentType.HUMAN ? ActorType.HUMAN : ActorType.AGENT)
                .build());

        return "{\"status\":\"resolved\"}";
    }

    @Tool(name = "get_thread_summary",
          description = "Get thread summary for a debate session. Pass threadId for a single thread, or omit for all threads.")
    public String getThreadSummary(
            @ToolArg(description = "debateSessionId") String debateSessionId,
            @ToolArg(description = "Optional threadId. Omit for all threads.") String threadId) {

        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return sessionError(debateSessionId);

        var result = projectionService.project(session.channelId(), threadProjection);
        var threads = result.state().threads();

        if (threadId != null && !threadId.isBlank()) {
            var view = threads.get(threadId);
            if (view == null) return "error: thread not found: " + threadId;
            return renderThreadView(view);
        }

        if (threads.isEmpty()) return "{\"threads\":[],\"count\":0}";

        String threadsJson = threads.values().stream()
                .map(this::renderThreadCompact)
                .collect(Collectors.joining(","));
        return "{\"threads\":[" + threadsJson + "],\"count\":" + threads.size() + "}";
    }

    private String renderThreadView(io.casehub.drafthouse.debate.ThreadView view) {
        String entriesJson = view.entries().stream()
                .map(e -> "{\"agentRole\":\"" + e.agentRole()
                           + "\",\"content\":" + DraftHouseMcpTools.jsonString(e.content())
                           + ",\"timestamp\":\"" + e.timestamp() + "\"}")
                .collect(Collectors.joining(","));
        return "{\"threadId\":\"" + view.threadId()
               + "\",\"status\":\"" + view.status()
               + "\",\"anchor\":{\"side\":\"" + view.anchor().side()
               + "\",\"startLine\":" + view.anchor().startLine()
               + ",\"endLine\":" + view.anchor().endLine() + "}"
               + ",\"entries\":[" + entriesJson + "]}";
    }

    private String renderThreadCompact(io.casehub.drafthouse.debate.ThreadView view) {
        return "{\"threadId\":\"" + view.threadId()
               + "\",\"status\":\"" + view.status()
               + "\",\"entryCount\":" + view.entries().size()
               + ",\"anchor\":{\"side\":\"" + view.anchor().side()
               + "\",\"startLine\":" + view.anchor().startLine()
               + ",\"endLine\":" + view.anchor().endLine() + "}}";
    }

    private DebateSession resolveSession(String debateSessionId) {
        try {
            UUID channelId = UUID.fromString(debateSessionId);
            return registry.find(channelId).orElse(null);
        } catch (IllegalArgumentException e) { return null; }
    }

    private String sessionError(String id) {
        try {
            UUID.fromString(id);
            return "error: no active debate session for: " + id;
        } catch (IllegalArgumentException e) {
            return "error: invalid session id format: " + id;
        }
    }

    private String roleError(String role) {
        return "error: invalid agentRole '" + role + "' — must be one of: " + VALID_ROLES;
    }

    private static AgentType parseRole(String agentRole) {
        try { return AgentType.valueOf(agentRole); }
        catch (IllegalArgumentException e) { return null; }
    }
}
