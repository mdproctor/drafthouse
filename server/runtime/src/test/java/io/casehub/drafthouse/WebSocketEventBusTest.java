package io.casehub.drafthouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.drafthouse.debate.AgentType;
import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.drafthouse.debate.EntryType;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketEventBusTest {

    private WebSocketEventBus bus;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        bus = new WebSocketEventBus();
        mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        try {
            var f = WebSocketEventBus.class.getDeclaredField("mapper");
            f.setAccessible(true);
            f.set(bus, mapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void register_and_unregister_tracks_connections() {
        WebSocketConnection conn = mockConnection();
        bus.register(conn);
        assertThat(bus.connectionCount()).isEqualTo(1);
        bus.unregister(conn);
        assertThat(bus.connectionCount()).isEqualTo(0);
    }

    @Test
    void watchSession_and_unwatchSession() {
        WebSocketConnection conn = mockConnection();
        UUID channelId = UUID.randomUUID();
        bus.register(conn);
        bus.watchSession(conn, channelId);
        assertThat(bus.sessionWatcherCount(channelId)).isEqualTo(1);
        bus.unwatchSession(conn, channelId);
        assertThat(bus.sessionWatcherCount(channelId)).isEqualTo(0);
    }

    @Test
    void watchFile_and_unwatchFile_with_reference_counting() {
        WebSocketConnection conn1 = mockConnection();
        WebSocketConnection conn2 = mockConnection();
        String path = "/tmp/test.md";
        bus.register(conn1);
        bus.register(conn2);
        bus.watchFile(conn1, path);
        bus.watchFile(conn2, path);
        assertThat(bus.hasFileWatchers(path)).isTrue();
        assertThat(bus.fileWatcherCount(path)).isEqualTo(2);
        bus.unwatchFile(conn1, path);
        assertThat(bus.hasFileWatchers(path)).isTrue();
        bus.unwatchFile(conn2, path);
        assertThat(bus.hasFileWatchers(path)).isFalse();
    }

    @Test
    void unregister_removes_from_all_watcher_sets() {
        WebSocketConnection conn = mockConnection();
        UUID channelId = UUID.randomUUID();
        String filePath = "/tmp/test.md";
        bus.register(conn);
        bus.watchSession(conn, channelId);
        bus.watchFile(conn, filePath);
        bus.unregister(conn);
        assertThat(bus.connectionCount()).isEqualTo(0);
        assertThat(bus.sessionWatcherCount(channelId)).isEqualTo(0);
        assertThat(bus.fileWatcherCount(filePath)).isEqualTo(0);
    }

    @Test
    void broadcast_sends_to_all_connections() {
        WebSocketConnection conn1 = mockConnection();
        WebSocketConnection conn2 = mockConnection();
        bus.register(conn1);
        bus.register(conn2);
        bus.broadcast("sessions", List.of());
        verify(conn1).sendText(anyString());
        verify(conn2).sendText(anyString());
    }

    @Test
    void pushDebateEntries_sends_only_to_session_watchers() {
        WebSocketConnection watcher = mockConnection();
        WebSocketConnection other = mockConnection();
        UUID channelId = UUID.randomUUID();
        bus.register(watcher);
        bus.register(other);
        bus.watchSession(watcher, channelId);

        DebateStreamEntry entry = new DebateStreamEntry(
                EntryType.RAISE, AgentType.REV, 1, "test content",
                "p1", null, null, null, null, "rev-agent",
                java.time.Instant.now(), null, null);
        bus.pushDebateEntries(channelId, List.of(entry));
        verify(watcher).sendText(anyString());
        verify(other, never()).sendText(anyString());
    }

    @Test
    void pushMetadata_sends_only_to_session_watchers() {
        WebSocketConnection watcher = mockConnection();
        WebSocketConnection other = mockConnection();
        UUID channelId = UUID.randomUUID();
        bus.register(watcher);
        bus.register(other);
        bus.watchSession(watcher, channelId);
        bus.pushMetadata(channelId, "context-usage", java.util.Map.of("effectivePercent", 42.0));
        verify(watcher).sendText(anyString());
        verify(other, never()).sendText(anyString());
    }

    @Test
    void pushFileChanged_sends_only_to_file_watchers() {
        WebSocketConnection watcher = mockConnection();
        WebSocketConnection other = mockConnection();
        bus.register(watcher);
        bus.register(other);
        bus.watchFile(watcher, "/tmp/test.md");
        bus.pushFileChanged("/tmp/test.md");
        verify(watcher).sendText(anyString());
        verify(other, never()).sendText(anyString());
    }

    @Test
    void sendText_failure_triggers_unregister() {
        WebSocketConnection deadConn = mock(WebSocketConnection.class);
        when(deadConn.sendText(anyString())).thenReturn(Uni.createFrom().failure(new RuntimeException("closed")));
        bus.register(deadConn);
        bus.broadcast("test", "payload");
        assertThat(bus.connectionCount()).isEqualTo(0);
    }

    @Test
    void formatEvent_uses_push_message_wire_format() {
        WebSocketConnection conn = mockConnection();
        bus.register(conn);
        bus.broadcast("test-topic", java.util.Map.of("key", "value"));
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn).sendText(captor.capture());
        String json = captor.getValue();
        assertThat(json).contains("\"op\":\"event\"");
        assertThat(json).contains("\"topic\":\"test-topic\"");
        assertThat(json).contains("\"payload\":");
    }

    @Test
    void watchBrainstorm_and_unwatchBrainstorm() {
        WebSocketConnection conn = mockConnection();
        bus.register(conn);
        bus.watchBrainstorm(conn, "bs-1");
        assertThat(bus.brainstormWatcherCount("bs-1")).isEqualTo(1);
        bus.unwatchBrainstorm(conn, "bs-1");
        assertThat(bus.brainstormWatcherCount("bs-1")).isEqualTo(0);
    }

    @Test
    void pushBrainstormEvent_sends_only_to_brainstorm_watchers() {
        WebSocketConnection watcher = mockConnection();
        WebSocketConnection other   = mockConnection();
        bus.register(watcher);
        bus.register(other);
        bus.watchBrainstorm(watcher, "bs-1");
        bus.pushBrainstormEvent("bs-1", "brainstorm-options", java.util.Map.of("options", java.util.List.of()));
        verify(watcher).sendText(anyString());
        verify(other, never()).sendText(anyString());
    }

    @Test
    void pushBrainstormEvent_noWatchers_noError() {
        bus.pushBrainstormEvent("bs-nonexistent", "brainstorm-options", java.util.Map.of());
    }

    @Test
    void unregister_removes_brainstorm_watchers() {
        WebSocketConnection conn = mockConnection();
        bus.register(conn);
        bus.watchBrainstorm(conn, "bs-1");
        bus.unregister(conn);
        assertThat(bus.brainstormWatcherCount("bs-1")).isEqualTo(0);
    }


    private WebSocketConnection mockConnection() {
        WebSocketConnection conn = mock(WebSocketConnection.class);
        when(conn.sendText(anyString())).thenReturn(Uni.createFrom().voidItem());
        return conn;
    }
}
