package io.casehub.drafthouse;

import io.casehub.agency.communication.dispatch.ChannelMessageMeta;
import io.casehub.agency.deliberation.conversation.ConversationProtocol;
import io.casehub.agency.deliberation.conversation.ConversationState;
import io.casehub.agency.deliberation.conversation.Priority;
import io.casehub.drafthouse.debate.AgentType;
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

@ApplicationScoped
@Path("/api/debate/{debateSessionId}/human")
public class HumanActionResource {

    private static final Logger LOG = Logger.getLogger(HumanActionResource.class.getName());

    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;
    @Inject InstanceService instanceService;
    @Inject ProjectionService projectionService;
    @Inject DebateChannelProjection debateProjection;
    @Inject DecisionFileWriter decisionFileWriter;

    record CommentRequest(String pointId, String content) {}
    record RaiseRequest(String content, String priority, String location,
                        String side, Integer startLine, Integer endLine, String selectedText) {}
    record OverrideRequest(String pointId, String reason) {}
    record PrioritiseRequest(String pointId, String newPriority) {}
    record BatchRequest(List<String> pointIds, String verdict) {}

    record ThreadStartRequest(String content, String side, int startLine, int endLine, String selectedText) {}

    record ThreadReplyRequest(String content) {}


    @POST @Path("/comment")
    @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response comment(@PathParam("debateSessionId") String debateSessionId,
                            CommentRequest request) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return notFound(debateSessionId);
        if (request == null || request.pointId() == null
                || request.content() == null || request.content().isBlank())
            return badRequest("pointId and content are required");

