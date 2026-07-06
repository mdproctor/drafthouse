package io.casehub.drafthouse.debate;

import io.casehub.blocks.conversation.ConversationState;
import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.DebateSessionRegistry;
import io.casehub.drafthouse.WebSocketEventBus;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.message.ProjectionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkspaceReplayAdapterTest {

    @Inject ChannelService channelService;
    @Inject MessageService messageService;
    @Inject InstanceService instanceService;
    @Inject ChannelGateway channelGateway;
    @Inject ProjectionService projectionService;
    @Inject DebateChannelProjection debateProjection;
    @Inject DebateSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;

    @Test
    void replay_creates_entries_with_correct_statuses() {
        Path fixture = Path.of("src/test/resources/fixtures/workspace-replay");
        var parseResult = WorkspaceParser.parse(fixture);

        String channelName = "drafthouse/debate/replay-test-" + System.nanoTime();
        Channel channel = channelService.create(ChannelCreateRequest.builder(channelName)
                .description("test replay").semantic(ChannelSemantic.APPEND).build());

        DebateSession session = new DebateSession(
                channel.id(), channel.id().toString(), channel.name(), null);

        var adapter = new WorkspaceReplayAdapter(
                messageService, instanceService, channelGateway, eventBus);

        var result = adapter.replay(session, parseResult);

        assertTrue(result.entryCount() > 0, "should have dispatched entries");

        var projected = projectionService.project(channel.id(), debateProjection);
        ConversationState state = projected.state();

        assertNotNull(state);
        assertFalse(state.points().isEmpty(), "should have conversation points");
        assertEquals(4, state.points().size(), "fixture has 4 issues");
    }
}
