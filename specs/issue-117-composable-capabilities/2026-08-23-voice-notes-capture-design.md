# Voice Notes Capture & Obsidian-Compatible Storage — Design Spec

> **Issue:** casehubio/drafthouse#117 (Composable capability architecture)
> **Date:** 2026-08-23
> **Status:** Design — pending implementation plan
> **Scope:** VoiceFacet, NotesFacet, speech SPIs, cleanup pipeline, Obsidian vault storage

---

## 1. Problem Statement

DraftHouse needs a way to capture voice notes, clean them up through a fidelity-preserving pipeline, and store them as Obsidian-compatible markdown notes. These notes serve as source material for downstream work — document drafting, LLM prompt authoring, or general reference. The capture boundary is distinct from the creation boundary: everything before the note preserves the speaker's intent; everything after it is authoring.

## 2. Architecture Overview

Two new facets plug into the existing `DraftHouseSession` container:

**VoiceFacet** — captures audio from the browser mic via MediaRecorder API, uploads completed recordings to `POST /api/voice/upload`, and invokes the STT SPI to produce a raw transcript file in the session working directory.

**NotesFacet** — receives raw transcripts, runs the cleanup pipeline (LangChain4j ChatModel with structured output), and writes the resulting note to the Obsidian-compatible vault. Also handles non-voice note creation (paste, import, typed).

**Facet-to-facet communication:** VoiceFacet declares `raw-transcript-*.md` as an artifact output; NotesFacet declares it as an input. These declarations are metadata (for introspection and dependency validation), not a runtime trigger. The actual trigger is a CDI event: after writing the raw transcript, `VoiceUploadResource` fires a `TranscriptReady` CDI event. NotesFacet observes this event and starts the cleanup pipeline. This preserves loose coupling — VoiceFacet doesn't reference NotesFacet directly.

**Session integration:** Both facets activate within a `DraftHouseSession`. For quick capture, auto-session creation transparently handles the session lifecycle — the user just hits record. The vault (`~/.drafthouse/vault/` by default, configurable via `casehub.drafthouse.vault.path`) outlives sessions — notes persist independently.

**Auto-session lifecycle:** When no active session exists, the `start_recording` MCP tool (or the `<voice-capture>` UI panel) auto-creates a lightweight session with a generated ID (`voice-{timestamp}`), assigns a working directory under `~/.drafthouse/sessions/{id}/`, and activates both VoiceFacet and NotesFacet. The auto-session remains active until explicitly ended or until a configurable idle timeout (default 30 minutes after last pipeline completion). Facet dependency: `VoiceFacet.activate()` checks for an active NotesFacet and activates it if absent — this is runtime logic in VoiceFacet, not a declarative dependency on the Facet interface.

### Component diagram

```
Browser                              Quarkus Server                        Filesystem
┌─────────────────┐                 ┌──────────────────────────┐          ┌─────────────────┐
│ <voice-capture>  │──POST /api/──→│ VoiceUploadResource       │          │ Session workdir  │
│  MediaRecorder   │  voice/upload  │   ↓                      │          │ ├── raw-transcript│
│  push-to-talk    │                │ SpeechToTextService (SPI) │──write──→│ │   -*.md         │
└─────────────────┘                │   (sherpa-onnx default)   │          │ └── ...           │
                                    └──────────────────────────┘          └────────┬──────────┘
                                                                                   │ artifact
┌─────────────────┐                 ┌──────────────────────────┐                   │ contract
│ <note-list>      │←──WebSocket───│ NotesFacet                │←──CDI event──────┘
│ <note-detail>    │   note-created │   ↓                      │
│ <pipeline-status>│                │ ChatModel (structured)    │          ┌─────────────────┐
└─────────────────┘                │   cleanup + metadata      │──write──→│ Vault            │
                                    │   ↓ (optional)            │          │ ├── notes/*.md    │
                                    │ ChatModel (goal refine)   │          │ ├── raw/*.md      │
                                    └──────────────────────────┘          │ └── .obsidian/    │
                                                                          └─────────────────┘
```

## 3. Speech SPIs (casehub-agency)

Speech capabilities are domain-agnostic composable building blocks — they live in casehub-agency as a speech module, reusable across CaseHub apps.

### 3.1 SpeechToTextService

```java
public interface SpeechToTextService {
    TranscriptionResult transcribe(Path audioFile, TranscriptionOptions options);
}

public record TranscriptionResult(String text, String language, double confidence) {}

public record TranscriptionOptions(
    String audioFormat,
    String languageHint,
    String modelSize
) {}
```