        Long inReplyTo = messageService.findByCorrelationId(request.pointId()).map(m -> m.id()).orElse(null);
        if (inReplyTo == null) return badRequest("point not found: " + request.pointId());

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);
        int round = currentRound(session);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ConversationProtocol.ENTRY_TYPE, "COMMENT");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        meta.put(ConversationProtocol.ROUND, String.valueOf(round));
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, request.content());

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender)
                .type(MessageType.RESPONSE)
                .content(encoded)
                .correlationId(request.pointId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.HUMAN)
                .build());

        decisionFileWriter.append(session.workspacePath(), round, "Comments",
                request.pointId(), request.content());

        return Response.ok("{\"status\":\"ok\"}").build();
    }

    @POST @Path("/raise")
    @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response raise(@PathParam("debateSessionId") String debateSessionId,
                          RaiseRequest request) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return notFound(debateSessionId);
        if (request == null || request.content() == null || request.content().isBlank())
            return badRequest("content is required");

        String priorityStr = parsePriorityLabel(request.priority());
        if (priorityStr == null) return badRequest("priority must be P1, P2, or P3");

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);
        int round = currentRound(session);
        String pointId = UUID.randomUUID().toString();

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ConversationProtocol.ENTRY_TYPE, "RAISE");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        meta.put(ConversationProtocol.ROUND, String.valueOf(round));
        meta.put(ConversationProtocol.PRIORITY, priorityStr);
        if (request.location() != null && !request.location().isBlank()) {
            meta.put(ConversationProtocol.LOCATION, request.location());
        }
        if (request.side() != null) meta.put("side", request.side());
        if (request.startLine() != null) meta.put("startLine", String.valueOf(request.startLine()));
        if (request.endLine() != null) meta.put("endLine", String.valueOf(request.endLine()));

        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, request.content());

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender)
                .type(MessageType.QUERY)
                .content(encoded)
                .correlationId(pointId)
                .actorType(ActorType.HUMAN)
                .build());

        String location = request.location() != null ? request.location() : "";
        if (request.startLine() != null && request.endLine() != null) {
            location = (location.isEmpty() ? "" : location + ", ")
                    + "lines " + request.startLine() + "-" + request.endLine();
        }
        String shortId = pointId.length() > 8 ? pointId.substring(0, 8) : pointId;
        String header = "H-" + shortId + (location.isEmpty() ? "" : " — " + location);
        String body = request.priority() != null
                ? "**Priority:** " + request.priority() + "\n" + request.content()
                : request.content();
        decisionFileWriter.append(session.workspacePath(), round, "New Points", header, body);

        return Response.ok("{\"status\":\"ok\",\"pointId\":\"" + pointId + "\"}").build();
    }

    @POST @Path("/override")
    @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response override(@PathParam("debateSessionId") String debateSessionId,
                             OverrideRequest request) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return notFound(debateSessionId);
        if (request == null || request.pointId() == null
                || request.reason() == null || request.reason().isBlank())
            return badRequest("pointId and reason are required");

        Long inReplyTo = messageService.findByCorrelationId(request.pointId()).map(m -> m.id()).orElse(null);
        if (inReplyTo == null) return badRequest("point not found: " + request.pointId());

        ConversationState state = projectState(session);
        var point = state.points().get(request.pointId());
        if (point != null && isResolved(point.status())) {
            return Response.status(409)
                    .entity("{\"error\":\"point already resolved: " + point.status() + "\"}")
                    .build();
        }

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);
        int round = currentRound(session);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ConversationProtocol.ENTRY_TYPE, "HUMAN_OVERRIDE");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        meta.put(ConversationProtocol.ROUND, String.valueOf(round));
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, request.reason());

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender)
                .type(MessageType.DONE)
                .content(encoded)
                .correlationId(request.pointId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.HUMAN)
                .build());

        decisionFileWriter.append(session.workspacePath(), round, "Overrides",
                request.pointId() + " → HUMAN_OVERRIDE", request.reason());

        return Response.ok("{\"status\":\"ok\"}").build();
    }

    @POST @Path("/prioritise")
    @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response prioritise(@PathParam("debateSessionId") String debateSessionId,
                               PrioritiseRequest request) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return notFound(debateSessionId);
        if (request == null || request.pointId() == null || request.newPriority() == null)
            return badRequest("pointId and newPriority are required");

        String priorityStr = parsePriorityLabel(request.newPriority());
        if (priorityStr == null) return badRequest("newPriority must be P1, P2, or P3");

        Long inReplyTo = messageService.findByCorrelationId(request.pointId()).map(m -> m.id()).orElse(null);
        if (inReplyTo == null) return badRequest("point not found: " + request.pointId());

        ConversationState state = projectState(session);
        var point = state.points().get(request.pointId());
        if (point != null && point.classification().priority().name().equals(priorityStr)) {
            return badRequest("point already has priority " + request.newPriority());
        }

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);
        int round = currentRound(session);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ConversationProtocol.ENTRY_TYPE, "REPRIORITISE");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        meta.put(ConversationProtocol.ROUND, String.valueOf(round));
        meta.put(ConversationProtocol.PRIORITY, priorityStr);
        String content = "Priority changed to " + request.newPriority();
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, content);

        messageService.dispatch(MessageDispatch.builder()
                .channelId(session.channelId())
                .sender(sender)
                .type(MessageType.RESPONSE)
                .content(encoded)
                .correlationId(request.pointId())
                .inReplyTo(inReplyTo)
                .actorType(ActorType.HUMAN)
                .build());

        String oldPriority = point != null ? labelForPriority(point.classification().priority()) : "?";
        decisionFileWriter.append(session.workspacePath(), round, "Priority Changes",
                request.pointId() + " → " + request.newPriority() + " (was " + oldPriority + ")", content);

        return Response.ok("{\"status\":\"ok\"}").build();
    }

    @POST @Path("/batch")
    @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Response batch(@PathParam("debateSessionId") String debateSessionId,
                          BatchRequest request) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) return notFound(debateSessionId);
        if (request == null || request.pointIds() == null || request.pointIds().isEmpty())
            return badRequest("pointIds must be non-empty");
        if (request.verdict() == null
                || (!"VERIFIED".equals(request.verdict()) && !"DEFERRED".equals(request.verdict())))
            return badRequest("verdict must be VERIFIED or DEFERRED");

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);
        int round = currentRound(session);
        MessageType msgType = "VERIFIED".equals(request.verdict()) ? MessageType.DONE : MessageType.DECLINE;

        for (String pointId : request.pointIds()) {
            Long inReplyTo = messageService.findByCorrelationId(pointId).map(m -> m.id()).orElse(null);
            if (inReplyTo == null) continue;

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(ConversationProtocol.ENTRY_TYPE, request.verdict());
            meta.put(ConversationProtocol.ROLE, "HUMAN");
            meta.put(ConversationProtocol.ROUND, String.valueOf(round));
            String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta,
                    "Batch " + request.verdict().toLowerCase());

            messageService.dispatch(MessageDispatch.builder()
                    .channelId(session.channelId())
                    .sender(sender)
                    .type(msgType)
                    .content(encoded)
                    .correlationId(pointId)
                    .inReplyTo(inReplyTo)
                    .actorType(ActorType.HUMAN)
                    .build());
        }

        String label = "VERIFIED".equals(request.verdict()) ? "Approved" : "Deferred";
        decisionFileWriter.append(session.workspacePath(), round, "Batch Decisions",
                label, label + ": " + String.join(", ", request.pointIds()));

        return Response.ok("{\"status\":\"ok\"}").build();
    }

    @POST
    @Path("/thread")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response startThread(@PathParam("debateSessionId") String debateSessionId,
                                ThreadStartRequest request) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) {return notFound(debateSessionId);}
        if (request == null || request.content() == null || request.content().isBlank()) {
            return badRequest("content is required");
        }
        if (request.selectedText() == null || request.selectedText().isBlank()) {
            return badRequest("selectedText is required");
        }

        DocumentSide docSide;
        try {docSide = DocumentSide.valueOf(request.side());} catch (IllegalArgumentException |
                                                                     NullPointerException e) {
            return badRequest("invalid side: " + request.side());
        }

        SelectionScope anchor;
        try {
            anchor = new SelectionScope(docSide, request.startLine(), request.endLine(), request.selectedText());
        } catch (IllegalArgumentException e) {return badRequest(e.getMessage());}

        String threadId = session.startThread(anchor);
        registry.persist(session);

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", threadId);
        meta.put("threadAction", "START");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        meta.put("side", request.side());
        meta.put("startLine", String.valueOf(request.startLine()));
        meta.put("endLine", String.valueOf(request.endLine()));
        meta.put("selectedText", request.selectedText());
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, request.content());

        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(session.channelId())
                                               .sender(sender)
                                               .type(MessageType.QUERY)
                                               .content(encoded)
                                               .correlationId(threadId)
                                               .actorType(ActorType.HUMAN)
                                               .build());

        return Response.ok("{\"status\":\"ok\",\"threadId\":\"" + threadId + "\"}").build();
    }

    @POST
    @Path("/thread/{threadId}/reply")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response replyToThread(@PathParam("debateSessionId") String debateSessionId,
                                  @PathParam("threadId") String threadId,
                                  ThreadReplyRequest request) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) {return notFound(debateSessionId);}
        if (request == null || request.content() == null || request.content().isBlank()) {
            return badRequest("content is required");
        }

        SelectionThread thread = session.threads().get(threadId);
        if (thread == null) {return badRequest("thread not found: " + threadId);}
        if (thread.status() == ThreadStatus.RESOLVED) {return badRequest("thread is resolved: " + threadId);}

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", threadId);
        meta.put("threadAction", "REPLY");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, request.content());

        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(session.channelId())
                                               .sender(sender)
                                               .type(MessageType.RESPONSE)
                                               .content(encoded)
                                               .correlationId(threadId)
                                               .actorType(ActorType.HUMAN)
                                               .build());

        return Response.ok("{\"status\":\"ok\"}").build();
    }

    @POST
    @Path("/thread/{threadId}/resolve")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resolveThread(@PathParam("debateSessionId") String debateSessionId,
                                  @PathParam("threadId") String threadId) {
        DebateSession session = resolveSession(debateSessionId);
        if (session == null) {return notFound(debateSessionId);}

        try {session.resolveThread(threadId);} catch (IllegalArgumentException e) {return badRequest(e.getMessage());}
        registry.persist(session);

        String sender = DebateParticipants.ensureSender(session, AgentType.HUMAN, instanceService, registry);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", threadId);
        meta.put("threadAction", "RESOLVE");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, "");

        messageService.dispatch(MessageDispatch.builder()
                                               .channelId(session.channelId())
                                               .sender(sender)
                                               .type(MessageType.DONE)
                                               .content(encoded)
                                               .correlationId(threadId)
                                               .actorType(ActorType.HUMAN)
                                               .build());

        return Response.ok("{\"status\":\"ok\"}").build();
    }


    private DebateSession resolveSession(String debateSessionId) {
        try {
            UUID channelId = UUID.fromString(debateSessionId);
            return registry.find(channelId).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int currentRound(DebateSession session) {
        try {
            ConversationState state = projectState(session);
            return state.points().values().stream()
                    .flatMap(p -> p.thread().stream())
                    .mapToInt(e -> e.round())
                    .max()
                    .orElse(1);
        } catch (Exception e) {
            return 1;
        }
    }

    private ConversationState projectState(DebateSession session) {
        return projectionService.project(session.channelId(), debateProjection).state();
    }

    private static boolean isResolved(String status) {
        return Set.of("AGREED", "DECLINED", "VERIFIED", "DEFERRED", "HUMAN_OVERRIDE").contains(status);
    }

    private static String parsePriorityLabel(String label) {
        if (label == null) return null;
        return switch (label.toUpperCase()) {
            case "P1", "HIGH" -> "HIGH";
            case "P2", "MEDIUM" -> "MEDIUM";
            case "P3", "LOW" -> "LOW";
            default -> null;
        };
    }

    private static String labelForPriority(Priority p) {
        return switch (p) {
            case HIGH -> "P1";
            case MEDIUM -> "P2";
            case LOW -> "P3";
        };
    }

    private static Response notFound(String id) {
        return Response.status(404).entity("{\"error\":\"session not found: " + id + "\"}").build();
    }

    private static Response badRequest(String msg) {
        return Response.status(400).entity("{\"error\":\"" + msg + "\"}").build();
    }
}
