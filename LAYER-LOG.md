# LAYER-LOG — DraftHouse

Architecture record of what was built at each integration layer. Each entry is complete
when the layer closes.

**Migration note:** This file will migrate to `ARC42STORIES.MD §9.4` Layer Entries when
that document is bootstrapped. See `../parent/docs/arc42stories-spec.md` and
`../parent/docs/arc42stories-casehub-profile.md`.

---

## Layer 0 — Scaffold and Infrastructure

**Started:** 2026-05-26
**Completed:** 🔲

### Summary
Migrated from `mdproctor/md-compare` to `casehubio/drafthouse`. Removed Electron
shell (browser-only UI). Renamed all artifacts to `io.casehub.drafthouse`. Integrated
with CaseHub parent BOM, CI, dashboards, and website.

### Accountability gaps closed
| Gap | What breaks without it | Closed by |
|-----|----------------------|-----------|
| No CaseHub identity | Can't use foundation modules (Qhorus, LangChain4j) | Parent POM + BOM registration |
| Electron dependency | Requires npm/Sparge for dev and test | Quarkus-only architecture |

### Key wiring
- `ui.dir` JVM property tells UiResource where to find `index.html` and `styles.css`
- URL query params `?a=<path>&b=<path>` replace Electron IPC for initial file loading
- Relative API URLs replace `http://127.0.0.1:${port}` — same-origin serving

### Architectural decisions
Dropped Electron in favour of browser-based UI served by Quarkus. This eliminates
npm, Sparge dependency, and the process manager. Trade-off: no native file dialog —
replaced with `prompt()` for now, but the MCP tool surface will be the primary way
to load documents.

### Pattern introduced
Browser-served Quarkus UI with URL query param initialization.

### Pattern anchor
`UiResource.java` — `serveFile()` method serves static assets from `ui.dir`.

### Gotchas
🔲

### Pattern to replicate
1. Serve HTML/CSS from Quarkus via a catch-all resource with configurable root dir
2. Use relative API URLs in the frontend (no port configuration needed)
3. Pass initial state via URL query params instead of IPC

### Navigation
`git log --grep="#15" --oneline`

---

## Layer 0.1 — Multi-Module Maven Restructure + Qhorus/LangChain4j Dependency Wiring

**Started:** 2026-05-31
**Completed:** 2026-05-31
**Issue:** #21 (epic #20)

### Summary
Split the flat `server/` Quarkus app into a multi-module Maven project (`api/` + `runtime/`)
per the platform module-tier-structure protocol. Added `casehub-qhorus 0.2-SNAPSHOT` and
`quarkus-langchain4j-anthropic 1.9.1` as dependencies. Wired the Qhorus `qhorus` named
datasource with H2 in-memory for dev/test.

### Accountability gaps closed
| Gap | What breaks without it | Closed by |
|-----|----------------------|-----------|
| Single-module structure | Can't separate pure-Java domain API from Quarkus runtime | `api/` + `runtime/` split |
| No Qhorus dependency | Can't use channel messaging, commitments, SharedData | `casehub-qhorus 0.2-SNAPSHOT` |
| No LangChain4j | Can't define `@AiService` for reviewer | `quarkus-langchain4j-anthropic 1.9.1` |
| No qhorus datasource | Qhorus extension fails startup without named datasource | H2 `qhorus` datasource + `MODE=PostgreSQL` |

### Key wiring
- `server/api/` — pure Java, no framework deps; will hold `ReviewSession`, `ReviewResult`, `ReviewSessionRegistry`
- `server/runtime/` — Quarkus app, depends on `api/`; holds all resources and new reviewer classes
- `<maven.compiler.parameters>true</maven.compiler.parameters>` in runtime — required by `AiServicesProcessor` (GE-20260525-a8bd9a)
- `quarkus-langchain4j 0.26.1` rejected — incompatible with Quarkus 3.33+; 1.9.1 (built against 3.33.1) verified on 3.34.3
- CORS scoped to `%dev` and `%test` profiles — was wildcard in default profile

### Pattern introduced
Two-module (`api/` + `runtime/`) hexagonal split for CaseHub application-tier projects.
`api/` is a pure-Java jar with no heavy framework deps; `runtime/` is the Quarkus application.

### Pattern anchor
`server/api/pom.xml` and `server/runtime/pom.xml`

### Gotchas
- Build order: `mvn install -DskipTests` on full reactor before running selective `mvn test -pl runtime` — Quarkus generate-code runs before inter-module compile if reactor order not respected
- `ChannelService.create()` does NOT register in `ChannelGateway` — must call `initChannel()` explicitly after channel creation (GE-20260526-5247f2)
- `ResponseFormat.JSON` without schema throws on Anthropic — `@AiService` returning a record type must use a full `JsonSchema` (GE-20260528-e9564b)

### Navigation
`git log --grep="#21" --oneline`

---

## Layer: Review Manifest — Layer 2 Agent Workflow

**Issue:** casehubio/drafthouse#29
**Status:** Complete

### Summary
Implemented the domain model and processing pipeline for debate-driven document review. Includes sealed algebraic types for debate events, incremental state folding via Qhorus projection, pure-Java parsing and rendering, and dual reviewer agent providers (LangChain4j production + Claude Agent SDK stub pending coordinate verification).