`Path` rather than `byte[]` — avoids loading entire audio files into heap (a 30-minute recording is ~30MB). The sherpa-onnx implementation reads files natively. A future streaming variant (`transcribeStreaming(InputStream, ...)`) can be added without breaking the initial contract.

### 3.2 TextToSpeechService

```java
public interface TextToSpeechService {
    SynthesisResult synthesise(String text, SynthesisOptions options);
}

public record SynthesisResult(byte[] audioData, String audioFormat, List<PhonemeTiming> phonemes) {}

public record PhonemeTiming(String phoneme, long startMs, long endMs) {}

public record SynthesisOptions(
    String voice,
    String language,
    String audioFormat,
    boolean includePhonemes
) {}
```

### 3.3 Default implementation: sherpa-onnx

Sherpa-onnx serves as the unified runtime for both STT and TTS via Java FFM/Panama:
- **STT:** Whisper models — offline, no API costs, Apple Silicon Metal acceleration
- **TTS:** VITS/Piper models — local synthesis with phoneme timing output
- One native binding for both directions

Alternative implementations pluggable via CDI for external services (Deepgram, Google, ElevenLabs, Amazon Polly).

The SPI abstraction is critical: latency and quality vary significantly across models and services. Implementations must be swappable without touching consumer code.

**Blocks module structure:** casehub-agency is currently a single Maven module. Adding speech SPIs requires restructuring into a multi-module reactor: `blocks-speech-api/` (pure Java SPI interfaces) and `blocks-speech-sherpa/` (FFM implementation with native library dependency). This restructuring must be coordinated with the blocks repo — a separate issue tracks it.

**Model management:** Sherpa-onnx models are stored under a configurable path (`casehub.drafthouse.speech.model-path`, default `~/.drafthouse/models/`). Models are downloaded on first use — the implementation checks for the model file and fetches it if absent. STT model selection is configurable (`casehub.drafthouse.speech.stt-model`, default `whisper-tiny` — 39MB, fast, acceptable quality for voice notes). Larger models (`whisper-base`, `whisper-small`) configurable for higher accuracy at the cost of latency. Platform-specific native libraries are resolved via standard JNI/FFM library path — macOS (Apple Silicon Metal), Linux (CPU/CUDA) supported.

### 3.4 Future: avatar lip-sync

