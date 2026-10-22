# casehub-drafthouse — Contributor Guide

> Internal architecture, extension points, and development details for platform contributors working on DraftHouse internals.

**GitHub:** [casehubio/drafthouse](https://github.com/casehubio/drafthouse)

---

## Internal Architecture

```
Quarkus Server (drafthouse-server-runner.jar, port 9001)
  ├── GET /api/ping                              ← health check (PingResource)
  ├── GET /api/file?path=                        ← read any local file (FileResource)
  ├── WS  /api/ws                                ← WebSocket push — pages-push wire format (DebateWebSocket)
  ├── GET /                                      ← Quinoa serves bundled webui (TypeScript → app.js)
  ├── MCP tools (review)                         ← DraftHouseMcpTools: start_review, update_selection, query_review, end_review, list_reviewers, get_reviewer_instructions
  ├── MCP tools (debate)                         ← DebateMcpTools: start_debate, raise_point, respond_to, flag_human, post_memo, request_subagent, get_debate_summary, get_debate_summary_at_round, restart_from_round, end_debate, report_context
  ├── MCP tools (documents)                      ← DebateMcpTools: add_document, remove_document, list_documents, set_comparison, export_debate_summary
  ├── MCP tools (workspace)                      ← DebateMcpTools: load_workspace (replay OR watch via WorkspaceWatcher)
  ├── MCP tools (brainstorm)                     ← BrainstormMcpTools: start_brainstorm, present_options, update_option, set_recommendation, mark_eliminated, mark_selected, end_brainstorm
  ├── POST /api/debate/{id}/selection            ← store selection scope (DebateEventResource)
  ├── DELETE /api/debate/{id}/selection           ← clear selection scope (DebateEventResource)
  ├── GET /api/debate/{id}/documents             ← list working set + comparison (DebateEventResource)
  ├── POST /api/debate/{id}/comparison           ← browser comparison change (DebateEventResource)
  ├── GET /api/debate/{id}/snapshot/{index}      ← timeline snapshot content (DebateEventResource)
  ├── GET /api/debate/sessions                   ← active debate sessions (DebateEventResource)
  ├── POST /api/debate/{id}/human/comment        ← HIL comment (HumanActionResource)
  ├── POST /api/debate/{id}/human/raise          ← HIL raise point (HumanActionResource)
  ├── POST /api/debate/{id}/human/override       ← HIL override point (HumanActionResource)
  ├── POST /api/debate/{id}/human/prioritise     ← HIL reprioritise point (HumanActionResource)
  ├── POST /api/debate/{id}/human/batch          ← HIL batch verify/defer (HumanActionResource)
  ├── PATCH /api/brainstorm/{id}/options/{optId} ← browser option status change (BrainstormResource)
  ├── GET /api/brainstorm/sessions               ← active brainstorm sessions (BrainstormResource)
  └── WS  /api/terminal                          ← PTY-over-WebSocket via pty4j (TerminalEndpoint)
```

---

## Module Details

### server/api/ — `casehub-drafthouse-api`

Pure Java domain model. No Quarkus, no JPA, no CDI annotations. Depends on `casehub-agency` (context tracking, message meta, bounded projection), `casehub-qhorus-api`, and `casehub-eidos-api`.

**Package `io.casehub.drafthouse`:**

| Class | Role |
|-------|------|
| `DebateSession` | Debate context: channel ID, session ID, channel name, agent ID, participants map, document set (`DocumentEntry` list), comparison pair, selection scope, context tracker, timeline, workspace path. Thread-safe participant management. |
| `DebateSessionRegistry` | Interface — find, put, remove, persist, activeSessions |
| `DebateSessionSnapshot` | Serializable debate state for persistence (channel, documents, comparison, participants, agent ID) |
| `DebateSessionStore` | SPI — pluggable persistence: `save`, `load`, `remove`, `loadAll` |
| `ReviewSession` | Review context: channel ID, session ID, channel name, instance ID, doc A/B content, selection scope, resolved reviewer |
| `ReviewSessionRegistry` | Interface — find, put, remove, updateSelection |
| `ReviewResult` | Structured feedback from a reviewer agent |
| `ResolvedReviewer` | Resolved reviewer: `agentId`, `name`, `instructions` |
| `DocumentSet` | Thread-safe document collection (synchronized ArrayList): add, remove, find, setComparison |
| `DocumentEntry` | Record: `path` + `label` |
| `DocumentSide` | Enum: `A`, `B` |
| `ComparisonPair` | Record: `pathA`, `pathB` |
| `SelectionScope` | Record: `side`, `startLine`, `endLine`, `selectedText` |
| `BrainstormSession` | Session with options list, lifecycle state (ACTIVE/CONVERGED/ABANDONED) |
| `BrainstormOption` | Record: `id`, `title`, `description`, `tradeoffs`, `status` |

**Package `io.casehub.drafthouse.debate`:**

| Class | Role |
|-------|------|
| `EntryType` | Enum — 17 values: RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED, VERIFIED, DEFERRED, MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR, RESTART_CONTEXT, ROUND_SNAPSHOT, COMMENT, HUMAN_OVERRIDE, REPRIORITISE |
| `AgentType` | Enum — 6 values: REV, IMP, SUPERVISOR, MODERATOR, SELECTOR, HUMAN |
| `DebateAgentProvider` | SPI — `String analyse(AgentTask task)`. Blocking. Single method for LLM invocation in sub-agent handlers. |
| `DocumentSnapshot` | Content snapshot at a timeline point |
| `DocumentTimeline` | Ordered sequence of snapshots across review rounds |
| `SnapshotSource` | Sealed interface for snapshot provenance |
| `ReviewConversationRenderer` | Renders conversation state to markdown |

**Tests:** `BrainstormSessionTest`, `DebateSessionTest`, `DebateSessionStoreContractTest`, `DocumentSetTest`, `ReviewResultTest`, `ReviewSessionTest`, `ReviewConversationRendererTest`

### server/runtime/ — `casehub-drafthouse`

Quarkus 3.34.3 app. All runtime components.

#### MCP Tool Classes

| Class | Tool Group | Tool Count |
|-------|-----------|------------|
| `DraftHouseMcpTools` | Review | 6 tools: start_review, update_selection, query_review, end_review, list_reviewers, get_reviewer_instructions |
| `DebateMcpTools` | Debate + Documents + Workspace | 17 tools: start_debate, raise_point, respond_to, flag_human, post_memo, request_subagent, get_debate_summary, get_debate_summary_at_round, restart_from_round, end_debate, report_context, add_document, remove_document, list_documents, set_comparison, export_debate_summary, load_workspace |
| `BrainstormMcpTools` | Brainstorming | 7 tools: start_brainstorm, present_options, update_option, set_recommendation, mark_eliminated, mark_selected, end_brainstorm |

**Total: 30 MCP tools across 3 classes.**

#### REST Resources

| Class | Path | Purpose |
|-------|------|---------|
| `PingResource` | `/api/ping` | Health check |
| `FileResource` | `/api/file` | Local file read |
| `DebateEventResource` | `/api/debate/` | Debate session management: selection, documents, comparison, snapshots, sessions list. Also internal push methods for WebSocket events. |
| `HumanActionResource` | `/api/debate/{id}/human/` | 5 HIL endpoints: comment, raise, override, prioritise, batch |
| `BrainstormResource` | `/api/brainstorm/` | Browser option status changes, session listing |

#### Services and Registries

| Class | Role |
|-------|------|
| `BrainstormService` | Brainstorming business logic: session lifecycle, option state transitions |
| `BrainstormSessionRegistry` | In-memory brainstorm session store |
| `DebateSessionRegistryImpl` | `DebateSessionRegistry` implementation with optional persistence delegate |
| `ReviewSessionRegistryImpl` | `ReviewSessionRegistry` implementation |
| `DraftHouseConfig` | Quarkus `@ConfigMapping` at `casehub.drafthouse.*` — reviewer, storage, context, persistence, debate sub-interfaces |
| `DraftHouseInstances` | Constants — `HUMAN_INSTANCE_ID` |

#### Channel System

| Class | Role |
|-------|------|
| `ReviewerChannelBackend` + `Factory` | Channel backend for review sessions — processes incoming messages through reviewer agent |
| `DebateChannelBackend` + `Factory` | Channel backend for debate sessions — pushes WebSocket events, dispatches SUB_TASK_REQUEST via CDI, triggers `orchestrator.converse()` on first message for autonomous sessions, terminates orchestrator on FLAG_HUMAN |
| `ChannelAgentDispatcher` | Extends `blocks.channel.ChannelAgentDispatcher` — routes `SUB_TASK_REQUEST` to handlers, posts findings back |
| `DebateParticipants` | `ensureSender()` — idempotent per-role Qhorus instance registration |

#### Reviewer System

| Class | Role |
|-------|------|
| `DraftHouseReviewerRegistry` | `AgentRegistry` implementation for DraftHouse reviewers |
| `ReviewerDescriptorSeeder` | `@Startup` — seeds 4 reviewer `AgentDescriptor` instances into registry |
| `ReviewerResolver` | Resolves agent ID to `ResolvedReviewer` — delegates to `SimplePromptRenderer` |
| `SimplePromptRenderer` | Renders reviewer briefing + resource context into full instructions |
| `DocumentReviewer` | LLM-backed document review (Phase 2 wiring) |

#### Sub-Agent Handlers (`handler/` package)

All extend `AbstractDebateSubAgentHandler` which implements `ChannelAgentHandler`:

| Handler | Task Type | Purpose |
|---------|-----------|---------|
| `VerifyHandler` | `VERIFY` | Verify a specific claim or finding against source |
| `ArbitrateHandler` | `ARBITRATE` | Arbitrate between competing positions |
| `DeepAnalysisHandler` | `DEEP_ANALYSIS` | Deep-dive analysis of a contested point |
| `ConsistencyCheckHandler` | `CONSISTENCY_CHECK` | Check proposed resolution for consistency |
| `NeutralSummaryHandler` | `NEUTRAL_SUMMARY` | Generate neutral summary of debate state |
| `CustomHandler` | `CUSTOM` | Custom analysis with freeform context |

Handlers invoke `DebateAgentProvider.analyse(AgentTask)` and post the result as `SUB_TASK_FINDING` (or `SUB_TASK_ERROR` on failure) back to the channel.

#### Agent Provider System

| Class | Role |
|-------|------|
| `PlatformDebateAgentProvider` | `@DefaultBean` — implements `DebateAgentProvider` via platform `AgentProvider` SPI. Blocking streaming invocation. |
| `ClaudeAgentSdkDebateAgentProvider` (claude-agent module) | Optional — displaces `PlatformDebateAgentProvider` when the claude-agent module is on the classpath. Pending `casehubio/platform#55`. |

#### Autonomous Orchestration

Implements blocks' `ConversationOrchestrator` SPIs for server-driven autonomous debates (when `start_debate(autonomous=true)`):

| Class | Implements | Role |
|-------|-----------|------|
| `DebateAgentInvoker` | `AgentInvoker<String>` | Wraps `DebateAgentProvider` — looks up per-agent system prompts, delegates to `analyse(AgentTask)` |
| `DebatePromptAssembler` | `PromptAssembler` | Assembles document content, selection scope, and conversation history (system prompt excluded — handled by invoker) |
| `DebateResponseBuilder` | `ResponseMessageBuilder` | Encodes LLM responses as `DHMETA:`-prefixed debate entries with inferred entry type and round |

**Triggering:** `DebateChannelBackend.post()` triggers `orchestrator.converse()` on a virtual thread when the first message arrives on an autonomous session. An `AtomicBoolean` CAS guard (`DebateSession.markConverseStarted()`) ensures exactly-once triggering. Termination uses `CompositeTermination` of `AllAgreedTermination`, `ContestedEscalation(3)`, and `MaxIterationsTermination(20)`. External interruption (FLAG_HUMAN, `endDebate`) calls `orchestrator.terminate()`.

#### Debate Projection and Protocol

| Class | Package | Role |
|-------|---------|------|
| `DebateProtocol` | `debate/` | Constants — `META_SENTINEL` for ChannelMessageMeta encoding |
| `DebateChannelProjection` | `debate/` | Qhorus `Projection` — folds channel messages into `ConversationState` (points, memos, sub-task findings). Inner class `RoundBoundedProjection` for round-limited views. |
| `ReviewChannelProjection` | `debate/` | Qhorus `Projection` for review sessions |
| `DebateStreamEntry` | `debate/` | Serializable debate entry for WebSocket push — parsed from raw channel messages |

#### Workspace Replay System

| Class | Package | Role |
|-------|---------|------|
| `WorkspaceParser` | `debate/` | Parses design-review workspace directory: `responses/reviewer-N.md` and `implementor-N.md` files into structured rounds with issues |
| `WorkspaceReplayAdapter` | `debate/` | Replays parsed workspace into debate channel as Qhorus messages |
| `WorkspaceWatcher` | `debate/` | Watches in-progress workspace directory for new response files via `directory-watcher`. Dispatches incremental replay on file changes. Self-removes on completion. |
| `ProgressLogParser` | `debate/` | Parses `progress.log` to detect terminal states (review completion) |

#### Persistence

| Class | Role |
|-------|------|
| `JpaDebateSessionStore` | `DebateSessionStore` implementation — activated by `casehub.drafthouse.persistence.enabled=true` via `@IfBuildProperty` |
| `NoOpDebateSessionStore` | `@DefaultBean` in-memory fallback — no persistence |
| `DebateSessionEntity` | JPA entity for debate session serialization |

#### WebSocket and Event System

| Class | Role |
|-------|------|
| `DebateWebSocket` | `@WebSocket(path = "/api/ws")` — handles subscribe/unsubscribe/listen/unlisten via pages-push protocol. Manages debate session subscriptions, brainstorm subscriptions, file watches. Sends catch-up events on connect. |
| `WebSocketEventBus` | Pub/sub — routes events to subscribed WebSocket connections. Methods: `broadcast`, `pushMetadata`, `pushFileChanged`, `watchSession`/`unwatchSession`, `watchBrainstorm`/`unwatchBrainstorm`, `watchFile`/`unwatchFile`, `hasFileWatchers` |
| `TerminalEndpoint` | `@WebSocket(path = "/api/terminal")` — PTY-over-WebSocket via pty4j. Accepts `cols`/`rows` query params. |

#### Human-in-the-Loop

| Class | Role |
|-------|------|
| `HumanActionResource` | 5 REST endpoints for browser HIL actions. Posts entries with `HUMAN` agent type to debate channel. |
| `DecisionFileWriter` | Writes human decisions to `decisions/human-round-N.md` in workspace directory. Thread-safe via per-file locks. |

#### Other

| Class | Role |
|-------|------|
| `DocumentSetJson` | JSON serialization utility for document sets and comparison pairs |

### server/claude-agent/ — `casehub-drafthouse-claude-agent`

Optional module. Single class: `ClaudeAgentSdkDebateAgentProvider`. Implements `DebateAgentProvider` via Claude Agent SDK. Depends on `casehub-drafthouse-api` and `casehub-platform-agent-api`. Pending `casehubio/platform#55`.

### Frontend (`server/runtime/src/main/webui/`)

TypeScript webui built with Quinoa. casehub-pages workbench with Lit (LitElement) panels.

**Entry point:** `src/index.ts` — workbench layout, WebSocket connection, topbar wiring, session discovery, keyboard shortcuts, cross-panel event routing, Electron IPC bridge.

**Panels (from `@casehubio/agency-ui-document-workbench`):**

| Panel | Element | Implementation |
|-------|---------|---------------|
| Diff viewer | `<document-diff>` | LitElement, Shadow DOM. LCS line diff, word-level highlights, canvas minimap, scroll sync, split/unified toggle. Fetches via `/api/file`. Reloads on `file-changed` events. |
| Debate feed | `<debate-feed>` | LitElement, Shadow DOM. Conversation entries grouped by round. Subscribes to `debate-entries` events. |
| Review tracker | `<review-tracker>` | LitElement, Shadow DOM. Point status checklist, progress bar, filter, sort. HIL action buttons (comment, override, prioritise, batch). Emits `point-selected`/`point-deselected`. |
| Context gauge | `<context-gauge>` | LitElement, Shadow DOM. Topbar gauge with normal/warn/error states. Subscribes to `context-usage` events. |
| Doc picker | `<doc-picker>` | LitElement, Shadow DOM. Document badge dropdown for A/B slot assignment. Posts comparison changes to REST API. |
| Timeline | `<document-timeline>` | LitElement, Shadow DOM. Version timeline strip. Filters `ROUND_SNAPSHOT` from debate entries. Emits `timeline-comparison-changed`. |
| Workspace status | `<workspace-status>` | LitElement, Shadow DOM. Live workspace watching progress. Subscribes to `workspace-progress` events. |
| Brainstorm options | `<brainstorm-options>` | LitElement, Shadow DOM. Interactive option cards with status transitions. Subscribes to `brainstorm-options` events. PATCH to REST API. |
| Brainstorm picker | `<brainstorm-picker>` | LitElement, Shadow DOM. Session switcher dropdown. Subscribes to `brainstorm-sessions` events. |
| Terminal | `<pages-component-terminal>` | From `@casehubio/pages-component-terminal`. xterm.js terminal connected to `/api/terminal` WebSocket. Brainstorm mode only. |

**Panel registration:** `registerPanel("diff-viewer", "document-diff")` maps layout IDs to custom element names.

**Frontend dependencies (via Maven SNAPSHOT WebJar):**
- `@casehubio/pages-runtime` — workbench rendering (`loadSite`)
- `@casehubio/pages-ui` — layout primitives (`rows`, `split`, `hostPanel`, `withId`, `html`)
- `@casehubio/pages-component` — `onPagesEvent`, `configure()` lifecycle
- `@casehubio/pages-data` — `createWebSocketSource` for WebSocket dataset subscriptions
- `@casehubio/pages-component-terminal` — xterm.js terminal component
- `lit` ^3.3.3 — web component framework
- `marked` ^15.0.0 — markdown rendering

**Cross-panel event routing (in `index.ts`):**
- `point-selected` → `document-diff.scrollToLocation()` + `highlightSection()`
- `point-deselected` → `document-diff.clearHighlight()`
- `selection-changed` → POST to `/api/debate/{id}/selection`
- `comparison-changed` → diff panel reloads files + starts file watches
- `brainstorm-user-action` → terminal input injection

---

## Key SPIs and Extension Points

| SPI | Package | Methods | Purpose |
|-----|---------|---------|---------|
| `DebateSessionStore` | `io.casehub.drafthouse` | `save`, `load`, `remove`, `loadAll` | Pluggable debate session persistence. `JpaDebateSessionStore` (build-property gated) and `NoOpDebateSessionStore` (`@DefaultBean` fallback). |
| `DebateAgentProvider` | `io.casehub.drafthouse.debate` | `String analyse(AgentTask task)` | LLM invocation for sub-agent handlers. `PlatformDebateAgentProvider` (`@DefaultBean` via platform `AgentProvider`) and `ClaudeAgentSdkDebateAgentProvider` (optional claude-agent module). |
| `ReviewSessionRegistry` | `io.casehub.drafthouse` | `find`, `put`, `remove`, `updateSelection` | Session storage abstraction. |
| `DebateSessionRegistry` | `io.casehub.drafthouse` | `find`, `put`, `remove`, `persist`, `activeSessions` | Debate session storage with persistence delegation. |
| `ChannelAgentHandler` | `io.casehub.agency.communication.dispatch` (from casehub-agency) | `handle(ChannelAgentRequest)` | Sub-agent task handler. 6 concrete handlers in `handler/` package. |
| `AgentRegistry` | `io.casehub.eidos.api` (from casehub-eidos) | `register`, `find`, `query` | Multi-LLM reviewer registry. `DraftHouseReviewerRegistry` implements this. |

---

## Reviewer Personas (seeded at startup)

| Agent ID | Name | Slot | Disposition | Focus |
|----------|------|------|-------------|-------|
| `drafthouse-structural-reviewer` | Structural Reviewer | `document-reviewer` | collaborative, strict | Gaps, contradictions, logical flow, internal consistency |
| `drafthouse-content-reviewer` | Content Reviewer | `document-reviewer` | competing, cautious | Accuracy, evidence, precision, substantiation |
| `drafthouse-readability-reviewer` | Readability Reviewer | `document-reviewer` | accommodating, directed | Clarity, jargon, ambiguous phrasing, prose quality |
| `drafthouse-completeness-reviewer` | Completeness Reviewer | `document-reviewer` | collaborative, strict | Coverage, edge cases, unaddressed alternatives |

All share tenancy `drafthouse`. Default reviewer: `drafthouse-structural-reviewer`.

---

## Depended On By

Nothing in the casehubio ecosystem — application tier only. DraftHouse is a leaf node.

---

## Current State

- Three-module Maven project (`server/api/` + `server/runtime/` + `server/claude-agent/`)
- Version: `0.2-SNAPSHOT`, Quarkus `3.34.3`, Java 21
- casehub-qhorus `0.2-SNAPSHOT`, casehub-agency `0.2-SNAPSHOT`, casehub-eidos `0.2-SNAPSHOT`, casehub-platform `0.2-SNAPSHOT`
- casehub-pages-push `0.2-SNAPSHOT` for typed WebSocket protocol
- Quinoa `2.8.3` for frontend build integration
- 30 MCP tools across 3 tool classes
- 5 HIL REST endpoints with `DecisionFileWriter` workspace persistence
- 6 sub-agent handler types via `DebateAgentProvider` SPI
- 4 reviewer personas via Eidos `AgentRegistry`
- JPA debate persistence (opt-in via build property)
- Workspace replay + live watching via `WorkspaceWatcher` (directory-watcher)
- casehub-pages workbench with 10 Lit panels
- No deployed production instances

---

## Recent Features (since April 2026)

- **HIL actions (#100)** — `HumanActionResource` with 5 REST endpoints (comment, raise, override, prioritise, batch). `HUMAN` agent type, `DecisionFileWriter` for workspace decision files. Browser review-tracker integration.
- **Brainstorming UI (#53)** — `<brainstorm-options>` and `<brainstorm-picker>` panels. `BrainstormResource` REST endpoint. Terminal injection for user actions.
- **Live workspace watching (#99)** — `WorkspaceWatcher` monitors in-progress design-review workspaces. `<workspace-status>` panel. directory-watcher for native FSEvents.
- **Structured output (#96)** — JSONL sidecar, evidence verification, verdict confirmations for design-review output.
- **Document timeline (#98)** — `<document-timeline>` version navigation across review rounds. `DocumentTimeline`, `DocumentSnapshot`, `SnapshotSource` domain types.
- **Replay adapter (#95)** — `WorkspaceParser`, `WorkspaceReplayAdapter` for loading completed design-review workspaces. `VERIFIED`/`DEFERRED` entry types.
- **Lit migration (#104)** — All panels migrated to LitElement with blocks-ui-compatible naming.
- **AgentProvider migration (#89)** — Moved from LangChain4j `ChatModel` to platform `AgentProvider` SPI. `PlatformDebateAgentProvider`.
- **Pages-push SDK (#92)** — Adopted `casehub-pages-push` typed protocol for WebSocket communication.
- **Quinoa workbench (#75)** — casehub-pages workbench via Quinoa. DebateEventBus migrated to pages-event CustomEvent.
- **WebSocket push (#87, #88)** — Replaced SSE polling with WebSocket. Reconnection with exponential backoff (max 60s).
- **Section highlighting (#90)** — Click-to-select review points with persistent diff highlight bar.
- **Multi-LLM reviewer registry (#62, #73)** — 4 reviewer personas via Eidos AgentRegistry.
- **Debate persistence (#64)** — `DebateSessionStore` SPI with JPA and in-memory backends.
- **Context meter (#52)** — `report_context` MCP tool + `<context-gauge>` web component.
- **Export summary (#65)** — `export_debate_summary` MCP tool writes debate projection to markdown.
- **Brainstorming MCP tools (#53)** — 7 MCP tools + `BrainstormService` + `TerminalEndpoint`.
- **Sub-agent dispatch** — `request_subagent` MCP tool with 6 handler types.
- **Round restart** — `restart_from_round` + `get_debate_summary_at_round` for branching debates.
- **Post memo** — `post_memo` MCP tool for per-round reasoning memos.
- **Maven SNAPSHOT for pages (#115)** — Frontend dependencies via Maven artifact instead of npm file: references.
- **Blocks extraction (#79-83)** — Context tracking, message meta, conversation protocol, channel agent dispatch extracted to casehub-agency.
- **Playwright E2E tests** — E2E coverage for diff panel, debate feed, review tracker, brainstorm options, cross-panel coordination.

---

## Design Documents

- **Architecture record:** `ARC42STORIES.MD` (Arc42Stories v0.1, CaseHub Application tier profile)
- **Research spec:** `docs/superpowers/specs/2026-05-26-document-review-tool-research.md`
- **Feature backlog:** `docs/FEATURES.md`
- [CLAUDE.md](https://raw.githubusercontent.com/casehubio/drafthouse/main/CLAUDE.md) — stack, module coordinates, architecture diagram, key design decisions

---

## Building and Testing

```bash
# Build all modules (skip tests)
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests

# Run all tests (must install api first)
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime

# Run single E2E test
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ScrollSyncE2ETest

# Run the app
java -jar server/runtime/target/drafthouse-server-runner.jar
# Open: http://localhost:9001/?a=/path/to/a.md&b=/path/to/b.md
```

E2E tests: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/`
Fixtures: `server/runtime/src/test/resources/fixtures/`

The `install` step is required so `runtime` can resolve `api` from the local Maven repository.

---

## Open Issues (future work)

| # | Title |
|---|-------|
| 110 | Live watcher: emit ROUND_SNAPSHOT entries and dispatch DEFERRED/evidence from tracker diffs |
| 109 | Investigate reviewer awareness of chunk boundaries |
| 108 | Explore context-aware chunking if basic chunking shows quality loss |
| 106 | Promote terminal component to @casehubio/agency-ui-terminal |
| 101 | Extract document panels as reusable @casehubio components |
| 93 | Document workbench — unify design-review and DraftHouse into reusable platform |
| 84 | Brainstorming UI on casehub-pages — foundation + feature |
| 72 | Review pipeline orchestration — sequential multi-perspective review sessions |
| 71 | Claude-to-Claude continuous conversation protocol |
| 61 | GraalVM native image build |
| 60 | Selection-scoped conversation channels — persistent per-selection threads |
