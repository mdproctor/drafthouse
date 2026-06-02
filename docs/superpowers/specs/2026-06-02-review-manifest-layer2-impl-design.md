# Review Manifest — Layer 2 Implementation Design
**Date:** 2026-06-02
**Status:** Draft — pending Layer 1 stabilisation
**Issue:** casehubio/drafthouse#29
**Depends on:** `docs/superpowers/specs/2026-06-01-review-manifest-design.md` (Layer 1 format — must be Approved before this spec advances)

---

## Scope

Layer 2 of the review manifest system: the Java implementation that drives agent
review rounds, formats entries into `debate.md`, and projects `debate.md` into
`summary.md`. Layer 3 (DraftHouse UI, Next/Back/Run Until) and Qhorus DebateChannel
(#27) are explicitly out of scope.

**Qhorus alignment:** `SummaryProjector` implements `ChannelProjection<ReviewState>`
from `casehub-qhorus-api` (qhorus#230 — shipped). `server/api/` gains a compile
dependency on `casehub-qhorus-api`. The incremental fold cursor from qhorus#231
(also shipped) is `MessageView.id()` (Long). `SummaryRenderer` stays a local class
until qhorus#232 (`ProjectionRenderer<S>` SPI + named registry) ships — the
qhorus#230 javadoc explicitly states rendering is consumer-side.

The session infrastructure (`ReviewSessionService`, git repo per session) will be
substantially replaced when #27 (DebateChannel) ships. This is a deliberate
local-first implementation with standalone value. What migrates cleanly: the domain
model, `SummaryProjector`, `SummaryRenderer`, `DebateAgentProvider` SPI.

---

## Module Structure

Three modules under `server/` in the main call path:

```
server/
  api/           — SPI + domain model. Pure Java, no Quarkus, no JPA.
  runtime/       — Quarkus 3.34.3 app. DebateAgentProvider @DefaultBean.
                   ReviewSessionService. Stub REST entry points.
  claude-agent/  — Optional module. ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1).
                   Activates by classpath presence. Requires Claude Code CLI.
```

`server/parser/` exists only as an optional debugging utility (plain `main()` in
`server/api/`) — it is **not** in the round-commit call path. All processing classes
are called in-process from `ReviewSessionService`; no subprocess roundtrip.

CDI priority ladder (`persistence-backend-cdi-priority.md`):
- `@DefaultBean` → `LangChain4jDebateAgentProvider` (always present in runtime)
- `@Alternative @Priority(1)` → `ClaudeAgentSdkDebateAgentProvider` (activates when
  `server/claude-agent/` is added as a compile dependency to `server/runtime/`)

---

## Package Naming

All new Layer 2 types live in `io.casehub.drafthouse.debate` to avoid collision with
the existing critique session model (`ReviewSessionRegistry`, `ReviewResult` in the
Qhorus-based critique backend in `io.casehub.drafthouse`). The word "review" is
reserved for the existing critique domain; the new debate system uses "debate"
throughout.

---

## server/api/ — Domain Model and SPIs

Package: `io.casehub.drafthouse.debate`

### Event model

**`DebateEvent`** — sealed interface, Java 17 records, exhaustive at compile time:

```java
public sealed interface DebateEvent
    permits DebateEvent.RaiseEvent, DebateEvent.ResponseEvent,
            DebateEvent.FlagHumanEvent, DebateEvent.AgentMemo {

    record RaiseEvent(int round, AgentType agent,
                      Priority priority, Scope scope,
                      String location, String content) implements DebateEvent {}

    record ResponseEvent(int round, AgentType agent,
                         String targetId, EntryType type,
                         String content, ReviewStatus statusDirective) implements DebateEvent {}

    record FlagHumanEvent(int round, AgentType agent,
                          String content, String targetId,
                          ReviewStatus statusDirective) implements DebateEvent {}

    record AgentMemo(int round, AgentType agent, String content) implements DebateEvent {}
}
```

Note: no `id` field on events. IDs are assigned by `DebateEntryFormatter` when
rendering to `debate.md`, not by the agent.

Supporting enums: `AgentType` (REV, IMP), `EntryType` (RAISE, AGREE, DISPUTE,
QUALIFY, FLAG_HUMAN), `Priority` (P1, P2, P3), `Scope` (SYSTEMIC, ISOLATED),
`ReviewStatus` (OPEN, ACTIVE, AGREED, PENDING_HUMAN).

### Fold state

```java
public record PointClassification(Priority priority, Scope scope, String location) {}
public record ThreadEntry(String entryId, AgentType agent, int round,
                          EntryType type, String content) {}
public record ReviewPoint(String id, PointClassification classification,
                          List<ThreadEntry> thread, ReviewStatus currentStatus) {}
public record FlagEntry(String entryId, int round, AgentType agent, String content) {}
public record ReviewState(LinkedHashMap<String, ReviewPoint> points,
                          List<FlagEntry> humanFlags) {}
```

### Agent SPI

```java
public interface DebateAgentProvider {
    List<DebateEntry> executeReviewerRound(DebateRoundContext context);
    List<DebateEntry> executeImplementerRound(DebateRoundContext context);
}

public record DebateRoundContext(
    String specContent,
    String debateContent,       // full current debate.md text
    ReviewState currentState,   // pre-computed projection — agents do not re-parse
    int roundNumber,
    String sessionId
) {}

public record DebateEntry(
    // No id field — assigned by DebateEntryFormatter
    EntryType type,
    String targetId,            // null for RAISE entries
    String content,
    ReviewStatus statusDirective,   // null when no status update
    Priority priority,          // non-null for RAISE only
    Scope scope,                // non-null for RAISE only
    String location             // optional location hint, may be null
) {}
```

`DebateRoundContext` includes `currentState` (pre-computed `ReviewState`) so agents
are not forced to re-parse `debateContent` to find open points.

### Processing classes — pure functions, no CDI

**`DebateParser`** — `List<DebateEvent> parse(String debateMarkdown)`:
Parses full accumulated `debate.md`. Detects round comment boundaries
(`<!-- Round N — Agent -->`), HTML anchors (`<a name="..."/>`), entry type headers,
body content, and `→ [ID] Status:` directives. Also detects memo blocks.
IDs are extracted from anchors and stored on the returned `ThreadEntry` objects
in `ReviewState` (via `SummaryProjector`), not on `DebateEvent`.

**`RoundParser`** — `List<DebateEntry> parse(String roundSnippet)`:
Parses the lightweight round-snippet format produced by agents (see Agent Output
Format below). Lenient — recovers from minor formatting variations. Separate from
`DebateParser` which targets full accumulated `debate.md`.

**`SummaryProjector` implements `ChannelProjection<ReviewState>`** (qhorus#230):
Pure left-fold (`PP-20260602-b748c9`). The `apply(ReviewState, MessageView)` method
switches on `MessageType`:

```java
return switch (message.type()) {
    case QUERY   -> handleRaise(state, message);           // debate RAISE
    case RESPONSE, DECLINE -> handleResponse(state, message); // AGREE/QUALIFY/DISPUTE
    case HANDOFF -> handleFlagHuman(state, message);       // FLAG_HUMAN
    case EVENT   -> state;   // AgentMemo — no-op (not delivered to agent context)
    case COMMAND, STATUS, DONE, FAILURE -> state;          // not expected in DebateChannel
};
```

AGREE vs QUALIFY sub-classification (both arrive as `RESPONSE`): convention TBD
by #27 (DebateChannel). Likely via content prefix or `artefactRefs` field.

For the v1 file-based path, a convenience method `project(List<DebateEvent> events)`
delegates to `apply()` via synthetic `MessageView` objects:
- `RaiseEvent` → `MessageView(type=QUERY, sender=agent.name(), content=..., correlationId=null, ...)`
- `ResponseEvent(AGREE)` → `MessageView(type=RESPONSE, correlationId=targetId, ...)`
- `ResponseEvent(DISPUTE)` → `MessageView(type=DECLINE, correlationId=targetId, ...)`
- `ResponseEvent(QUALIFY)` → `MessageView(type=RESPONSE, content="[QUALIFY]"+content, ...)`
- `FlagHumanEvent` → `MessageView(type=HANDOFF, ...)`
- `AgentMemo` → `MessageView(type=EVENT, ...)` — folded as no-op

Synthetic `MessageView` objects use `null` for Qhorus-specific fields (`id`, `channelId`,
`createdAt`, etc.) that are only populated by the real message store.

Incremental fold (qhorus#231): `ReviewSessionService` tracks the count of events
already folded. After each round, only new events are applied to the existing
`ReviewState` — no full replay. Cursor = event count in v1 (file-based);
`MessageView.id()` (Long) when Qhorus-backed.

Status authority: last `→ [ID] Status:` directive for each point ID in document
order wins. Within-round ordering: `DebateEntryFormatter` appends raise entries
first, responses in sequence, flag-human entries last — document order within a
round is deterministic and `SummaryProjector` relies on it.

**`SummaryRenderer`** — `String render(ReviewState state)`:
Deterministic template rendering. Agreed points rendered with strikethrough headers.
`FlagEntry` list rendered at bottom as `⚑` section. Memos never appear.

**`DebateEntryFormatter`** — `String format(List<DebateEntry> entries, int round, AgentType agent, String existingDebate)`:
The inverse of `DebateParser`. Turns `List<DebateEntry>` + round metadata into
appendable `debate.md` text. Assigns IDs: scans `existingDebate` to count prior
entries for this round+agent to determine `seq`, then generates `[R<round>-<AGENT>-<seq>]`.
Appends round comment boundary if this is the first entry in a new round. Produces
HTML anchors, entry type headers, body, status lines, and memo block.
Append order within a round: RAISE entries first, AGREE/DISPUTE/QUALIFY responses
in sequence, FLAG_HUMAN entries last. This ordering is relied upon by `SummaryProjector`.

### Agent output format (round snippet)

Both `LangChain4jDebateAgentProvider` and `ClaudeAgentSdkDebateAgentProvider` produce
entries in a **round snippet** format — not full `debate.md` syntax, not strict JSON
schema. The round snippet is a simple delimited format that `RoundParser` handles
leniently:

```
TYPE: raise
PRIORITY: P1
SCOPE: Isolated
LOCATION: §3.2
CONTENT: Both `start_review` and `begin_review` appear — no canonical form.

TYPE: agree
TARGET: R1-REV-001
STATUS: Agreed
CONTENT: Standardising to `start_review` throughout (§3.2, §5.1).

MEMO: §4 feels under-specified across the board — suspect systemic gap.
```

This format is easy for an LLM to produce reliably, easy to parse leniently, and
avoids coupling agents to the precise anchor/comment/emoji syntax of `debate.md`.
`DebateEntryFormatter` renders round snippets to proper `debate.md` format.

---

## server/runtime/ — LangChain4j Default Provider + Session Management

### `LangChain4jDebateAgentProvider @DefaultBean`

Uses `@RegisterAiService`. Single API call per round — `DebateRoundContext` content
passed in context. Returns round snippet as text; `RoundParser` converts to
`List<DebateEntry>`. Works with any `ChatModel` configured via
`quarkus.langchain4j.*` properties.

System prompts loaded from classpath resources:
`src/main/resources/prompts/spec-reviewer.txt` and `spec-implementer.txt`.
Round snippet format specification is included in the system prompt.

### `ReviewSessionService` (blocking I/O — `@Blocking`)

All methods annotated `@Blocking` — git operations (JGit) are file I/O and must
run on Quarkus worker threads, not the event loop.

**JGit** is used for all git operations — pure Java, controllable, exception-based,
no subprocess. No dependency on system `git` binary.

**Turn alternation:** `ReviewSessionService` determines the next agent by counting
`<!-- Round N — Agent -->` comment boundaries in `debate.md`. Round 0 (session open,
no boundaries) → next is REV. Alternates REV/IMP thereafter. The caller may
override by passing an explicit `AgentType` parameter.

**Round-commit sequence:**
1. Call `DebateAgentProvider.executeReviewerRound()` or `executeImplementerRound()`
2. Parse returned round snippet via `RoundParser` → `List<DebateEntry>`
3. Format via `DebateEntryFormatter` → appendable `debate.md` text
4. Append to `debate.md`
5. Incremental fold: parse full `debate.md` → `List<DebateEvent>`, apply only
   events since last cursor via `SummaryProjector.project()`, update cursor
6. Render via `SummaryRenderer` → rewrite `summary.md`
7. JGit: `git add -A && git commit -m "round-N: REV|IMP"`

**Failure modes (v1 — no transactional rollback):**
- Step 2-4 failure (parse/format error): `debate.md` unchanged, exception propagated, session consistent.
- Step 5-6 failure (projection error): `debate.md` updated with new entries, `summary.md` stale. Exception propagated. Session inconsistent.
- Step 7 failure (commit error): both files updated, uncommitted. Exception propagated. Session inconsistent.
- Recovery for inconsistent state: `git -C <session-path> reset --hard HEAD` restores last clean commit. Document this in operator runbook.
- Transactional temp-file swap (write to temp, atomic rename) is v2.

**Concurrency (v1):** Concurrent round dispatch against the same session is not
supported. Callers must serialise access. Layer 3 enforces this via UI state
(only one Next/Run Until in flight at a time). No session-level locking in v1.

**Session ID:** `drafthouse-<YYYYMMDD>-<6-char-hex>` from spec path + timestamp.

**Parser jar path (v1):** Not applicable — processing classes called in-process.
`debate-parser-runner.jar` exists only as a debugging utility, not in the
round-commit path. Configurability (#28) applies only to session storage base path.

### Layer 2 Entry Points (stub — superseded by Layer 3)

Stub REST endpoints in `server/runtime/` provide a testable trigger for Layer 2
before Layer 3 exists. Explicitly marked `@Deprecated` at declaration — to be
replaced by `DraftHouseMcpTools` (#24):

```
POST /api/review/sessions
  Body: { "specPath": "/absolute/path/to/spec.md" }
  Returns: { "sessionId": "drafthouse-20260602-a3f2", "sessionPath": "..." }

POST /api/review/sessions/{sessionId}/next-round
  Returns: { "roundNumber": 1, "agent": "REV", "entriesAdded": 3, "humanFlagRaised": false }
```

---

## server/claude-agent/ — Claude Agent SDK Provider (optional)

**`ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1)`**

⚠️ **Maven coordinate unverified:** The artifact
`com.github.spring-ai-community:claude-agent-sdk-java:1.0.0` was identified on
GitHub but its Maven Central availability has not been confirmed. Verify before
implementing this module.

Uses `ClaudeAsyncClient` → Mutiny `Multi` via `Multi.createFrom().publisher(flux)`.
Launches a genuine Claude Code CLI session per round. Requires Claude Code CLI
installed and authenticated on host.

**Round completion:** The agent calls `finish_round()` MCP tool. If the agent
does not call `finish_round()` within a configurable timeout (default: 5 minutes),
`ClaudeAgentSdkDebateAgentProvider` terminates the session and returns entries
collected so far. A minimum-entries safeguard (at least one entry required) prevents
returning empty rounds silently.

**MCP tools exposed to the agent** (in-process MCP server):

| Tool | Parameters | Returns | Behaviour |
|---|---|---|---|
| `submit_debate_entry` | `type: string, content: string, target_id?: string, status?: string, priority?: string, scope?: string, location?: string` | `{ "accepted": true, "entry_seq": 3 }` | Validates type/status enums; accumulates entry in session state; does not write to disk until `finish_round` |
| `read_spec_section` | `heading: string` | `{ "content": "..." }` | Returns section content from spec file; fuzzy heading match |
| `read_debate_entry` | `entry_id: string` | `{ "content": "...", "type": "...", "status": "..." }` | Looks up entry by ID in current `debate.md` |
| `finish_round` | — | `{ "entries_submitted": N }` | Signals round complete; triggers flush of accumulated entries to `List<DebateEntry>` return value |

Entries accumulate in memory during the session; only on `finish_round` does
`ClaudeAgentSdkDebateAgentProvider` return them to `ReviewSessionService` for
formatting and commit. The Claude session itself never writes to `debate.md`.

Activates by classpath presence. Off by default in CI. Requires Claude Code CLI
on PATH.

---

## Testing

### Contract test

Abstract base class `DebateAgentProviderContractTest` in `server/runtime/`
test sources (avoids `<classifier>tests</classifier>` Maven complexity).
`ClaudeAgentSdkDebateAgentProviderTest` in `server/claude-agent/` depends on
`server/runtime/` test jar via `<classifier>tests</classifier>`.

**Scope of contract tests:** structural and protocol verification — correct entry
types, ID scheme adherence, status directive presence, memo exclusion. Tests use
mock/stub models. They do NOT verify reasoning quality (whether the agent raises
meaningful points). Behavioral/reasoning tests require real model integration tests
and are documented as out of CI scope.

```java
public abstract class DebateAgentProviderContractTest {
    protected abstract DebateAgentProvider provider();

    @Test void reviewerProducesAtLeastOneRaiseEntry();
    @Test void implementerRespondsToAllOpenPoints();
    @Test void entryTypeIsValidEnum();
    @Test void statusDirectivePresentOnResponseEntries();
    @Test void flagHumanEntryHasContent();
    @Test void memoNotIncludedInReturnedEntries();
    @Test void agreeEntryHasTargetId();
    @Test void raiseEntryHasPriorityAndScope();
    @Test void roundSnippetParsesCleanlyViaRoundParser();
}
```

Fixtures in `server/runtime/src/test/resources/fixtures/debate/` — shared.

**Concrete implementations:**

```java
// Always runs in CI — uses stub model
class LangChain4jDebateAgentProviderTest extends DebateAgentProviderContractTest {
    protected DebateAgentProvider provider() {
        return new LangChain4jDebateAgentProvider(mockChatModel());
    }
}

// Off by default — requires Claude Code CLI
@IfBuildProperty(name = "drafthouse.claude-agent.test.enabled", stringValue = "true")
class ClaudeAgentSdkDebateAgentProviderTest extends DebateAgentProviderContractTest {
    protected DebateAgentProvider provider() {
        return new ClaudeAgentSdkDebateAgentProvider(realClaudeClient());
    }
}
```

### Unit tests — processing classes

`DebateParserTest`, `RoundParserTest`, `SummaryProjectorTest`, `SummaryRendererTest`,
`DebateEntryFormatterTest` — pure Java, no Quarkus, no mocking. Fast.

`DebateEntryFormatterTest` covers: ID assignment (seq counting), append ordering
(raise before responses before flags), round comment boundary insertion, memo block
rendering.

`SummaryProjectorTest` covers: same-round multiple status directives for same point
(last in document order wins), flag-human status transition, agreed point
strikethrough, empty debate (no points).

### E2E — round-trip

`DebateRoundTripIT` — exercises the full path: `DebateAgentProvider` stub →
`RoundParser` → `DebateEntryFormatter` → append → `DebateParser` → `SummaryProjector`
→ `SummaryRenderer`. Verifies no information is lost or corrupted across the
format/parse cycle. Fixture pairs: append to empty debate, append round 2 to existing
round 1, multi-point with mixed statuses.

---

## Platform Alignment

| Protocol | Applied where |
|---|---|
| `persistence-backend-cdi-priority.md` | `@DefaultBean` / `@Alternative @Priority(1)` for agent providers |
| `spi-adapter-module-placement.md` | `server/claude-agent/` starts in-repo; extract when adopted cross-project |
| `optional-module-pattern.md` | `server/claude-agent/` activates by classpath presence |
| `event-log-left-fold-projection.md` (PP-20260602-b748c9) | `SummaryProjector` left-fold design |
| `auth-retrofit-readiness.md` | No auth types in SPI signatures |
| `reactive-blocking-tier-separation.md` | `ReviewSessionService` `@Blocking`; all git operations on worker thread |
| qhorus#230 `ChannelProjection<S>` SPI | `SummaryProjector implements ChannelProjection<ReviewState>` |
| qhorus#231 incremental projection | Cursor-based fold in `ReviewSessionService`; cursor = event count (v1), `MessageView.id()` (v2) |

---

## Out of Scope

- Layer 3 DraftHouse UI (Next/Back/Run Until) — separate spec; stub REST endpoints
  above are temporary scaffolding
- Qhorus DebateChannel (#27) — `SummaryProjector` ready; session infrastructure will be replaced
- qhorus#232 `ProjectionRenderer<S>` SPI + named registry — `SummaryRenderer` stays local until then
- Session storage path configurability (#28)
- Sub-agent / context management (#26)
- `DebateAgentProvider` AGREE/QUALIFY sub-classification in `MessageType.RESPONSE` — convention TBD in #27
- `server/parser/` as a call-path component — in-process only; CLI exists for debugging
- Transactional round-commit atomicity — v2
- Session-level concurrency control — v2
- Behavioral/reasoning quality tests — real model integration tests, out of CI scope
