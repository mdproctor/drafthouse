# casehub-drafthouse — Consumer Guide

> MCP-driven document review and adversarial debate tool. Any MCP client (Claude Code, Claudony, or custom) can open documents, compare versions, run multi-agent debates with structured conversation protocols, dispatch sub-agent analysis, manage brainstorming sessions, and conduct human-in-the-loop reviews — all grounded in specific document regions.

**GitHub:** [casehubio/drafthouse](https://github.com/casehubio/drafthouse)
**Tier:** CaseHub Application
**Port:** 9001 (default)

---

## Purpose

DraftHouse is an MCP-driven document review and adversarial debate platform. It provides three operational modes:

1. **Document Review** — Open two document versions (before/after), assign a reviewer agent, and conduct grounded Q&A via Qhorus channels.
2. **Adversarial Debate** — Multi-agent structured debate on a spec or document with round-based conversation, sub-agent dispatch, context tracking, human-in-the-loop actions, and workspace replay.
3. **Brainstorming** — Interactive option exploration with MCP tools for presenting, exploring, eliminating, recommending, and selecting options.

The browser UI is a casehub-pages workbench with Lit panels: two-panel markdown diff viewer, debate conversation feed, review point tracker, context usage gauge, document timeline, and brainstorming option cards.

---

## Module Structure

| Module | Artifact ID | Purpose |
|--------|-------------|---------|
| `server/api/` | `casehub-drafthouse-api` | Pure Java domain model — `DebateSession`, `ReviewSession`, `BrainstormSession`, `DebateSessionStore` SPI, `DebateAgentProvider` SPI, entry types, agent types, document snapshots/timelines |
| `server/runtime/` | `casehub-drafthouse` | Quarkus 3.34.3 app — MCP endpoints, REST APIs, WebSocket push, Qhorus channel integration, reviewer registry, sub-agent dispatch, workspace replay, brainstorming services, terminal endpoint, browser UI |
| `server/claude-agent/` | `casehub-drafthouse-claude-agent` | Optional module — `ClaudeAgentSdkDebateAgentProvider` (displaces `PlatformDebateAgentProvider` when present) |

All modules use version `0.2-SNAPSHOT`. Java 21. Quarkus 3.34.3.

---

## Key Abstractions

| Concept | Class | Role |
|---------|-------|------|
| Review session | `ReviewSession` | Document review context: two document sides (A/B), reviewer agent, selection scope, Qhorus channel |
| Debate session | `DebateSession` | Adversarial debate context: document working set, comparison pair, participants, context tracker, timeline, workspace path |
| Brainstorm session | `BrainstormSession` | Option exploration context: options with states (EXPLORED, RECOMMENDED, ELIMINATED, SELECTED), lifecycle (ACTIVE / CONVERGED / ABANDONED) |
| Document side | `DocumentSide` | Enum: `A` or `B` — one version of a document within a review session |
| Document entry | `DocumentEntry` | A document in a debate working set: path + label |
| Comparison pair | `ComparisonPair` | Which two documents the diff viewer compares: `pathA` + `pathB` |
| Selection scope | `SelectionScope` | Active text selection: side, start/end lines, selected text |
| Resolved reviewer | `ResolvedReviewer` | Resolved reviewer identity: `agentId`, `name`, `instructions` — produced by `ReviewerResolver` via Eidos `AgentRegistry` |
| Debate session snapshot | `DebateSessionSnapshot` | Serializable debate state for persistence |
| Document snapshot | `DocumentSnapshot` | Content snapshot at a point in time (sealed `SnapshotSource`) |
| Document timeline | `DocumentTimeline` | Ordered sequence of document snapshots across review rounds |
| Entry type | `EntryType` | 17 conversation entry types (see below) |
| Agent type | `AgentType` | 6 participant roles (see below) |

### Entry Types (17 total)

`RAISE`, `AGREE`, `COUNTER`, `DISPUTE`, `QUALIFY`, `FLAG_HUMAN`, `DECLINED`, `VERIFIED`, `DEFERRED`, `MEMO`, `SUB_TASK_REQUEST`, `SUB_TASK_FINDING`, `SUB_TASK_ERROR`, `RESTART_CONTEXT`, `ROUND_SNAPSHOT`, `COMMENT`, `HUMAN_OVERRIDE`, `REPRIORITISE`

### Agent Types (6 total)

`REV` (reviewer), `IMP` (implementor), `SUPERVISOR`, `MODERATOR`, `SELECTOR`, `HUMAN`

---

## MCP Tool Surface

DraftHouse exposes MCP tools in four groups. All tools return JSON strings. Errors use `"error: ..."` format.

### Review Tools (`DraftHouseMcpTools`)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `start_review` | `docAPath`, `docBPath`, `agentId` | Start a document review session with A/B document paths and optional reviewer agent ID. Returns `sessionId`, channel name, reviewer info. |
| `update_selection` | `sessionId`, `side`, `selectedText` | Update selected text in review session. Pass null side+text to clear. |
| `query_review` | `sessionId`, `question` | Send a question to the reviewer via Qhorus channel. Async response. |
| `end_review` | `sessionId`, `deleteChannel` | End review session. Optionally delete the Qhorus channel. |
| `list_reviewers` | (none) | List available reviewer agents with IDs, names, dispositions, capabilities, briefing summaries. |
| `get_reviewer_instructions` | `agentId`, `resourcePath` | Render full reviewer instructions, optionally contextualised to a resource. |

### Debate Tools (`DebateMcpTools`)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `start_debate` | `specPath`, `agentId`, `autonomous` | Start an adversarial debate session on a spec file. Registers REV and IMP agents. Set `autonomous=true` for server-driven debate where agents respond automatically via ConversationOrchestrator. Returns `debateSessionId`, channel, reviewer info. |
| `raise_point` | `debateSessionId`, `agentRole`, `round`, `content`, `priority`, `scope`, `location` | Raise a new debate point. Priority: P1/P2/P3. Scope: ISOLATED/SYSTEMIC. Returns `pointId`. |
| `respond_to` | `debateSessionId`, `agentRole`, `round`, `pointId`, `entryType`, `content` | Respond to a point. Entry type: agree, dispute, qualify, counter, declined. |
| `flag_human` | `debateSessionId`, `agentRole`, `round`, `pointId`, `reason` | Escalate a point for human review. |
| `post_memo` | `debateSessionId`, `agentRole`, `round`, `content` | Write a per-round reasoning memo to the debate channel. |
| `request_subagent` | `debateSessionId`, `agentRole`, `taskType`, `pointId`, `round`, `customInput` | Dispatch a sub-agent for focused analysis. Task types: VERIFY, ARBITRATE, DEEP_ANALYSIS, CONSISTENCY_CHECK, NEUTRAL_SUMMARY, CUSTOM. |
| `get_debate_summary` | `debateSessionId` | Get current debate summary as JSON with markdown summary and reviewer info. Includes active selection and working set. |
| `get_debate_summary_at_round` | `debateSessionId`, `round` | Get debate summary as it stood at end of round N. |
| `restart_from_round` | `debateSessionId`, `round` | Create a new debate session branching from round N. Original session stays live. |
| `end_debate` | `debateSessionId`, `deleteChannel` | End debate session. Stops any active workspace watcher. |
| `report_context` | `debateSessionId`, `usagePercent` | Report context window usage (0-100%). Returns warning when threshold exceeded. |

### Document Tools (in `DebateMcpTools`)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `add_document` | `debateSessionId`, `path`, `label` | Add a document to the debate working set. |
| `remove_document` | `debateSessionId`, `path` | Remove a document (cannot remove the primary/first document). |
| `list_documents` | `debateSessionId` | List all documents and current comparison pair. |
| `set_comparison` | `debateSessionId`, `pathA`, `pathB` | Set which two documents the diff viewer compares. |
| `export_debate_summary` | `debateSessionId`, `outputPath` | Export debate summary to a markdown file on disk. |

### Workspace Tools (in `DebateMcpTools`)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `load_workspace` | `workspacePath` | Load a completed design-review workspace into DraftHouse. Replays `responses/` files into debate channel. If review is in-progress, starts a `WorkspaceWatcher` for live updates. Idempotent. |

### Brainstorming Tools (`BrainstormMcpTools`)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `start_brainstorm` | (none) | Start a brainstorming session. Returns `sessionId`. |
| `present_options` | `sessionId`, `optionsJson` | Present 2-4 options as JSON array with `id`, `title`, `description`, `tradeoffs`. |
| `update_option` | `sessionId`, `optionId`, `description`, `tradeoffs` | Enrich an option with exploration results. Sets status to EXPLORED. |
| `set_recommendation` | `sessionId`, `optionId` | Mark one option as recommended. |
| `mark_eliminated` | `sessionId`, `optionId` | Mark an option as eliminated. |
| `mark_selected` | `sessionId`, `optionId` | Mark final selection. Converges the session. |
| `end_brainstorm` | `sessionId` | End the brainstorming session. |

### Pipeline Tools (`PipelineMcpTools`)

| Tool | Parameters | Description |
|------|-----------|-------------|
| `start_pipeline` | `debateSessionId`, `dimensions` (JSON array), `ordered`, `specPath` | Create a review pipeline linking to a debate session. Starts a PipelineWatcher per dimension. |
| `update_pipeline` | `pipelineId`, `action`, `dimension` (optional) | Update pipeline state: `checkpoint_reached`, `dimension_refused`, `dimension_accepted`, `crosscutting_started`, `pipeline_complete`. |
| `load_decisions` | `pipelineId`, `decisionsPath` | Load brainstorming decisions from a decisions.md file into the pipeline. |

**Total: 31 MCP tools.**

---

## REST API Surface

### Debate REST Endpoints (`DebateEventResource`)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/debate/{id}/selection` | Store selection scope on debate session |
| `DELETE` | `/api/debate/{id}/selection` | Clear selection scope |
| `GET` | `/api/debate/{id}/documents` | List working set documents + current comparison |
| `POST` | `/api/debate/{id}/comparison` | Browser-initiated comparison change |
| `GET` | `/api/debate/{id}/snapshot/{index}` | Document content at timeline snapshot index |
| `GET` | `/api/debate/sessions` | Active debate session list |

### Human-in-the-Loop REST Endpoints (`HumanActionResource`)

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/api/debate/{id}/human/comment` | `{pointId, content}` | Add human comment on a debate point |
| `POST` | `/api/debate/{id}/human/raise` | `{content, priority, location, side, startLine, endLine, selectedText}` | Human raises a new point |
| `POST` | `/api/debate/{id}/human/override` | `{pointId, reason}` | Human overrides a point (marks resolved) |
| `POST` | `/api/debate/{id}/human/prioritise` | `{pointId, newPriority}` | Human changes point priority (P1/P2/P3) |
| `POST` | `/api/debate/{id}/human/batch` | `{pointIds, verdict}` | Batch accept (VERIFIED) or defer (DEFERRED) multiple points |

Human actions also write to `decisions/human-round-N.md` files in the workspace via `DecisionFileWriter`.

### Brainstorming REST Endpoints (`BrainstormResource`)

| Method | Path | Description |
|--------|------|-------------|
| `PATCH` | `/api/brainstorm/{id}/options/{optionId}` | Browser-initiated status change (ELIMINATED, RECOMMENDED, SELECTED) |
| `GET` | `/api/brainstorm/sessions` | Active brainstorm session list |

### Infrastructure Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/ping` | Health check |
| `GET` | `/api/file?path=` | Read any local file (no path restriction — local-only tool) |

---

## WebSocket Protocol

**Endpoint:** `WS /api/ws`

Uses the `casehub-pages-push` wire protocol (`PushMessage`). Supports subscribe/unsubscribe/listen/unlisten operations.

**Dataset subscriptions:**
- `debate:{sessionId}` — debate events, context usage, document changes, comparison changes
- `brainstorm:{sessionId}` — brainstorm options, convergence, session lifecycle
- `file:{path}` — file change notifications (requires path to be in an active session's document set)

**Event topics pushed from server:**
- `sessions` — active debate session list (on connect)
- `brainstorm-sessions` — active brainstorm session list (on connect)
- `session-created`, `session-ended` — debate lifecycle
- `debate-entries` — debate conversation entries (catch-up on subscribe)
- `context-usage` — context window metrics
- `documents-changed` — working set changes
- `comparison-changed` — diff viewer pair changes
- `brainstorm-options` — option state (catch-up on subscribe)
- `brainstorm-converged`, `brainstorm-ended` — brainstorm lifecycle
- `brainstorm-user-action` — browser-initiated option actions
- `brainstorm-session-created` — new brainstorm session
- `file-changed` — file modification notification
- `workspace-progress` — live workspace watching progress
- `autonomous-completed` — autonomous debate loop finished (reason, dispatchCount, durationMs)
- `autonomous-failed` — autonomous debate loop failed (error)
- `reconnected` — sent on WebSocket connect

**Terminal WebSocket:** `WS /api/terminal?cols={cols}&rows={rows}` — PTY-over-WebSocket via pty4j (brainstorming mode only).

---

## Reviewer Agents

Four reviewer personas are seeded at startup via `ReviewerDescriptorSeeder` into the Eidos `AgentRegistry`:

| Agent ID | Name | Disposition | Focus |
|----------|------|-------------|-------|
| `drafthouse-structural-reviewer` | Structural Reviewer | collaborative, strict | Gaps, contradictions, missing sections, logical flow, internal consistency |
| `drafthouse-content-reviewer` | Content Reviewer | competing, cautious | Accuracy, evidence, overgeneralisations, missing details |
| `drafthouse-readability-reviewer` | Readability Reviewer | accommodating, directed | Clarity, jargon, ambiguous phrasing, prose quality |
| `drafthouse-completeness-reviewer` | Completeness Reviewer | collaborative, strict | Coverage, edge cases, unaddressed alternatives, thoroughness |

All share slot `document-reviewer` and tenancy `drafthouse`. Default reviewer is `drafthouse-structural-reviewer`.

---

## Sub-Agent System

The `request_subagent` MCP tool dispatches focused analysis sub-agents via the `DebateAgentProvider` SPI. Six handler types exist:

| Task Type | Handler | Purpose |
|-----------|---------|---------|
| `VERIFY` | `VerifyHandler` | Verify a specific claim or finding |
| `ARBITRATE` | `ArbitrateHandler` | Arbitrate between competing positions |
| `DEEP_ANALYSIS` | `DeepAnalysisHandler` | Deep-dive analysis of a point |
| `CONSISTENCY_CHECK` | `ConsistencyCheckHandler` | Check proposed resolution for consistency |
| `NEUTRAL_SUMMARY` | `NeutralSummaryHandler` | Generate neutral summary of the debate |
| `CUSTOM` | `CustomHandler` | Custom analysis with freeform context |

Sub-agent findings appear in `get_debate_summary` with a pending indicator while running.

---

## Browser UI (Workbench)

The UI is a casehub-pages workbench with Lit (LitElement) panels. Two layouts:

### Review/Debate Layout (default)

Topbar with controls, split main content (diff viewer + debate/review panels), status bar with context gauge.

| Panel | Element | Role |
|-------|---------|------|
| Diff viewer | `<document-diff>` | Two-panel markdown diff with LCS line diff, word-level highlights, canvas minimap, scroll sync, split/unified toggle |
| Debate feed | `<debate-feed>` | Debate event conversation feed grouped by round |
| Review tracker | `<review-tracker>` | Review point status checklist with HIL action buttons (comment, override, prioritise, batch accept/defer) |
| Context gauge | `<context-gauge>` | Status bar context usage gauge (normal/warn/error states) |
| Doc picker | `<doc-picker>` | Topbar document badge dropdown for A/B slot assignment |
| Timeline | `<document-timeline>` | Document version timeline strip above diff panel |
| Workspace status | `<workspace-status>` | Topbar live workspace watching progress indicator |
| Review pipeline | `<review-pipeline>` | Pipeline progress dashboard — decisions, phase header, dimension cards (hidden by default, auto-shown on pipeline events) |

### Brainstorming Layout (`?mode=brainstorm`)

Terminal + brainstorm options side by side.

| Panel | Element | Role |
|-------|---------|------|
| Terminal | `<pages-component-terminal>` | xterm.js terminal via pty4j WebSocket |
| Brainstorm options | `<brainstorm-options>` | Interactive option cards with status, actions, convergence summary |
| Brainstorm picker | `<brainstorm-picker>` | Topbar session switcher dropdown |

### Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `n` | Next diff |
| `p` | Previous diff |
| `u` | Toggle unified/split view |
| `Ctrl+S` | Toggle scroll sync |
| `?` | Toggle shortcuts overlay |
| `Esc` | Close overlay |

### URL Parameters

| Param | Effect |
|-------|--------|
| `?a=/path` | Initial file A path |
| `?b=/path` | Initial file B path |
| `?debate={sessionId}` | Auto-connect to debate session |
| `?mode=brainstorm` | Use brainstorming layout |

---

## Channel Usage Pattern

DraftHouse uses a single APPEND Qhorus channel per session. Debate entries use `ChannelMessageMeta` encoding with structured metadata (entry type, role, round, priority, scope, location). The `ConversationProtocol` from `casehub-agency` provides the metadata key constants.

Review sessions use `QUERY`/`RESPONSE` message types. Debate sessions use the full `MessageType` taxonomy: `QUERY` (raise), `RESPONSE` (qualify/counter), `DONE` (agree/verified), `DECLINE` (dispute/declined/deferred), `HANDOFF` (flag_human), `STATUS` (memo/restart_context).

---

## Configuration

All configuration under `casehub.drafthouse.*`:

| Key | Default | Description |
|-----|---------|-------------|
| `casehub.drafthouse.reviewer.max-doc-chars` | `100000` | Maximum document size in characters |
| `casehub.drafthouse.storage.root` | `~/.drafthouse/reviews` | Storage root for review session files |
| `casehub.drafthouse.context.window-size-chars` | `800000` | Context window size for tracking |
| `casehub.drafthouse.context.threshold-percent` | `80` | Warning threshold percentage |
| `casehub.drafthouse.persistence.enabled` | `false` | Enable JPA debate session persistence |
| `casehub.drafthouse.debate.catch-up-limit` | `500` | Max messages sent on WebSocket catch-up |

---

## Dependencies

| Repo | Artifact | Nature |
|------|----------|--------|
| `casehub-qhorus` | `casehub-qhorus` (runtime) | Channel mesh runtime — `ChannelService`, `ChannelGateway`, `MessageService`, `InstanceService`, `ProjectionService` |
| `casehub-qhorus` | `casehub-qhorus-api` | Channel mesh SPIs — `Channel`, `ChannelCreateRequest`, `ChannelSemantic`, `MessageDispatch`, `MessageType` |
| `casehub-eidos` | `casehub-eidos-api` | `AgentRegistry`, `AgentDescriptor`, `AgentDisposition`, `AgentCapability`, `Resource` — Eidos identity model for reviewer registry |
| `casehub-agency` | `casehub-agency` | Context tracking (`ContextSnapshot`), message meta encoding (`ChannelMessageMeta`), conversation protocol (`ConversationProtocol`, `ConversationState`, `ConversationPoint`), channel agent dispatch (`ChannelAgentHandler`, `ChannelAgentDispatcher`, `AgentTask`), bounded projection |
| `casehub-platform` | `casehub-platform` (runtime) | `MockCurrentPrincipal` `@DefaultBean` required by Qhorus JPA stores |
| `casehub-platform` | `casehub-platform-agent-api` | `AgentProvider` SPI — provider-agnostic LLM invocation for sub-agent dispatch |
| `casehub-pages` | `casehub-pages-push` | Typed push protocol SDK — `PushMessage`, `PushRequest` wire format |
| `casehub-pages` | `casehub-pages-npm` (Maven artifact) | Frontend packages via Maven SNAPSHOT (WebJar pattern) — pages-runtime, pages-ui, pages-component, pages-data |
| Quarkiverse | `quarkus-quinoa 2.8.3` | npm build integration — bundles TypeScript webui at compile time |
| Quarkiverse | `quarkus-websockets-next` | WebSocket support for debate event push and terminal |
| JGit | `org.eclipse.jgit 6.9.0` | Local git history access for review session versioning |
| pty4j | `pty4j 0.13.11` | PTY management for terminal WebSocket endpoint |
| directory-watcher | `io.methvin:directory-watcher 0.18.0` | Native macOS FSEvents for workspace watching |

---

## What This Repo Does NOT Do

- **No document storage** — review state is in-memory or JPA when enabled; no document database
- **No consensus or voting** — each reviewer agent is independent; debates use structured conversation, not consensus protocols
- **No git, PR, or source control** — `casehub-devtown` owns that domain
- **No audit trail, case orchestration, or agent identity** — those are foundation concerns (ledger, work, eidos)
- **No deployed production instances** — local-only tool
- **No NormativeChannelLayout** — uses single APPEND channels, not the 3-channel work/observe/oversight layout (that is Claudony's concern)