TTS phoneme timing output feeds avatar lip-sync animation (casehubio/agency#154). Not in scope for this design — filed as a future capability.

## 4. Voice Capture Pipeline

### 4.1 Browser capture

**Phase 1 (this design): Push-to-talk**
- MediaRecorder API captures audio from user's microphone
- User holds/toggles a button to record, releases to stop
- Completed recording uploaded via `POST /api/voice/upload` (multipart: audio file + session ID + optional goal tag)

**Phase 2 (future): Additional input modes**
- **Continuous with pause detection** — VAD segments audio at natural pauses. Requires silence threshold calibration, noise floor estimation, debounce.
- **Record-then-review** — long recording with audio playback, seeking, and waveform UI before submitting.

### 4.2 Audio transport

HTTP POST upload to `/api/voice/upload`. Push-to-talk means the recording completes before processing begins — streaming STT is unnecessary. When continuous mode is implemented (Phase 2), the transport decision can be revisited with actual streaming requirements.

### 4.3 Server processing

1. JAX-RS `VoiceUploadResource` receives multipart form data (audio file saved to session working directory)
2. Invokes `SpeechToTextService.transcribe(audioPath, options)` via the blocks STT SPI
3. Writes raw transcript to session working directory as `raw-transcript-{timestamp}.md`
4. Fires `TranscriptReady` CDI event (carries session ID, transcript path, optional goal tag)
5. Emits `voice-transcript-ready` WebSocket event to browser (scoped to `voice:{sessionId}` topic)
6. NotesFacet observes the CDI event and starts the cleanup pipeline

## 5. Notes Pipeline

### 5.1 Two-stage pipeline

**Stage 1 — Cleanup + metadata extraction (always runs):**
- Single LLM call via LangChain4j ChatModel with structured output schema
- Fidelity cleanup: remove filler words (um, ah), repeated words/sentences, broken sentence fragments
- Metadata extraction: generate title, summary, tags from the content
- Model selection: Haiku-tier — mechanical text cleanup, not creative reasoning
- Input: raw transcript text
- Output: structured response containing cleaned text, title, summary, tags[], detected language

**Stage 2 — Goal-specific refinement (optional):**
- Runs only if the note has a goal tag (set at capture time or after stage 1)
- Parameterized by intent — different prompts for different goals:
  - `document` — readability, grammar, sentence structure (still the speaker's words, just clearer)
  - `prompt` — precision, disambiguation, instruction clarity
  - `reference` — no refinement (stage 1 output is sufficient)
- Model selection: can vary by goal — prompts may benefit from a stronger model

**Key constraint:** No stage changes what was said — only how clearly it reads. The note is the boundary between capture and creation.

### 5.3 Pipeline states and error handling

```
PENDING → STT_RUNNING → STAGE1_RUNNING → STAGE1_COMPLETE → [STAGE2_RUNNING → COMPLETE] | FAILED
```

- **STT failure:** Raw audio file preserved in working directory. User notified via `pipeline-error` WebSocket event. Retryable via `refine_note` MCP tool or UI retry button.
- **Stage 1 LLM failure** (API error, timeout, schema mismatch after retries): Raw transcript preserved. Note created in vault with `status: raw-only` frontmatter — usable but unprocessed. User sees error state in `<pipeline-status>` panel. Retryable.
- **Stage 2 LLM failure:** Stage 1 output (cleaned note) already written to vault. Note usable without refinement. `status: cleaned` in frontmatter. Retry stage 2 independently via `refine_note`.
- **Partial completion:** Each stage writes its output file before advancing. On session restore, NotesFacet scans for the last completed stage file and resumes from there.

### 5.2 Non-voice input

NotesFacet also accepts input from sources other than voice:
- `create_note` MCP tool — paste text, type directly, or import existing markdown
- Same pipeline applies: cleanup + metadata extraction, optional goal refinement
- Same vault storage format

## 6. Vault Storage

### 6.1 Directory structure

```
~/.drafthouse/vault/                    (configurable via casehub.drafthouse.vault.path)
├── notes/
│   └── 2026-08-23-standup-thoughts.md  ← cleaned note with frontmatter
├── raw/
│   └── 2026-08-23-standup-thoughts.md  ← verbatim STT output
└── .obsidian/                           ← Obsidian config (auto-created if absent)
```

Obsidian opens this directory as a secondary vault. `raw/` can be excluded from Obsidian search/graph via `.obsidian/app.json` excludes.

Note filenames use timestamp initially (`2026-08-23T091500.md`); renamed to `{date}-{slugified-title}.md` after metadata extraction produces a title.

### 6.2 Note frontmatter (Obsidian-compatible YAML)

```yaml
---
title: Standup thoughts on auth module
date: 2026-08-23T09:15:00
source: voice
duration: 45s
session: dh-abc123
goal: prompt
tags: [auth, testing, regression]
summary: Check whether the session timeout regression is fixed in the auth module
raw: ../raw/2026-08-23-standup-thoughts.md
---
```

Fields: `date` (capture timestamp), `source` (voice | text | import), `duration` (recording length, voice only), `session` (DraftHouseSession ID), `goal` (document | prompt | reference, if tagged), `tags` (LLM-extracted), `title` (LLM-extracted), `summary` (LLM-extracted), `raw` (relative path to raw transcript).

### 6.3 Persistence model

- The vault is the durable store — notes outlive their capture session
- `SessionSnapshot` stores facet metadata (active facets, vault path reference) but not note content
- Pipeline recovery is file-based: each stage writes its output file as a checkpoint. On session restore, NotesFacet scans the working directory for incomplete pipeline artifacts and resumes from the last completed stage.
- In-progress recordings are ephemeral audio buffers — a crash loses the current recording but never corrupts the vault
- No transactional guarantee between STT completion and vault write — a crash between these steps loses the transcript but not the audio (which can be re-processed)
- **Raw file lifecycle:** The raw transcript is initially written to the session working directory (`raw-transcript-{timestamp}.md`). When the pipeline completes, the raw transcript is *moved* (not copied) to the vault's `raw/` directory — the working directory copy is removed. The vault's `raw/` is the permanent record. The note's frontmatter `raw:` field points to the vault copy. During pipeline execution, the working directory is the source of truth; after completion, the vault is.

## 7. MCP Tools

**Registration pattern:** All voice/notes tools are registered statically via `@ApplicationScoped` + `@Tool` (same as existing debate and brainstorm tools). Each tool method checks facet state as a precondition — e.g. `requireFacet(session, "voice")` — and returns an error message if the facet is not active. This follows the proven pattern in `DebateMcpTools` and `BrainstormMcpTools`. True dynamic tool registration (tools appearing/disappearing from the MCP tool list) is deferred until #117 delivers the platform `WorkerFunctionProvider` mechanism.

### 7.1 VoiceFacet tools (active when voice facet is activated)

| Tool | Description |
|------|-------------|
| `start_recording` | Begin audio capture (mode: push-to-talk; continuous and record-review added in Phase 2) |
| `stop_recording` | End capture, trigger upload and STT |
| `list_recordings` | Show recordings in current session |

### 7.2 NotesFacet tools (active when notes facet is activated)

| Tool | Description |
|------|-------------|
| `create_note` | Create a note from text (non-voice path) |
| `refine_note` | Apply goal-specific refinement to a cleaned note |
| `list_notes` | List notes in the vault with metadata |
| `search_notes` | Search vault by tags, title, content |
| `get_note` | Retrieve note content and metadata |

### 7.3 Session-level

- `activate_facet voice` activates VoiceFacet (NotesFacet auto-activated as dependency)
- Quick-capture auto-creates a lightweight session with both facets pre-activated

## 8. Browser UI Panels

All panels are LitElement with Shadow DOM, registered via `registerPanel()`, orchestrated through `onPagesEvent()`.

| Panel | Placement | Purpose |
|-------|-----------|---------|
| `<voice-capture>` | Topbar | Mic button, mode selector, recording status, waveform indicator |
| `<note-list>` | Main area | Vault browser — notes with metadata, search/filter, click to view |
| `<note-detail>` | Main area | Note content, frontmatter, raw transcript toggle, refine/re-tag |
| `<pipeline-status>` | Topbar | Pipeline progress for in-flight notes (transient) |

### WebSocket events (via existing /api/ws)

Events are scoped to `voice:{sessionId}` topic — clients subscribe to a specific session's events via the existing `TopicRegistry` pattern (same as `debate:{channelId}` and `brainstorm:{sessionId}`). No cross-session leakage.

| Event | When |
|-------|------|
| `voice-recording-started` | Capture begins |
| `voice-recording-stopped` | Capture ends |
| `voice-transcript-ready` | STT complete, raw transcript available |
| `note-created` | Pipeline complete, note written to vault |
| `note-updated` | Refinement applied to existing note |
| `pipeline-error` | Pipeline stage failed (includes stage, error message) |

## 9. Phased Delivery

| Phase | Scope | Rationale |
|-------|-------|-----------|
| 1 | Push-to-talk capture, STT SPI + sherpa-onnx, two-stage pipeline, vault storage, NotesFacet MCP tools, basic UI panels | Validates full voice-to-note pipeline with simplest input mode |
| 2 | Continuous mode (VAD), record-then-review, audio playback UI, waveform visualisation | Adds complexity after core pipeline is proven |
| 3 | TTS SPI + sherpa-onnx, read-aloud capability | Speech output — independent of capture pipeline |

## 10. Dependencies

| Dependency | What | Where |
|------------|------|-------|
| blocks-speech-api | STT + TTS SPI interfaces (pure Java) | New module in casehubio/agency (requires multi-module restructuring) |
| blocks-speech-sherpa | sherpa-onnx FFM implementation | New module in casehubio/agency |
| sherpa-onnx native library | whisper + VITS/Piper models | Runtime dependency, Apple Silicon Metal + Linux CPU/CUDA |
| LangChain4j ChatModel | Pipeline structured output calls | Already available via casehub-platform-agent-langchain4j |
| DraftHouseSession + Facet | Session container, facet lifecycle | Already implemented on branch |

**Note:** Adding speech modules to blocks requires restructuring it from a single module into a multi-module reactor. This is a cross-repo coordination concern — a blocks issue should be filed for the restructuring before implementation begins.

## References

- `server/api/src/main/java/io/casehub/drafthouse/Facet.java` — facet interface with artifact contract
- `server/api/src/main/java/io/casehub/drafthouse/ArtifactSpec.java` — artifact declaration
- `server/api/src/main/java/io/casehub/drafthouse/DraftHouseSession.java` — unified session container
- `server/runtime/src/main/java/io/casehub/drafthouse/SessionMcpTools.java` — session-level MCP tools (activate_facet stub)
- `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseSessionRegistry.java` — session lifecycle
- casehubio/drafthouse#117 — parent epic (composable capability architecture)
- casehubio/agency#154 — future avatar lip-sync capability
- `casehub-platform-agent-langchain4j` — ChatModel ↔ AgentProvider bridge
- `PlatformDebateAgentProvider.java` — existing agent provider pattern
- `DebateWebSocket.java` — existing WebSocket /api/ws endpoint
