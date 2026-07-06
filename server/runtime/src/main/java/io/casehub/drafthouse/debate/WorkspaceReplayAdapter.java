package io.casehub.drafthouse.debate;

import io.casehub.blocks.channel.ChannelMessageMeta;
import io.casehub.blocks.conversation.ConversationProtocol;
import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.WebSocketEventBus;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;

import java.util.*;
import java.util.logging.Logger;

public class WorkspaceReplayAdapter {

    private static final Logger LOG = Logger.getLogger(WorkspaceReplayAdapter.class.getName());
    private static final String HUMAN_INSTANCE_ID = "drafthouse-human";

    public record ReplayResult(int entryCount, Map<String, String> statusDistribution) {}

    private final MessageService messageService;
    private final InstanceService instanceService;
    private final ChannelGateway channelGateway;
    private final WebSocketEventBus eventBus;

    public WorkspaceReplayAdapter(MessageService messageService,
                                   InstanceService instanceService,
                                   ChannelGateway channelGateway,
                                   WebSocketEventBus eventBus) {
        this.messageService = messageService;
        this.instanceService = instanceService;
        this.channelGateway = channelGateway;
        this.eventBus = eventBus;
    }

    public ReplayResult replay(DebateSession session,
                                WorkspaceParser.WorkspaceParseResult parseResult) {

        UUID channelId = session.channelId();
        String revSender = registerSender(session, AgentType.REV);
        String impSender = registerSender(session, AgentType.IMP);

        channelGateway.initChannel(channelId,
                new ChannelRef(channelId, session.channelName()));

        Map<String, Long> raiseMessageIds = new HashMap<>();
        int entryCount = 0;

        for (var round : parseResult.rounds()) {
            int n = round.roundNumber();

            // 1. RAISE entries
            for (var issue : round.issues()) {
                String location = extractLocation(issue.body());
                if (location == null) {
                    location = findLocationFromResponses(issue.issueId(), round.responses());
                }

                var meta = buildMeta("RAISE", "REV", n, null, null, location);
                String encoded = ChannelMessageMeta.encode(
                        DebateProtocol.META_SENTINEL, meta, issue.title() + "\n\n" + issue.body());

                DispatchResult dr = messageService.dispatch(MessageDispatch.builder()
                        .channelId(channelId)
                        .sender(revSender)
                        .type(MessageType.QUERY)
                        .content(encoded)
                        .correlationId(issue.issueId())
                        .actorType(ActorType.AGENT)
                        .build());

                raiseMessageIds.put(issue.issueId(), dr.messageId());
                entryCount++;
            }

            // 2. QUALIFY / COUNTER / FLAG_HUMAN entries
            for (var resp : round.responses()) {
                String entryType = switch (resp.status()) {
                    case "FIXED" -> "QUALIFY";
                    case "REJECTED" -> "COUNTER";
                    case "ESCALATED" -> "FLAG_HUMAN";
                    default -> "QUALIFY";
                };
                MessageType msgType = switch (resp.status()) {
                    case "FIXED" -> MessageType.RESPONSE;
                    case "REJECTED" -> MessageType.RESPONSE;
                    case "ESCALATED" -> MessageType.HANDOFF;
                    default -> MessageType.RESPONSE;
                };

                var meta = buildMeta(entryType, "IMP", n, null, null, null);
                String content = resp.body().isEmpty() ? resp.rationale() : resp.body();
                String encoded = ChannelMessageMeta.encode(
                        DebateProtocol.META_SENTINEL, meta, content);

                Long inReplyTo = raiseMessageIds.get(resp.issueId());
                var dispatchBuilder = MessageDispatch.builder()
                        .channelId(channelId)
                        .sender(impSender)
                        .type(msgType)
                        .content(encoded)
                        .correlationId(resp.issueId())
                        .inReplyTo(inReplyTo)
                        .actorType(ActorType.AGENT);

                if (msgType == MessageType.HANDOFF) {
                    dispatchBuilder.target(HUMAN_INSTANCE_ID);
                }

                messageService.dispatch(dispatchBuilder.build());
                entryCount++;
            }

            // 3. VERIFIED / DISPUTE / AGREE entries (from next round's reviewer)
            for (var conf : round.confirmations()) {
                String entryType;
                MessageType msgType;
                String content;

                if (conf.resolved()) {
                    entryType = "VERIFIED";
                    msgType = MessageType.DONE;
                    content = "Fix verified.";
                } else if (conf.accepted()) {
                    entryType = "AGREE";
                    msgType = MessageType.DONE;
                    content = "Rejection accepted.";
                } else {
                    entryType = "DISPUTE";
                    msgType = MessageType.DECLINE;
                    content = conf.reason().isEmpty() ? "Still open." : conf.reason();
                }

                var meta = buildMeta(entryType, "REV", n + 1, null, null, null);
                String encoded = ChannelMessageMeta.encode(
                        DebateProtocol.META_SENTINEL, meta, content);

                Long inReplyTo = raiseMessageIds.get(conf.issueId());
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(channelId)
                        .sender(revSender)
                        .type(msgType)
                        .content(encoded)
                        .correlationId(conf.issueId())
                        .inReplyTo(inReplyTo)
                        .actorType(ActorType.AGENT)
                        .build());
                entryCount++;
            }

            // 4. MEMO entries (assumptions + settled decisions)
            for (String assumption : round.assumptions()) {
                entryCount += dispatchMemo(channelId, revSender, n,
                        "ASSUMPTION: " + assumption);
            }
            for (var sd : round.settledDecisions()) {
                String text = "SETTLED: " + sd.text();
                if (!sd.fromIssue().isEmpty()) text += " (from " + sd.fromIssue() + ")";
                entryCount += dispatchMemo(channelId, revSender, n, text);
            }
        }

