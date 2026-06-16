package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.ProjectionResult;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.casehub.drafthouse.debate.AgentType;
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.drafthouse.debate.ReviewState;
import io.casehub.drafthouse.debate.SubTaskFinding;
import io.casehub.drafthouse.debate.SubTaskStatus;
import io.casehub.drafthouse.debate.SubTaskType;
import io.casehub.drafthouse.SelectionScope;
import io.casehub.drafthouse.DocumentSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DebateMcpToolsTest {

    private ChannelService channelService;
    private ChannelGateway channelGateway;
    private InstanceService instanceService;
    private MessageService messageService;
    private ProjectionService projectionService;
    private DebateSessionRegistry registry;
    private DebateChannelProjection debateProjection;
    private DraftHouseConfig config;
    private DebateEventResource debateEventResource;
    private DebateMcpTools tools;

    private Channel stubChannel;

    @BeforeEach
    void setUp() {
        channelService    = mock(ChannelService.class);
        channelGateway    = mock(ChannelGateway.class);
        instanceService   = mock(InstanceService.class);
        messageService    = mock(MessageService.class);
        projectionService = mock(ProjectionService.class);
        registry          = mock(DebateSessionRegistry.class);
        debateProjection  = mock(DebateChannelProjection.class);
        config            = mock(DraftHouseConfig.class);
        debateEventResource = mock(DebateEventResource.class);

        DraftHouseConfig.Context contextConfig = mock(DraftHouseConfig.Context.class);
        when(contextConfig.windowSizeChars()).thenReturn(800_000L);
        when(contextConfig.thresholdPercent()).thenReturn(80.0);
        when(config.context()).thenReturn(contextConfig);

        tools = new DebateMcpTools();
        tools.channelService    = channelService;
        tools.channelGateway    = channelGateway;
        tools.instanceService   = instanceService;
        tools.messageService    = messageService;
        tools.projectionService = projectionService;
        tools.registry          = registry;
        tools.debateProjection  = debateProjection;
        tools.config            = config;
        tools.debateEventResource = debateEventResource;

        stubChannel      = new Channel();
        stubChannel.id   = UUID.randomUUID();
        stubChannel.name = "drafthouse/debate/d-" + UUID.randomUUID();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(stubChannel);

        Instance stubInstance = new Instance();
        stubInstance.id = UUID.randomUUID();
        when(instanceService.register(anyString(), anyString(), any())).thenReturn(stubInstance);
    }

    // ── start_debate ──────────────────────────────────────────────────────────

    @Test
    void startDebate_specPathWithQuote_escapedCorrectlyInJson() {
        String result = tools.startDebate("/path/to/spec \"with quotes\".md");
        assertThat(result).contains("\\\"with quotes\\\"");
        assertThat(result).doesNotContain("\"specPath\":\"/path/to/spec \"");
    }

    @Test
    void startDebate_registryPutBeforeInitChannel() {
        var order = inOrder(registry, channelGateway);
        tools.startDebate("irrelevant-spec.md");
        order.verify(registry).put(any());
        order.verify(channelGateway).initChannel(eq(stubChannel.id), any(ChannelRef.class));
    }

    @Test
    void startDebate_happyPath_sessionFieldsCorrect() {
        String result = tools.startDebate("spec.md");

        assertThat(result).contains(stubChannel.id.toString());
        assertThat(result).contains("spec.md");

        ArgumentCaptor<DebateSession> cap = ArgumentCaptor.forClass(DebateSession.class);
        verify(registry).put(cap.capture());
        DebateSession s = cap.getValue();
        assertThat(s.channelId()).isEqualTo(stubChannel.id);
        assertThat(s.debateSessionId()).isEqualTo(stubChannel.id.toString());
        assertThat(s.channelName()).isEqualTo(stubChannel.name);
        // After startDebate() returns, REV and IMP must be in the participants map
        assertThat(s.instanceIdFor(AgentType.REV))
                .isEqualTo(DebateSession.instanceId(AgentType.REV, s.debateSessionId()));
        assertThat(s.instanceIdFor(AgentType.IMP))
                .isEqualTo(DebateSession.instanceId(AgentType.IMP, s.debateSessionId()));
    }

    @Test
    void startDebate_channelName_hasDebatePrefix() {
        tools.startDebate("spec.md");
        verify(channelService).create(
                argThat(name -> name.startsWith("drafthouse/debate/d-")),
                anyString(), eq(ChannelSemantic.APPEND), isNull());
    }

    // ── session precondition ──────────────────────────────────────────────────

    @Test
    void raisePoint_invalidSessionIdFormat_returnsFormatError() {
        String result = tools.raisePoint("not-a-uuid", "REV", 1, "content", "P1", "ISOLATED", null);
        assertThat(result).startsWith("error: invalid session id format:");
    }

    @Test
    void raisePoint_unknownSession_returnsNotFoundError() {
        when(registry.find(any(UUID.class))).thenReturn(Optional.empty());
        String result = tools.raisePoint(UUID.randomUUID().toString(), "REV", 1, "content", "P1", "ISOLATED", null);
        assertThat(result).startsWith("error: no active debate session for:");
    }

    // ── raise_point ───────────────────────────────────────────────────────────

    @Test
    void raisePoint_dispatchesQueryWithCorrectFields() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.raisePoint(channelId.toString(), "REV", 1,
                "The issue content.", "P1", "ISOLATED", "§3.2");

        assertThat(result).contains("pointId");
        assertThat(result).contains("dispatched");

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        MessageDispatch d = cap.getValue();
        assertThat(d.type()).isEqualTo(MessageType.QUERY);
        assertThat(d.sender()).isEqualTo(session.instanceIdFor(AgentType.REV));
        assertThat(d.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(d.correlationId()).isNotBlank();
        assertThat(d.artefactRefs()).isNull();
        assertThat(d.content()).startsWith(DebateProtocol.META_SENTINEL);
        assertThat(d.content()).contains("entryType=RAISE");
        assertThat(d.content()).contains("agent=REV");
        assertThat(d.content()).contains("round=1");
        assertThat(d.content()).contains("priority=P1");
        assertThat(d.content()).contains("scope=ISOLATED");
        assertThat(d.content()).contains("location=§3.2");
        assertThat(d.content()).endsWith("The issue content.");
    }

    @Test
    void raisePoint_impSender_usesImpInstanceId() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.raisePoint(channelId.toString(), "IMP", 2, "content", "P2", "SYSTEMIC", null);

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().sender()).isEqualTo(session.instanceIdFor(AgentType.IMP));
    }

    @Test
    void raisePoint_supervisorRole_dispatchesCorrectly() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.raisePoint(channelId.toString(), "SUPERVISOR", 1,
                "Meta concern.", "P2", "SYSTEMIC", null);

        assertThat(result).contains("pointId");
        assertThat(result).contains("dispatched");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().sender())
                .isEqualTo(DebateSession.instanceId(AgentType.SUPERVISOR, channelId.toString()));
        assertThat(cap.getValue().content()).contains("agent=SUPERVISOR");
    }

    @Test
    void raisePoint_unknownRole_returnsErrorListingAllValidRoles() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.raisePoint(channelId.toString(), "INVALID_ROLE", 1,
                "content", "P1", "ISOLATED", null);

        assertThat(result).startsWith("error: invalid agentRole 'INVALID_ROLE'");
        // Must list all valid roles in the error message
        for (AgentType role : AgentType.values()) {
            assertThat(result).contains(role.name());
        }
    }

    // ── respond_to ────────────────────────────────────────────────────────────

    @Test
    void respondTo_agree_dispatchesDone() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message();
        stubMsg.id = 99L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        String result = tools.respondTo(channelId.toString(), "IMP", 2, pointId, "agree", "I agree.");

        assertThat(result).contains("dispatched");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        MessageDispatch d = cap.getValue();
        assertThat(d.type()).isEqualTo(MessageType.DONE);
        assertThat(d.inReplyTo()).isEqualTo(99L);
        assertThat(d.correlationId()).isEqualTo(pointId);
        assertThat(d.artefactRefs()).isNull();
        assertThat(d.content()).contains("entryType=AGREE");
        assertThat(d.content()).endsWith("I agree.");
    }

    @Test
    void respondTo_dispute_dispatchesDecline() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message();
        stubMsg.id = 42L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "REV", 3, pointId, "dispute", "No.");

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.DECLINE);
    }

    @Test
    void respondTo_qualify_dispatchesResponse() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 1L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "IMP", 2, pointId, "qualify", "Partly.");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.RESPONSE);
    }

    @Test
    void respondTo_counter_dispatchesResponse() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 1L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "REV", 3, pointId, "counter", "Counter arg.");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.RESPONSE);
        assertThat(cap.getValue().artefactRefs()).isNull();
        assertThat(cap.getValue().content()).contains("entryType=COUNTER");
    }

    @Test
    void respondTo_unknownPointId_returnsError() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        when(messageService.findByCorrelationId(anyString())).thenReturn(Optional.empty());

        String result = tools.respondTo(channelId.toString(), "IMP", 2, "no-such-point", "agree", "Agreed.");
        assertThat(result).startsWith("error: point not found:");
    }

    @Test
    void respondTo_declined_dispatchesDeclinedEntry() {
        UUID chId = stubChannel.id;
        DebateSession session = sessionFor(chId);
        when(registry.find(chId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message();
        stubMsg.id = 1L;
        when(messageService.findByCorrelationId("pt-1")).thenReturn(Optional.of(stubMsg));

        String result = tools.respondTo(chId.toString(), "IMP", 2, "pt-1", "declined", "Cannot engage with this point.");

        assertThat(result).contains("dispatched");
        ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(captor.capture());
        MessageDispatch dispatched = captor.getValue();
        assertThat(dispatched.type()).isEqualTo(MessageType.DECLINE);
        assertThat(dispatched.content()).contains("entryType=DECLINED");
        assertThat(dispatched.content()).contains("Cannot engage with this point.");
    }

    // ── flag_human ────────────────────────────────────────────────────────────

    @Test
    void flagHuman_dispatchesHandoffWithCorrectFields() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 7L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        String result = tools.flagHuman(channelId.toString(), "REV", 3, pointId, "Human clarification needed.");

        assertThat(result).contains("dispatched");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        MessageDispatch d = cap.getValue();
        assertThat(d.type()).isEqualTo(MessageType.HANDOFF);
        assertThat(d.target()).isEqualTo(DraftHouseInstances.HUMAN_INSTANCE_ID);
        assertThat(d.inReplyTo()).isEqualTo(7L);
        assertThat(d.artefactRefs()).isNull();
        assertThat(d.content()).contains("entryType=FLAG_HUMAN");
        assertThat(d.content()).endsWith("Human clarification needed.");
    }

    @Test
    void flagHuman_unknownPointId_returnsError() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        when(messageService.findByCorrelationId(anyString())).thenReturn(Optional.empty());

        String result = tools.flagHuman(channelId.toString(), "REV", 3, "no-point", "reason");
        assertThat(result).startsWith("error: point not found:");
    }

    // ── get_debate_summary ────────────────────────────────────────────────────

    @Test
    void getDebateSummary_delegatesToProjectionAndRenders() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        ReviewState emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
        when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
        when(debateProjection.render(result)).thenReturn("# Summary\n...");

        String summary = tools.getDebateSummary(channelId.toString());
        assertThat(summary).isEqualTo("# Summary\n...");
    }

    // ── end_debate ────────────────────────────────────────────────────────────

    @Test
    void endDebate_removesSessionAndReturnsEnded() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.endDebate(channelId.toString(), false);

        verify(registry).remove(channelId);
        assertThat(result).contains("ended");
        assertThat(result).contains("false");
    }

    @Test
    void endDebate_unknownSession_returnsNotFoundJson() {
        when(registry.find(any(UUID.class))).thenReturn(Optional.empty());

        String result = tools.endDebate(UUID.randomUUID().toString(), false);

        assertThat(result).contains("not-found");
        assertThat(result).doesNotStartWith("error:");
        verifyNoInteractions(channelService);
    }

    @Test
    void endDebate_deregistersAllRegisteredParticipants() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId); // pre-populated with REV + IMP
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.endDebate(channelId.toString(), false);

        verify(instanceService).deregister(session.instanceIdFor(AgentType.REV));
        verify(instanceService).deregister(session.instanceIdFor(AgentType.IMP));
    }

    @Test
    void endDebate_deregistersThirdParticipant_whenSupervisorJoined() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        // SUPERVISOR joins after session creation (lazy registration)
        session.registerIfAbsent(AgentType.SUPERVISOR,
                () -> DebateSession.instanceId(AgentType.SUPERVISOR, channelId.toString()));
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.endDebate(channelId.toString(), false);

        verify(instanceService).deregister(session.instanceIdFor(AgentType.REV));
        verify(instanceService).deregister(session.instanceIdFor(AgentType.IMP));
        verify(instanceService).deregister(session.instanceIdFor(AgentType.SUPERVISOR));
    }

    @Test
    void startDebate_partialFailure_deregistersRegisteredInstancesWhenInitFails() {
        doThrow(new RuntimeException("init failed"))
                .when(channelGateway).initChannel(any(), any());

        String result = tools.startDebate("spec.md");

        assertThat(result).startsWith("error:");
        String debateSessionId = stubChannel.id.toString();
        // Cleanup must deregister all registered participants (REV + IMP were registered before failure)
        verify(instanceService).deregister("drafthouse-rev-" + debateSessionId);
        verify(instanceService).deregister("drafthouse-imp-" + debateSessionId);
    }

    // ── request_subagent (round param) ────────────────────────────────────────

    @Test
    void requestSubagent_encodesRoundInContent() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        tools.requestSubagent(channelId.toString(), "REV", "VERIFY", "pt-1", 3, null);

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().content()).contains("entryType=SUB_TASK_REQUEST");
        assertThat(cap.getValue().content()).contains("round=3");
    }

    @Test
    void requestSubagent_encodesRoundAndPointId() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        tools.requestSubagent(channelId.toString(), "IMP", "ARBITRATE", "pt-42", 7, null);

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        String content = cap.getValue().content();
        assertThat(content).contains("round=7");
        assertThat(content).contains("pointId=pt-42");
    }

    // ── get_debate_summary_at_round ───────────────────────────────────────────

    @Test
    void getDebateSummaryAtRound_rejectsRoundZero() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.getDebateSummaryAtRound(channelId.toString(), 0);
        assertThat(result).startsWith("error:");
    }

    @Test
    void getDebateSummaryAtRound_rejectsNegativeRound() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.getDebateSummaryAtRound(channelId.toString(), -1);
        assertThat(result).startsWith("error:");
    }

    @Test
    void getDebateSummaryAtRound_emptyBoundedState_returnsNoneMessage() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));
        ReviewState empty = emptyState();
        when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(empty, 99L));

        String result = tools.getDebateSummaryAtRound(channelId.toString(), 2);
        assertThat(result).contains("No debate activity up to round 2");
    }

    @Test
    void getDebateSummaryAtRound_delegatesToProjectionService() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));
        when(projectionService.project(eq(channelId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));

        tools.getDebateSummaryAtRound(channelId.toString(), 2);

        verify(projectionService).project(eq(channelId), any());
    }

    @Test
    void getDebateSummaryAtRound_unknownSession_returnsError() {
        when(registry.find(any(UUID.class))).thenReturn(Optional.empty());
        String result = tools.getDebateSummaryAtRound(UUID.randomUUID().toString(), 2);
        assertThat(result).startsWith("error:");
    }

    // ── restart_from_round ────────────────────────────────────────────────────

    @Test
    void restartFromRound_rejectsRoundZero() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.restartFromRound(channelId.toString(), 0);
        assertThat(result).startsWith("error:");
    }

    @Test
    void restartFromRound_rejectsNegativeRound() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.restartFromRound(channelId.toString(), -1);
        assertThat(result).startsWith("error:");
    }

    @Test
    void restartFromRound_createsNewSession_responseContainsBothSessionIds() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));
        when(projectionService.project(eq(originalId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));
        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);

        String result = tools.restartFromRound(originalId.toString(), 2);

        assertThat(result).contains("newDebateSessionId");
        assertThat(result).contains("originalDebateSessionId");
        assertThat(result).contains(originalId.toString());
        assertThat(result).contains(newCh.id.toString());
    }

    @Test
    void restartFromRound_registersNewSession_notOriginal() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));
        when(projectionService.project(eq(originalId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));
        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);

        tools.restartFromRound(originalId.toString(), 2);

        ArgumentCaptor<DebateSession> cap = ArgumentCaptor.forClass(DebateSession.class);
        verify(registry).put(cap.capture());
        assertThat(cap.getValue().channelId()).isEqualTo(newCh.id);
        assertThat(cap.getValue().channelId()).isNotEqualTo(originalId);
    }

    @Test
    void restartFromRound_postsRestartContextMarkerToNewChannel() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));
        when(projectionService.project(eq(originalId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));
        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);

        tools.restartFromRound(originalId.toString(), 2);

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, atLeastOnce()).dispatch(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(d ->
                newCh.id.equals(d.channelId())
                && d.content() != null
                && d.content().contains("RESTART_CONTEXT")
                && d.content().contains("originChannelId=" + originalId)
                && d.content().contains("originRound=2"));
    }

    @Test
    void restartFromRound_lazyRegistersRevForNewSession() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));
        when(projectionService.project(eq(originalId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));
        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);

        tools.restartFromRound(originalId.toString(), 2);

        // RESTART_CONTEXT marker is sent as REV — REV must be registered for the new session
        String expectedRevId = DebateSession.instanceId(AgentType.REV, newCh.id.toString());
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService, atLeastOnce()).dispatch(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(d ->
                newCh.id.equals(d.channelId()) && expectedRevId.equals(d.sender()));
    }

    @Test
    void restartFromRound_emptyBoundedState_summaryContainsNoneMessage() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));
        when(projectionService.project(eq(originalId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));
        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);

        String result = tools.restartFromRound(originalId.toString(), 2);
        assertThat(result).contains("No debate activity up to round 2");
    }

    @Test
    void restartFromRound_failure_cleansUpNewChannel() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));
        when(projectionService.project(eq(originalId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));
        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);
        doThrow(new RuntimeException("marker post failed")).when(messageService).dispatch(any());

        String result = tools.restartFromRound(originalId.toString(), 2);

        assertThat(result).startsWith("error:");
        verify(channelService).delete(eq(newCh.id), eq(true));
        // REV was registered via sender() before dispatch() threw — must be deregistered
        verify(instanceService).deregister(DebateSession.instanceId(AgentType.REV, newCh.id.toString()));
    }

    @Test
    void restartFromRound_doesNotTouchOriginalSession() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));
        when(projectionService.project(eq(originalId), any()))
                .thenReturn(new ProjectionResult<>(emptyState(), null));
        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);

        tools.restartFromRound(originalId.toString(), 2);

        verify(registry, never()).remove(originalId);
        verify(instanceService, never()).deregister(contains(originalId.toString()));
    }

    @Test
    void restartFromRound_findingsComplete_separatesCompletedFromPending() {
        UUID originalId = stubChannel.id;
        when(registry.find(originalId)).thenReturn(Optional.of(sessionFor(originalId)));

        ReviewState boundedState = stateWithFindings(
                finding("f1", SubTaskStatus.COMPLETE),
                finding("f2", SubTaskStatus.COMPLETE),
                finding("f3", SubTaskStatus.PENDING));
        ReviewState fullState = stateWithFindings(
                finding("f1", SubTaskStatus.COMPLETE),
                finding("f2", SubTaskStatus.COMPLETE),
                finding("f3", SubTaskStatus.PENDING),
                finding("f4", SubTaskStatus.COMPLETE),
                finding("f5", SubTaskStatus.COMPLETE));

        Channel newCh = newChannel();
        when(channelService.create(anyString(), anyString(), eq(ChannelSemantic.APPEND), isNull()))
                .thenReturn(newCh);
        when(projectionService.project(eq(originalId), isA(DebateChannelProjection.RoundBoundedProjection.class)))
                .thenReturn(new ProjectionResult<>(boundedState, 10L));
        when(projectionService.project(eq(originalId), eq(debateProjection)))
                .thenReturn(new ProjectionResult<>(fullState, 20L));

        String result = tools.restartFromRound(originalId.toString(), 2);

        assertThat(result).contains("\"findingsComplete\":2");
        assertThat(result).contains("\"findingsPending\":1");
        assertThat(result).contains("\"findingsInOriginalOnly\":2");
        assertThat(result).doesNotContain("findingsIncluded");
    }

    // ── report_context ────────────────────────────────────────────────────────

    @Test
    void reportContext_validSession_returnsOk() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.reportContext(channelId.toString(), 42.0);

        assertThat(result).contains("\"status\":\"ok\"");
        assertThat(result).contains("\"effectivePercent\":42.0");
    }

    @Test
    void reportContext_thresholdExceeded_returnsWarning() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.reportContext(channelId.toString(), 85.0);

        assertThat(result).contains("\"status\":\"warning\"");
        assertThat(result).contains("\"effectivePercent\":85.0");
        assertThat(result).contains("consider committing state");
    }

    @Test
    void reportContext_negativePercent_clampedToZero() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.reportContext(channelId.toString(), -5.0);

        assertThat(result).contains("\"status\":\"ok\"");
        assertThat(result).contains("\"effectivePercent\":0.0");
    }

    @Test
    void reportContext_over100_accepted() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        String result = tools.reportContext(channelId.toString(), 120.0);

        assertThat(result).contains("\"status\":\"warning\"");
        assertThat(result).contains("\"effectivePercent\":120.0");
    }

    @Test
    void reportContext_invalidSession_returnsError() {
        when(registry.find(any(UUID.class))).thenReturn(Optional.empty());

        String result = tools.reportContext(UUID.randomUUID().toString(), 50.0);

        assertThat(result).startsWith("error:");
    }

    @Test
    void reportContext_pushesSnapshotToEventResource() {
        UUID channelId = stubChannel.id;
        when(registry.find(channelId)).thenReturn(Optional.of(sessionFor(channelId)));

        tools.reportContext(channelId.toString(), 42.0);

        verify(debateEventResource).pushContextSnapshot(eq(channelId), any(ContextSnapshot.class));
    }

    // ── context tracking at dispatch sites ────────────────────────────────────

    @Test
    void raisePoint_tracksContextContribution() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.raisePoint(channelId.toString(), "REV", 1, "content", "P1", "ISOLATED", null);

        assertThat(session.contextTracker().snapshot(800_000, 80.0).serverContributionChars()).isGreaterThan(0);
        assertThat(session.contextTracker().snapshot(800_000, 80.0).messageCount()).isEqualTo(1);
        verify(debateEventResource).pushContextSnapshot(eq(channelId), any(ContextSnapshot.class));
    }

    @Test
    void respondTo_tracksContextContribution() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 1L;
        when(messageService.findByCorrelationId(anyString())).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "IMP", 2, "pt-1", "agree", "Agreed.");

        assertThat(session.contextTracker().snapshot(800_000, 80.0).serverContributionChars()).isGreaterThan(0);
        verify(debateEventResource).pushContextSnapshot(eq(channelId), any(ContextSnapshot.class));
    }

    @Test
    void getDebateSummary_includesSelectionContext() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        session.updateSelection(new SelectionScope(DocumentSide.A, 5, 12, "The selected passage."));
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        ReviewState emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
        when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
        when(debateProjection.render(result)).thenReturn("# Summary");

        String summary = tools.getDebateSummary(channelId.toString());

        assertThat(summary).contains("## Active Selection");
        assertThat(summary).contains("**Document A**, lines 5–12:");
        assertThat(summary).contains("The selected passage.");
    }

    @Test
    void getDebateSummary_noSelection_noSelectionSection() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        ReviewState emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
        when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
        when(debateProjection.render(result)).thenReturn("# Summary");

        String summary = tools.getDebateSummary(channelId.toString());

        assertThat(summary).doesNotContain("Active Selection");
    }

    @Test
    void getDebateSummary_selectionWithZeroLines_omitsLineNumbers() {
        UUID channelId = stubChannel.id;
        DebateSession session = sessionFor(channelId);
        session.updateSelection(new SelectionScope(DocumentSide.B, 0, 0, "Review text only."));
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        ReviewState emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
        ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
        when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
        when(debateProjection.render(result)).thenReturn("# Summary");

        String summary = tools.getDebateSummary(channelId.toString());

        assertThat(summary).contains("## Active Selection");
        assertThat(summary).contains("**Document B**:");
        assertThat(summary).doesNotContain("lines 0");
        assertThat(summary).contains("Review text only.");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a DebateSession pre-populated with REV and IMP participants (no instanceService call).
     * Pre-population prevents unexpected instanceService.register() calls in tests that only need
     * a session object with known participant IDs.
     */
    private DebateSession sessionFor(final UUID channelId) {
        final DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-" + channelId, "spec.md");
        session.registerIfAbsent(AgentType.REV,
                () -> DebateSession.instanceId(AgentType.REV, channelId.toString()));
        session.registerIfAbsent(AgentType.IMP,
                () -> DebateSession.instanceId(AgentType.IMP, channelId.toString()));
        return session;
    }

    private static ReviewState emptyState() {
        return new ReviewState(Map.of(), List.of(), List.of(), Map.of());
    }

    private static Channel newChannel() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "drafthouse/debate/d-new-" + ch.id;
        return ch;
    }

    private static ReviewState stateWithFindings(SubTaskFinding... findings) {
        Map<String, SubTaskFinding> map = new LinkedHashMap<>();
        for (final SubTaskFinding f : findings) map.put(f.subTaskId(), f);
        return new ReviewState(Map.of(), List.of(), List.of(), map);
    }

    private static SubTaskFinding finding(final String id, final SubTaskStatus status) {
        return new SubTaskFinding(id, SubTaskType.VERIFY, "REV", null,
                status == SubTaskStatus.COMPLETE ? "the finding text" : null,
                status == SubTaskStatus.ERROR ? "error occurred" : null,
                status);
    }
}
