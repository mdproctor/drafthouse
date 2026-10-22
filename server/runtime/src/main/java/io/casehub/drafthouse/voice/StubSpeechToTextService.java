package io.casehub.drafthouse.voice;

import io.casehub.agency.speech.SpeechToTextService;
import io.casehub.agency.speech.TranscriptionOptions;
import io.casehub.agency.speech.TranscriptionResult;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;

@ApplicationScoped
@DefaultBean
public class StubSpeechToTextService implements SpeechToTextService {
    @Override
    public TranscriptionResult transcribe(Path audioFile, TranscriptionOptions options) {
        return new TranscriptionResult(
                "[stub] Transcription of " + audioFile.getFileName(), "en", 1.0);
    }
}
