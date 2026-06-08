package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import io.casehub.drafthouse.debate.DebateChannelProjection;
import io.casehub.drafthouse.debate.DebateProtocol;
import io.casehub.drafthouse.debate.ReviewState;
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

        tools = new DebateMcpTools();
        tools.channelService    = channelService;
        tools.channelGateway    = channelGateway;
        tools.instanceService   = instanceService;
        tools.messageService    = messageService;
        tools.projectionService = projectionService;
        tools.registry          = registry;
        tools.debateProjection  = debateProjection;

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
        assertThat(s.revInstanceId()).isEqualTo("drafthouse-rev-" + s.debateSessionId());
        assertThat(s.impInstanceId()).isEqualTo("drafthouse-imp-" + s.debateSessionId());
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
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        String result = tools.raisePoint(channelId.toString(), "REV", 1,
                "The issue content.", "P1", "ISOLATED", "§3.2");

        assertThat(result).contains("pointId");
        assertThat(result).contains("dispatched");

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        MessageDispatch d = cap.getValue();
        assertThat(d.type()).isEqualTo(MessageType.QUERY);
        assertThat(d.sender()).isEqualTo(session.revInstanceId());
        assertThat(d.actorType()).isEqualTo(ActorType.AGENT);
        assertThat(d.correlationId()).isNotBlank();
        // Metadata encoded in content as META header; artefactRefs is null (Qhorus parses it as CSV UUIDs)
        assertThat(d.artefactRefs()).isNull();
        assertThat(d.content()).startsWith(DebateProtocol.META_SENTINEL);
        assertThat(d.content()).contains("entryType=raise");
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
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.raisePoint(channelId.toString(), "IMP", 2, "content", "P2", "SYSTEMIC", null);

        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().sender()).isEqualTo(session.impInstanceId());
    }

    // ── respond_to ────────────────────────────────────────────────────────────

    @Test
    void respondTo_agree_dispatchesDone() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
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
        // Metadata encoded in content; artefactRefs is null
        assertThat(d.artefactRefs()).isNull();
        assertThat(d.content()).contains("entryType=agree");
        assertThat(d.content()).endsWith("I agree.");
    }

    @Test
    void respondTo_dispute_dispatchesDecline() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
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
        DebateSession session = new DebateSession(channelId, channelId.toString(), stubChannel.name, "r", "i");
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
        DebateSession session = new DebateSession(channelId, channelId.toString(), stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        Message stubMsg = new Message(); stubMsg.id = 1L;
        when(messageService.findByCorrelationId(pointId)).thenReturn(Optional.of(stubMsg));

        tools.respondTo(channelId.toString(), "REV", 3, pointId, "counter", "Counter arg.");
        ArgumentCaptor<MessageDispatch> cap = ArgumentCaptor.forClass(MessageDispatch.class);
        verify(messageService).dispatch(cap.capture());
        assertThat(cap.getValue().type()).isEqualTo(MessageType.RESPONSE);
        assertThat(cap.getValue().artefactRefs()).isNull();
        assertThat(cap.getValue().content()).contains("entryType=counter");
    }

    @Test
    void respondTo_unknownPointId_returnsError() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(), stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        when(messageService.findByCorrelationId(anyString())).thenReturn(Optional.empty());

        String result = tools.respondTo(channelId.toString(), "IMP", 2, "no-such-point", "agree", "Agreed.");
        assertThat(result).startsWith("error: point not found:");
    }

    // ── flag_human ────────────────────────────────────────────────────────────

    @Test
    void flagHuman_dispatchesHandoffWithCorrectFields() {
        UUID channelId = stubChannel.id;
        String pointId = UUID.randomUUID().toString();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
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
        // Metadata encoded in content; artefactRefs is null
        assertThat(d.artefactRefs()).isNull();
        assertThat(d.content()).contains("entryType=flag-human");
        assertThat(d.content()).endsWith("Human clarification needed.");
    }

    @Test
    void flagHuman_unknownPointId_returnsError() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(), stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        when(messageService.findByCorrelationId(anyString())).thenReturn(Optional.empty());

        String result = tools.flagHuman(channelId.toString(), "REV", 3, "no-point", "reason");
        assertThat(result).startsWith("error: point not found:");
    }

    // ── get_debate_summary ────────────────────────────────────────────────────

    @Test
    void getDebateSummary_delegatesToProjectionAndRenders() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(), stubChannel.name, "r", "i");
        when(registry.find(channelId)).thenReturn(Optional.of(session));
        ReviewState emptyState = new ReviewState(Map.of(), List.of());
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
        DebateSession session = new DebateSession(channelId, channelId.toString(), stubChannel.name, "r", "i");
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
    void endDebate_deregistersRevAndImpInstances() {
        UUID channelId = stubChannel.id;
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                stubChannel.name,
                "drafthouse-rev-" + channelId,
                "drafthouse-imp-" + channelId);
        when(registry.find(channelId)).thenReturn(Optional.of(session));

        tools.endDebate(channelId.toString(), false);

        verify(instanceService).deregister(session.revInstanceId());
        verify(instanceService).deregister(session.impInstanceId());
    }

    @Test
    void startDebate_partialFailure_deregistersRegisteredInstancesWhenInitFails() {
        doThrow(new RuntimeException("init failed"))
                .when(channelGateway).initChannel(any(), any());

        String result = tools.startDebate("spec.md");

        assertThat(result).startsWith("error:");
        String debateSessionId = stubChannel.id.toString();
        verify(instanceService).deregister("drafthouse-rev-" + debateSessionId);
        verify(instanceService).deregister("drafthouse-imp-" + debateSessionId);
    }
}
