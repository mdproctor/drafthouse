package io.casehub.drafthouse.voice;

import io.casehub.agency.speech.SpeechToTextService;
import io.casehub.agency.speech.TranscriptionOptions;
import io.casehub.agency.speech.TranscriptionResult;
import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.casehub.drafthouse.NoOpDraftHouseSessionStore;
import io.casehub.drafthouse.WebSocketEventBus;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceUploadResourceTest {

    @TempDir Path workDir;
    DraftHouseSessionRegistry registry;
    SpeechToTextService sttService;
    @SuppressWarnings("unchecked") Event<TranscriptReady> transcriptEvent = mock(Event.class);
    WebSocketEventBus eventBus;
    VoiceUploadResource resource;

    @BeforeEach
    void setUp() {
        registry = new DraftHouseSessionRegistry(new NoOpDraftHouseSessionStore());
        sttService = mock(SpeechToTextService.class);
        eventBus = mock(WebSocketEventBus.class);

        var session = registry.create("test-session");
        session.setWorkingDirectory(workDir);
        session.activateFacet(new VoiceFacet());

        when(transcriptEvent.fireAsync(any(TranscriptReady.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        resource = new VoiceUploadResource();
        resource.registry = registry;
        resource.sttService = sttService;
        resource.transcriptReadyEvent = transcriptEvent;
        resource.eventBus = eventBus;
    }

    @Test
    void uploadTranscribesAndWritesRawFile() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
            .thenReturn(new TranscriptionResult("hello world", "en", 0.95));

        InputStream audio = new ByteArrayInputStream(new byte[]{1, 2, 3});
        String result = resource.upload("test-session", null, audio);

        assertTrue(result.contains("hello world"));
        verify(transcriptEvent).fireAsync(any(TranscriptReady.class));

        long rawFiles = Files.list(workDir)
            .filter(p -> p.getFileName().toString().startsWith("raw-transcript-"))
            .count();
        assertEquals(1, rawFiles);
    }

    @Test
    void uploadSavesAudioWithWebmExtension() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
            .thenReturn(new TranscriptionResult("test", "en", 0.9));

        InputStream audio = new ByteArrayInputStream(new byte[]{1, 2, 3});
        resource.upload("test-session", null, audio);

        long webmFiles = Files.list(workDir)
            .filter(p -> p.getFileName().toString().endsWith(".webm"))
            .count();
        assertEquals(1, webmFiles);
    }

    @Test
    void uploadWithGoalTagPassesToEvent() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
            .thenReturn(new TranscriptionResult("test note", "en", 0.9));

        InputStream audio = new ByteArrayInputStream(new byte[]{1, 2, 3});
        resource.upload("test-session", "prompt", audio);

        var captor = org.mockito.ArgumentCaptor.forClass(TranscriptReady.class);
        verify(transcriptEvent).fireAsync(captor.capture());
        assertEquals("prompt", captor.getValue().goalTag());
        assertEquals("test-session", captor.getValue().sessionId());
    }

    @Test
    void uploadPushesWebSocketEvent() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
            .thenReturn(new TranscriptionResult("hello", "en", 0.95));

        InputStream audio = new ByteArrayInputStream(new byte[]{1, 2, 3});
        resource.upload("test-session", null, audio);

        verify(eventBus).pushVoiceEvent(eq("test-session"), eq("voice-transcript-ready"), any());
    }

    @Test
    void uploadUnknownSessionAutoCreates() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
                .thenReturn(new TranscriptionResult("unknown session", "en", 0.9));

        InputStream audio  = new ByteArrayInputStream(new byte[]{1, 2, 3});
        String      result = resource.upload("nonexistent", null, audio);

        assertFalse(result.startsWith("Failed:"), "Unknown session should auto-create: " + result);
        assertTrue(result.contains("unknown session"));
    }

    @Test
    void uploadSessionWithoutVoiceFacetReturnsError() {
        var session = registry.create("no-voice");
        session.setWorkingDirectory(workDir);

        InputStream audio = new ByteArrayInputStream(new byte[]{1, 2, 3});
        String result = resource.upload("no-voice", null, audio);
        assertTrue(result.startsWith("Failed:"));
        assertTrue(result.contains("voice facet"));
    }

    @Test
    void uploadBlankSessionIdAutoCreatesSession() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
                .thenReturn(new TranscriptionResult("auto hello", "en", 0.9));

        InputStream audio  = new ByteArrayInputStream(new byte[]{1, 2, 3});
        String      result = resource.upload("", null, audio);

        assertTrue(result.contains("auto hello"), "Should transcribe successfully: " + result);
        assertFalse(result.startsWith("Failed:"), "Should not fail: " + result);
        verify(transcriptEvent).fireAsync(any(TranscriptReady.class));
    }

    @Test
    void uploadNullSessionIdAutoCreatesSession() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
                .thenReturn(new TranscriptionResult("null session hello", "en", 0.85));

        InputStream audio  = new ByteArrayInputStream(new byte[]{1, 2, 3});
        String      result = resource.upload(null, null, audio);

        assertTrue(result.contains("null session hello"), "Should transcribe: " + result);
        assertFalse(result.startsWith("Failed:"), "Should not fail: " + result);
    }

    @Test
    void autoCreatedSessionHasVoiceAndNotesFacets() throws Exception {
        when(sttService.transcribe(any(Path.class), any(TranscriptionOptions.class)))
                .thenReturn(new TranscriptionResult("facet check", "en", 0.9));

        InputStream audio = new ByteArrayInputStream(new byte[]{1, 2, 3});
        resource.upload("", null, audio);

        var sessions = registry.activeSessions();
        var autoSession = sessions.stream()
                                  .filter(s -> s.id().startsWith("voice-"))
                                  .findFirst();
        assertTrue(autoSession.isPresent(), "Auto-session should exist");
        assertTrue(autoSession.get().findFacet("voice").isPresent(), "Should have voice facet");
        assertTrue(autoSession.get().findFacet("notes").isPresent(), "Should have notes facet");
    }


}
