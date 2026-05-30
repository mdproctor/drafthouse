# Critique Backend SPI — Design Spec

**Date:** 2026-05-30

## Context

`CritiqueResource` is a 501 stub. Phase 2 wires it to a real LLM. The design
goal is to explore Claude API direct and LangChain4j as separate, independently
evaluable backends — not to commit to one path before we know which fits better.

## Axes of variation

Two distinct axes, deliberately kept separate:

| Axis | Handled by |
|---|---|
| Which LLM runtime (Claude SDK / LangChain4j) | `ReviewerBackend` SPI |
| Auth mode within Claude direct (API key / Vertex / Bedrock) | Config property inside `ClaudeDirectBackend` |

Auth is not a SPI. It is a configuration concern inside one implementation.
Making it an interface boundary adds indirection with no benefit — the Anthropic
SDK already provides `AnthropicClient` and `AnthropicVertexAI`; selecting between
them on a config property is three lines of code.

## SPI: `ReviewerBackend`

```java
public interface ReviewerBackend {
    String critique(CritiqueContext ctx);
}
```

Blocking return for now. Streaming added later without breaking the interface
(add `Multi<String> stream(CritiqueContext ctx)` as a default or second method).

### `CritiqueContext`

```java
public record CritiqueContext(
    String documentA,
    String documentB,
    Optional<SelectionContext> selection,
    String personalityInstructions,
    List<ConversationTurn> history
) {}

public record SelectionContext(
    String side,        // "A" or "B"
    String selectedText,
    boolean inChangedRegion
) {}

public record ConversationTurn(
    String role,        // "user" or "assistant"
    String content
) {}
```

`history` is present from day one so the interface does not break when
conversation support is added. Implementations may ignore it initially.

## Implementations

### `ClaudeDirectBackend`

Uses `anthropic-java` SDK. Prompt construction lives here — not shared.

Auth selected by `casehub.drafthouse.critique.claude.auth-mode`:

| Value | Mechanism |
|---|---|
| `api-key` (default) | Reads `ANTHROPIC_API_KEY` env var |
| `vertex` | `AnthropicVertexAI` with Google Application Default Credentials |
| `bedrock` | AWS SDK transport (future — stub for now) |

Vertex requires `ANTHROPIC_VERTEX_PROJECT_ID` and `CLOUD_ML_REGION` (or
`VERTEX_AI_LOCATION`) env vars — matches current user setup.

### `LangChain4jBackend`

Uses `quarkus-langchain4j`. Defines a `@RegisterAiService` interface for the
reviewer. Provider (Anthropic, OpenAI, Vertex, Ollama) is entirely
`application.properties` config — no Java change to swap providers.

Prompt construction lives here — not shared with `ClaudeDirectBackend`.
Divergence between the two prompt strategies is intentional: it is useful data
about what each runtime naturally encourages.

## CDI wiring

Active backend selected by config property:

```properties
casehub.drafthouse.critique.backend=claude-direct   # or: langchain4j
```

A CDI producer bean reads this property and returns the appropriate
`ReviewerBackend` instance. Both beans exist in the application; only one is
active at runtime. `@LookupIfProperty` or a simple `@Produces` factory covers
this cleanly in Quarkus.

`CritiqueResource` injects `ReviewerBackend` — it has no knowledge of which
implementation is active.

## Relationship to future SPIs

**ReviewStrategy SPI** (post-MVP, FEATURES.md) covers personality libraries,
multi-reviewer orchestration, and conversation threading. It is orthogonal to
`ReviewerBackend` — the strategy *uses* the backend, it does not replace it.

Do not conflate them now. The boundary between these two SPIs will be clearer
after Phase 2 validates what the reviewer actually needs to do.

## Out of scope

- Streaming response (add later — interface accommodates it)
- Conversation history management (carried in context; impls may ignore for now)
- ReviewStrategy SPI (post-MVP)
- AWS Bedrock auth (stub only — not needed until Bedrock is a real target)

## Testing

- Unit: mock `ReviewerBackend` in `CritiqueResource` tests — verifies the
  resource layer without an LLM
- Integration: `ClaudeDirectBackend` tested against real API in a profile-gated
  integration test (skipped in CI unless credentials are present)
- `LangChain4jBackend`: same pattern — Quarkus test profile with a mock
  LangChain4j provider for unit; real provider for integration
- No Playwright E2E for critique in Phase 2 (UI panel is a placeholder stub)