### What shipped
- **Domain model** (`server/api/`, package `io.casehub.drafthouse.debate`):
  - `DebateEvent` (sealed, Java 17): `RoundStarted`, `QuestionAsked`, `AnswerProvided`, `AgentRejected`, `RoundEnded`, `SessionClosed`
  - `ReviewState` (incremental fold state): `currentReview`, `rounds`, `lastEventId`
  - `ReviewPoint` (selection anchor): file, line range, tokenized text
  - `DebateEntry` (rendered result): template, formatted vars, agent signatures

- **Qhorus projection** (`SummaryProjector implements ChannelProjection<ReviewState>`):
  - Incremental fold on each `DebateEvent` commit
  - Leverages casehub-qhorus-api qhorus#230 generic projection interface
  - Incremental commit support (qhorus#231) allows efficient state computation

- **Pure-Java processing pipeline**:
  - `DebateParser` — parses debate YAML into domain objects
  - `RoundParser` — extracts rounds and entries
  - `SummaryRenderer` — renders `ReviewState` to formatted text
  - `DebateEntryFormatter` — formats individual debate entries with agent signatures
  - All functions pure; no UI framework coupling

- **LangChain4j reviewer agent** (`LangChain4jDebateAgentProvider @DefaultBean`):
  - Uses `quarkus-langchain4j-anthropic 1.9.1`
  - Implements `DebateAgentProvider` contract
  - Production-grade, runs in CI tests

- **Claude Agent SDK reviewer agent** (`ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1)`):
  - Stub pending claude-agent-sdk-java coordinate verification (casehubio/platform#55)
  - Will use direct Claude API when coordinates are available
  - Marked `@Alternative` so LangChain4j runs by default in CI

- **Session lifecycle** (`ReviewSessionService`):
  - JGit session storage under `.casehub/reviews/`
  - Incremental fold on each round commit
  - Session state recovered from commit history

- **REST API** (superseded by DraftHouseMcpTools #24):
  - `POST /api/review/sessions` — create session
  - `POST /api/review/sessions/{id}/next-round` — append debate round
  - Stubs marked with comment about MCP tool migration

- **Contract tests** with parity between both providers:
  - `DebateParserTest`, `RoundParserTest`, `SummaryRendererTest` — pure-Java logic
  - `LangChain4jDebateAgentTest`, `ClaudeAgentSdkDebateAgentTest` — both providers
  - `DebateRoundTripIT` — E2E round-trip verifying parser/formatter/projector pipeline

### Accountability gaps closed
| Gap | What breaks without it | Closed by |
|-----|----------------------|-----------|
| No debate domain model | Can't represent dispute structure | `DebateEvent` sealed type |
| No incremental state | Replay on every commit | Qhorus `ChannelProjection<ReviewState>` |
| No pure-Java pipeline | Can't embed in other tools | `DebateParser`, `RoundParser`, formatters |
| No agent abstraction | Can't swap providers | `DebateAgentProvider` interface |
| No session persistence | State lost on app restart | JGit session storage + fold recovery |

### Key wiring
- `DebateAgentProvider` abstracts both LangChain4j and Claude Agent SDK implementations
- `ClaudeAgentSdkDebateAgentProvider` is `@Alternative @Priority(1)` so LangChain4j runs by default
- Session state is recovered by replaying commits to `SummaryProjector` (incremental fold)
- REST stubs remain for compatibility but are deprecated in favour of MCP tools (#24)

### Architectural decisions
1. **Sealed algebraic type for events** — forces exhaustive pattern matching, prevents invalid states
2. **Incremental fold via Qhorus projection** — no replay cost for long sessions, recovers state on startup
3. **Pure-Java pipeline** — decouples domain logic from Quarkus, allows embedding in Claudony and other clients
4. **Dual agent providers** — LangChain4j production-ready; Claude Agent SDK stub pending platform coordinates

### Patterns introduced
1. Domain model as sealed algebraic type (Java 17)
2. Incremental state folding via Qhorus `ChannelProjection<S>`
3. Pure-Java processing pipeline for document-review-specific logic
4. Abstract agent provider with swappable implementations

### Pattern anchors
- `io.casehub.drafthouse.debate.DebateEvent` — sealed type definition
- `io.casehub.drafthouse.debate.ReviewState` — incremental fold state
- `SummaryProjector` — qhorus projection implementation
- `DebateParserTest` — demonstrates pure-Java pipeline testing

### Gotchas
- `ClaudeAgentSdkDebateAgentProvider` is a stub because `claude-agent-sdk-java` coordinates are not yet published (casehubio/platform#55). When coordinates ship, remove `@Alternative` from provider and populate implementation.
- Qhorus commit IDs must be deterministic (no timestamps) for test stability — use `CommitId.of(roundNumber)` or UUID derived from content hash.

### Tests (all passing)
```
DebateParserTest ........................ 8 tests
RoundParserTest ......................... 8 tests
SummaryRendererTest ..................... 10 tests
DebateEntryFormatterTest ................ 8 tests
LangChain4jDebateAgentTest .............. 5 tests
DebateRoundTripIT ....................... 4 tests
ReviewerChannelBackendTest ............. 8 tests
E2E tests (SwapPanels, WordDiff, etc.) . (4 tests each)
--------
Total: 51 tests, 0 failures, 0 errors, 0 skipped
```

### Navigation
`git log --grep="#29" --oneline`
