package io.casehub.drafthouse.debate;

import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.WebSocketEventBus;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WorkspaceWatcherTest {

    @Inject ChannelService channelService;
    @Inject MessageService messageService;
    @Inject InstanceService instanceService;
    @Inject ChannelGateway channelGateway;
    @Inject WebSocketEventBus eventBus;

    @Test
    void watcher_dispatches_entries_for_new_reviewer_file(@TempDir Path tmpDir) throws Exception {
        Path responsesDir = tmpDir.resolve("responses");
        Files.createDirectories(responsesDir);
        Files.writeString(tmpDir.resolve(".spec-path"), "/tmp/test.md");

        Channel channel = channelService.create(ChannelCreateRequest.builder(
                "drafthouse/debate/watcher-test-" + System.nanoTime())
                .description("watcher test").semantic(ChannelSemantic.APPEND).build());

        DebateSession session = new DebateSession(
                channel.id(), channel.id().toString(), channel.name(), null);

        channelGateway.initChannel(channel.id(),
                new ChannelRef(channel.id(), channel.name()));

        String revId = DebateSession.instanceId(AgentType.REV, session.debateSessionId());
        instanceService.register(revId, "test rev", List.of("document-debate-rev"));
        session.registerIfAbsent(AgentType.REV, () -> revId);

        String impId = DebateSession.instanceId(AgentType.IMP, session.debateSessionId());
        instanceService.register(impId, "test imp", List.of("document-debate-imp"));
        session.registerIfAbsent(AgentType.IMP, () -> impId);

        var adapter = new WorkspaceReplayAdapter(
                messageService, instanceService, channelGateway, eventBus);

        var watcher = new WorkspaceWatcher(
                adapter, eventBus, session, messageService, null, () -> {});
        watcher.start(tmpDir, 0, new HashSet<>(), new HashMap<>(), 0L, null, null);

        Thread.sleep(500);

        Files.writeString(responsesDir.resolve("reviewer-1.md"),
                "## R1-01: Missing validation\n\nNo input validation.\n\nSIGNAL: CONTINUE\n");

        Thread.sleep(3000);

        var messages = messageService.pollAfter(channel.id(), 0L, Integer.MAX_VALUE);
        var entries = messages.stream()
                .map(DebateStreamEntry::from).filter(Objects::nonNull).toList();

        assertFalse(entries.isEmpty(), "watcher should have dispatched entries for reviewer-1.md");
        assertTrue(entries.stream().anyMatch(e -> e.entryType() == EntryType.RAISE),
                "should contain a RAISE entry");

        watcher.stop();
    }

    @Test
    void watcher_stops_on_terminal_state(@TempDir Path tmpDir) throws Exception {
        Path responsesDir = tmpDir.resolve("responses");
        Files.createDirectories(responsesDir);
        Files.writeString(tmpDir.resolve(".spec-path"), "/tmp/test.md");

        Channel channel = channelService.create(ChannelCreateRequest.builder(
                "drafthouse/debate/watcher-term-" + System.nanoTime())
                .description("watcher terminal test").semantic(ChannelSemantic.APPEND).build());

        DebateSession session = new DebateSession(
                channel.id(), channel.id().toString(), channel.name(), null);

        channelGateway.initChannel(channel.id(),
                new ChannelRef(channel.id(), channel.name()));

        String revId = DebateSession.instanceId(AgentType.REV, session.debateSessionId());
        instanceService.register(revId, "test rev", List.of("document-debate-rev"));
        session.registerIfAbsent(AgentType.REV, () -> revId);

        var adapter = new WorkspaceReplayAdapter(
                messageService, instanceService, channelGateway, eventBus);

        CountDownLatch completed = new CountDownLatch(1);
        var watcher = new WorkspaceWatcher(
                adapter, eventBus, session, messageService, null, completed::countDown);
        watcher.start(tmpDir, 0, new HashSet<>(), new HashMap<>(), 0L, null, null);

        Thread.sleep(500);

        Files.writeString(tmpDir.resolve("progress.log"), "REVIEW DONE\n");

        assertTrue(completed.await(5, TimeUnit.SECONDS),
                "watcher should invoke onComplete after terminal state");
    }

    @Test
    void watcher_dispatches_responses_for_implementor_file(@TempDir Path tmpDir) throws Exception {
        Path responsesDir = tmpDir.resolve("responses");
        Files.createDirectories(responsesDir);
        Files.writeString(tmpDir.resolve(".spec-path"), "/tmp/test.md");

        Files.writeString(responsesDir.resolve("reviewer-1.md"),
                          "## R1-01: Missing validation\n\nNo input validation.\n\nSIGNAL: CONTINUE\n");

        Channel channel = channelService.create(ChannelCreateRequest.builder(
                                                                            "drafthouse/debate/watcher-imp-" + System.nanoTime())
                                                                    .description("watcher implementor test").semantic(ChannelSemantic.APPEND).build());

        DebateSession session = new DebateSession(
                channel.id(), channel.id().toString(), channel.name(), null);

        channelGateway.initChannel(channel.id(),
                                   new ChannelRef(channel.id(), channel.name()));

        String revId = DebateSession.instanceId(AgentType.REV, session.debateSessionId());
        instanceService.register(revId, "test rev", List.of("document-debate-rev"));
        session.registerIfAbsent(AgentType.REV, () -> revId);

        String impId = DebateSession.instanceId(AgentType.IMP, session.debateSessionId());
        instanceService.register(impId, "test imp", List.of("document-debate-imp"));
        session.registerIfAbsent(AgentType.IMP, () -> impId);

        var adapter = new WorkspaceReplayAdapter(
                messageService, instanceService, channelGateway, eventBus);

        var existingIds = new HashSet<String>();
        var raiseIds    = new HashMap<String, Long>();

        var round1 = WorkspaceParser.parseRoundFromMarkdown(responsesDir, 1, existingIds);
        adapter.dispatchIssues(channel.id(),
                               session.instanceIdFor(AgentType.REV), round1, raiseIds);
        round1.issues().forEach(i -> existingIds.add(i.issueId()));
        long lastMsgId = messageService.pollAfter(channel.id(), 0L, Integer.MAX_VALUE)
                                       .stream().mapToLong(m -> m.id()).max().orElse(0L);

        var watcher = new WorkspaceWatcher(
                adapter, eventBus, session, messageService, null, () -> {});
        watcher.start(tmpDir, 0, existingIds, raiseIds, lastMsgId, null, null);

        Thread.sleep(500);

        Files.writeString(responsesDir.resolve("implementor-1.md"),
                          "## R1-01: FIXED\n\nAdded null check.\n\n§3.2\n\nSIGNAL: CONTINUE\n");

        Thread.sleep(3000);

        var messages = messageService.pollAfter(channel.id(), lastMsgId, Integer.MAX_VALUE);
        var entries = messages.stream()
                              .map(DebateStreamEntry::from).filter(Objects::nonNull).toList();

        assertTrue(entries.stream().anyMatch(e -> e.entryType() == EntryType.QUALIFY),
                   "should contain a QUALIFY entry for the implementor response");

        watcher.stop();
    }

}