        // 5. DEFERRED entries (from tracker terminal statuses)
        for (var te : parseResult.trackerStatuses()) {
            if ("DEFERRED".equals(te.status())) {
                int deferredRound = findDeferredRound(te.issueId(), parseResult);
                var meta = buildMeta("DEFERRED", "REV", deferredRound, null, null, null);
                String encoded = ChannelMessageMeta.encode(
                        DebateProtocol.META_SENTINEL, meta, "Issue deferred.");

                Long inReplyTo = raiseMessageIds.get(te.issueId());
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(channelId)
                        .sender(revSender)
                        .type(MessageType.DECLINE)
                        .content(encoded)
                        .correlationId(te.issueId())
                        .inReplyTo(inReplyTo)
                        .actorType(ActorType.AGENT)
                        .build());
                entryCount++;
            }
        }

        // 6. Evidence MEMO entries
        for (var te : parseResult.trackerStatuses()) {
            if (te.evidence() != null) {
                int evidenceRound = findEvidenceRound(te.issueId(), parseResult);
                entryCount += dispatchMemo(channelId, revSender, evidenceRound,
                        te.issueId() + ": spec commit " + te.evidence());
            }
        }

        // Batch push to WebSocket
        var messages = messageService.pollAfter(channelId, 0L, Integer.MAX_VALUE);
        var entries = messages.stream()
                .map(DebateStreamEntry::from)
                .filter(Objects::nonNull)
                .toList();
        eventBus.pushDebateEntries(channelId, entries);

        Map<String, String> statusDist = new LinkedHashMap<>();
        for (var te : parseResult.trackerStatuses()) {
            statusDist.merge(te.status(), "1",
                    (a, b) -> String.valueOf(Integer.parseInt(a) + 1));
        }

        return new ReplayResult(entryCount, statusDist);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String registerSender(DebateSession session, AgentType role) {
        return session.registerIfAbsent(role, () -> {
            String id = DebateSession.instanceId(role, session.debateSessionId());
            instanceService.register(id,
                    "DraftHouse replay " + role.name().toLowerCase() + " " + session.debateSessionId(),
                    List.of("document-debate-" + role.name().toLowerCase()));
            return id;
        });
    }

    private int dispatchMemo(UUID channelId, String sender, int round, String content) {
        var meta = buildMeta("MEMO", "REV", round, null, null, null);
        String encoded = ChannelMessageMeta.encode(DebateProtocol.META_SENTINEL, meta, content);
        messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender(sender)
                .type(MessageType.STATUS)
                .content(encoded)
                .actorType(ActorType.AGENT)
                .build());
        return 1;
    }

    private static Map<String, String> buildMeta(String entryType, String role, int round,
                                                  String priority, String scope, String location) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ConversationProtocol.ENTRY_TYPE, entryType);
        meta.put(ConversationProtocol.ROLE, role);
        meta.put(ConversationProtocol.ROUND, String.valueOf(round));
        if (priority != null) meta.put(ConversationProtocol.PRIORITY, priority);
        if (scope != null) meta.put(ConversationProtocol.SCOPE, scope);
        if (location != null) meta.put(ConversationProtocol.LOCATION, location);
        return meta;
    }

    private static String extractLocation(String body) {
        var m = java.util.regex.Pattern.compile("§(\\d+(?:\\.\\d+)*)|[Ss]ection\\s+(\\d+(?:\\.\\d+)*)")
                .matcher(body);
        if (m.find()) {
            String ref = m.group(1) != null ? m.group(1) : m.group(2);
            return "§" + ref;
        }
        return null;
    }

    private static String findLocationFromResponses(String issueId,
                                                     List<WorkspaceParser.ParsedResponse> responses) {
        for (var r : responses) {
            if (r.issueId().equals(issueId) && r.sectionRef() != null) {
                return "§" + r.sectionRef();
            }
        }
        return null;
    }

    private static int findDeferredRound(String issueId,
                                          WorkspaceParser.WorkspaceParseResult result) {
        for (var round : result.rounds()) {
            for (var conf : round.confirmations()) {
                if (conf.issueId().equals(issueId) && !conf.resolved() && !conf.accepted()) {
                    return round.roundNumber() + 1;
                }
            }
        }
        for (var round : result.rounds()) {
            for (var issue : round.issues()) {
                if (issue.issueId().equals(issueId)) return round.roundNumber();
            }
        }
        return 1;
    }

    private static int findEvidenceRound(String issueId,
                                          WorkspaceParser.WorkspaceParseResult result) {
        for (var round : result.rounds()) {
            for (var resp : round.responses()) {
                if (resp.issueId().equals(issueId) && "FIXED".equals(resp.status())) {
                    return round.roundNumber();
                }
            }
        }
        for (var round : result.rounds()) {
            for (var issue : round.issues()) {
                if (issue.issueId().equals(issueId)) return round.roundNumber();
            }
        }
        return 1;
    }
}
