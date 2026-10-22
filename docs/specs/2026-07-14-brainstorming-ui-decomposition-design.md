# Brainstorming UI — Decomposition Design

**Issue:** #53 (child of epic #84)
**Date:** 2026-07-14

## Context

The brainstorming skill currently runs text-only in the CLI. #53 envisions a richer
UI — interactive option cards, exploration, challenge mode, convergence visualisation.
This spec decomposes that vision into six incremental slices, each independently
deliverable.

DraftHouse and Claudony both need an embedded CLI experience. xterm.js (`@xterm/xterm`)
must be introduced as a new dependency — it is not currently in the stack. The terminal
component starts in DraftHouse (consistent with the Lit migration spec's convention
that components live in DraftHouse before promotion to blocks-ui) and will be promoted
to `@casehubio/agency-ui-terminal` once blocks-ui component hosting conventions are
validated by existing panel promotions.

## Architecture

```
Brainstorming Skill (CLI)
  │
  ├── calls MCP tools: start_brainstorm, present_options, update_option,
  │   set_recommendation, mark_eliminated, mark_selected, end_brainstorm
  │
  ▼
Quarkus Server
  ├── BrainstormMcpTools        ← handles MCP tool calls
  ├── BrainstormSession         ← tracks options, state, lifecycle
  ├── WebSocketEventBus         ← pushes brainstorm events (session-scoped)
  ├── TerminalEndpoint          ← WebSocket-to-PTY bridge (separate from event bus)
  │
  ▼
Browser (pages-event bus + terminal WebSocket)
  ├── <terminal-panel>          ← xterm.js component (DraftHouse panel)
  ├── <brainstorm-panel>        ← option cards, actions
  │     └── dispatches terminal-inject event → terminal-panel
  └── <brainstorm-convergence>  ← decision timeline
```

The CLI session is the authority. The server stores brainstorm state and pushes
events. The panels are rendering + input surfaces. Panel clicks dispatch custom
events that the terminal panel handles by injecting text into the CLI session,
keeping the CLI as the single source of truth.

## Process Model

The terminal panel requires a server-side process to connect to. DraftHouse
manages this via a PTY-over-WebSocket architecture:

1. **Server:** A dedicated WebSocket endpoint (`TerminalEndpoint`) manages PTY
   subprocesses using pty4j (JetBrains' PTY library). Each WebSocket connection
   spawns a PTY subprocess running the configured command (e.g., Claude Code CLI
   with the brainstorming skill). The endpoint bridges stdin from WebSocket frames
   to the PTY, and stdout/stderr from the PTY to WebSocket frames.

2. **Client:** The `<terminal-panel>` connects to the terminal WebSocket endpoint
   (separate from the existing event bus WebSocket at `/api/ws`). xterm.js renders
   the PTY output and sends keystrokes over WebSocket.

3. **Separation:** The terminal WebSocket carries raw PTY I/O (bytes/text). The
   event bus WebSocket carries structured pages-events (JSON). These are independent
   connections — terminal I/O does not flow through the `WebSocketEventBus`.

4. **Lifecycle:** PTY process is created on WebSocket connect and killed on
   disconnect. No process pooling — one PTY per connection.

5. **Bootstrap sequence:** The terminal spawns a shell (the user's `$SHELL`
   or `/bin/bash`), not Claude Code directly. The user starts Claude Code
   within that shell. This makes the DraftHouse → PTY → Claude Code → MCP →
   DraftHouse dependency chain user-mediated rather than automatic. MCP
   server discovery relies on the user's existing Claude Code MCP configuration.
   Bootstrap ordering is naturally satisfied because DraftHouse (including its
   MCP server extension) is already running before any terminal WebSocket
   connection is established.

6. **Native dependency:** pty4j (`org.jetbrains.pty4j:pty4j`) is a JNA-based
   library bundling platform-specific native binaries for PTY management.
   This is DraftHouse's first native dependency — it changes the deployment
   constraint from "pure JVM" to "JVM + platform-native PTY." Target
   platforms: macOS (aarch64, x86_64) and Linux (aarch64, x86_64). pty4j is
   not in the CaseHub parent BOM — the dependency must be added to
   `server/runtime/pom.xml` with an explicit version.

## Data Model

### BrainstormSession (server-side)

- `sessionId: String`
- `options: List<BrainstormOption>`
- `state: ACTIVE | CONVERGED | ABANDONED`

**Lifecycle:**

- **Storage:** In-memory via `BrainstormSessionRegistry` (analogous to
  `DebateSessionRegistry`). Brainstorm sessions are ephemeral — tied to the
  terminal session lifetime. No JPA persistence needed.
- **Creation:** `start_brainstorm` MCP tool creates a session and registers it.
  Returns the session ID to the skill.
- **Association:** The session ID links MCP tool calls to the panel. Events are
  scoped to the session ID via the WebSocket topic registry.
- **Concurrent sessions:** One active session per brainstorm interaction. The
  panel renders whichever session's events it receives.
- **Transitions:**
  - `mark_selected` → state becomes `CONVERGED`
  - `end_brainstorm` without selection → state becomes `ABANDONED`
- **Cleanup:** Inactivity-based timeout. Each MCP tool call on a session resets
  an inactivity timer (configurable, default: 10 minutes). When the timer fires
  on an `ACTIVE` session, it transitions to `ABANDONED` and is removed from the
  registry. This avoids requiring cross-connection state — the terminal WebSocket,
  MCP/SSE connection, and event bus WebSocket are independent channels with no
  shared connection identity. The inactivity timer handles all cleanup cases:
  normal end (`end_brainstorm`), user disconnect (no more MCP calls → timeout),
  and abandoned sessions (same).

### BrainstormOption

- `id: String` (e.g. "A", "B", "C")
- `title: String`
- `description: String`
- `tradeoffs: String`
- `status: LIVE | RECOMMENDED | EXPLORED | ELIMINATED | SELECTED`

### MCP Tools

| Tool | Purpose | Emits event |
|------|---------|-------------|
| `start_brainstorm` | Create session | broadcast `brainstorm-session-created` + session-scoped `brainstorm-started` |
| `present_options` | Show 2-4 options with tradeoffs | `brainstorm-options` |
| `update_option` | Enrich an option after exploration | `brainstorm-option-updated` |
| `set_recommendation` | Mark one option as recommended | `brainstorm-options` (updated) |
| `mark_eliminated` | Dim an option | `brainstorm-options` (updated) |
| `mark_selected` | Final choice made | `brainstorm-converged` |
| `end_brainstorm` | Close session | `brainstorm-ended` |

All events push the full current option set so the panel can render from a single
event without tracking history. The convergence view accumulates successive
full-state snapshots — each snapshot carries a timestamp and the complete option
set, so transitions are derived by comparing consecutive snapshots. No separate
delta events are needed.

### WebSocket Event Delivery

Brainstorm events are delivered via the existing `WebSocketEventBus` using
session-scoped topics, following the established debate session pattern:

- **Session discovery:** `start_brainstorm` calls
  `eventBus.broadcast("brainstorm-session-created", { sessionId })` — a
  non-scoped broadcast to ALL WebSocket connections, regardless of topic
  subscriptions. This follows the existing debate pattern
  (`broadcast("session-created", ...)` in `DebateMcpTools.startDebate()`).
  The brainstorm-mode `index.ts` listens for this event and subscribes to
  the session-scoped topic:
  ```typescript
  if (topic === "brainstorm-session-created" && !currentBrainstormId) {
    currentBrainstormId = payload.sessionId;
    wsSource.subscribe("brainstorm:" + payload.sessionId, ...);
  }
  ```
  The catch-up mechanism then pushes current state to the new subscriber.
- **Topic pattern:** `"brainstorm:" + sessionId`
- **Registration:** `watchBrainstorm(conn, sessionId)` /
  `unwatchBrainstorm(conn, sessionId)` on `WebSocketEventBus` (analogous to
  `watchSession` / `unwatchSession`)
- **Push:** `pushBrainstormEvent(sessionId, topic, payload)` delivers events
  only to connections watching that session (analogous to `pushMetadata`)
- **Client subscription:** `index.ts` subscribes to the brainstorm topic via
  `wsSource.subscribe("brainstorm:" + sessionId, ...)` when entering
  brainstorming mode
- **WebSocket handler:** `DebateWebSocket` (the `/api/ws` handler) must be
  updated to handle `brainstorm:` prefix subscriptions in `handleSubscribe()`
  and `handleListen()`. Currently only `debate:` and `file:` prefixes are
  handled — any other prefix falls through to a bare `ack` without calling
  `eventBus.watchBrainstorm()`, causing silent event delivery failure. The
  handler should call `eventBus.watchBrainstorm(connection, sessionId)` on
  subscribe and `eventBus.unwatchBrainstorm(connection, sessionId)` on
  unsubscribe.
- **Catch-up on subscribe:** When the `brainstorm:` subscription handler
  registers a connection, it reads the current `BrainstormSession` from
  `BrainstormSessionRegistry`. If the session exists and has options, it
  pushes a `brainstorm-options` event with the current option set to the
  newly subscribed connection (same event topic as live updates — the
  panel does not distinguish catch-up from live). This follows the existing
  `DebateWebSocket.sendCatchUp()` pattern that pushes accumulated debate
  entries and context snapshots on new subscriptions.

## Panel Behaviour

### `<terminal-panel>`

- Wraps xterm.js (new dependency: `@xterm/xterm`, `@xterm/addon-fit`)
- Connects to the terminal WebSocket endpoint (`/api/terminal`)
- Exposes `configure({ command?, env? })` to specify the subprocess command
- Exposes `injectInput(text: string)` that writes to the terminal's stdin
  via the terminal WebSocket connection
- Listens for `terminal-inject` custom events on `document` to receive
  text injection requests from other panels
- Standard DraftHouse Lit panel — no app-specific logic beyond xterm.js hosting
- Registered via `registerPanel("terminal", "terminal-panel")`

### `<brainstorm-panel>`

- Subscribes to `brainstorm-options`, `brainstorm-option-updated`,
  `brainstorm-converged` via `onPagesEvent`
- Renders options as cards in a row/grid
- Card visual states:
  - `LIVE` — default card
  - `RECOMMENDED` — highlighted border/badge
  - `EXPLORED` — enriched content shown
  - `ELIMINATED` — dimmed/strikethrough
  - `SELECTED` — prominent, others collapse
- Action buttons (Slice 4): "Select", "Explore", "Challenge"
  - Each dispatches a `terminal-inject` custom event on `document` with
    `detail: { text: "select Option A\n" }` (natural language matching what
    the user would type)
  - The terminal panel listens for this event and calls `injectInput()`
  - Button clicks and user typing are equivalent by design — the CLI is
    the source of truth and parses natural language input
- Input injection is equivalent to user typing — if the LLM is mid-generation,
  the input is buffered by the CLI's line discipline (standard terminal
  behaviour)

### `<brainstorm-convergence>`

- Accumulates successive full-state event snapshots over session lifetime
- Derives transitions by comparing consecutive snapshots (which option
  changed status, when)
- Shows each option's status transitions as a horizontal lane
- Compact — sits below the brainstorm panel or as a collapsible section

### Layout

Brainstorming mode uses a separate layout, selected via URL parameter.
`index.ts` checks `mode=brainstorm` and builds the appropriate layout:

```typescript
// index.ts — mode-based layout selection
const mode = params.get("mode");

if (mode === "brainstorm") {
  const workbench = rows(
    html(`<div id="topbar">...</div>`),
    split("horizontal", [
      rows(hostPanel("terminal")),
      rows(
        hostPanel("brainstorm"),
        hostPanel("brainstorm-convergence"),
      ),
    ], { ratio: [50, 50] }),
  );
  await loadSite(document.getElementById("app")!, workbench);
} else {
  // existing diff/review layout (unchanged)
  ...
}
```

This avoids runtime layout switching — each mode has its own layout tree,
selected at page load. The brainstorming URL is `/?mode=brainstorm`, while
the existing diff/review URL is `/?a=path&b=path&debate=id`. Dock toggling
(`pages-dock-toggle`) works within either layout for individual panel
visibility.

## Skill Integration

The brainstorming skill adds MCP tool calls at existing decision points. The
skill continues to render markdown in the terminal — MCP calls are additive.
The MCP tools always execute and update server-side `BrainstormSession` state
regardless of whether a browser is connected. If no WebSocket connection
watches the `"brainstorm:" + sessionId` topic, events are simply not
delivered — the tools succeed silently, matching the existing `pushMetadata`
behaviour when no watchers are registered.

**At "Propose 2-3 approaches":**
- `start_brainstorm` → creates session
- `present_options` → sends all options with tradeoffs
- `set_recommendation` → marks the recommended one

**When user selects:** `mark_selected`

**When user explores/challenges:**
- `update_option` → enriches with exploration results
- `mark_eliminated` → if challenge reveals fatal flaw

**At design approval:** `end_brainstorm`

## Slice Decomposition

### Slice 1: Terminal component

xterm.js terminal panel in DraftHouse. Connects to a server-side
PTY-over-WebSocket endpoint. Hosts a CLI session. Includes both the
frontend panel (`<terminal-panel>`) and the server-side `TerminalEndpoint`.

Promotion to `@casehubio/agency-ui-terminal` for cross-app reuse (Claudony)
is future work, once blocks-ui component conventions are validated.

- Scale: L · Complexity: High
- Dependencies: none
- Repo: casehub-drafthouse
- New dependencies: `@xterm/xterm`, `@xterm/addon-fit`,
  `org.jetbrains.pty4j:pty4j` (not in CaseHub parent BOM — explicit version)

### Slice 2: Brainstorming MCP tools + server events

New MCP tools (`start_brainstorm`, `present_options`, `update_option`,
`set_recommendation`, `mark_eliminated`, `mark_selected`, `end_brainstorm`).
Server stores `BrainstormSession` state in `BrainstormSessionRegistry` and
pushes session-scoped pages-events over WebSocket via `WebSocketEventBus`.

- Scale: M · Complexity: Med
- Dependencies: none
- Repo: casehub-drafthouse

### Slice 3: Brainstorm panel — read-only

Lit panel subscribes to brainstorming pages-events. Renders option cards
side-by-side with visual status (recommended, explored, eliminated, selected).
Read-only — user interacts via terminal.

- Scale: S · Complexity: Low
- Dependencies: Slice 2 (event topics)
- Includes: `mode=brainstorm` URL parameter handling and brainstorming
  layout tree in `index.ts` (from §Layout)

### Slice 4: Interactive panel — terminal injection

Action buttons on cards: "Select", "Explore", "Challenge". Clicks dispatch
`terminal-inject` custom events on `document`, which the terminal panel
handles by calling `injectInput()`.

- Scale: S · Complexity: Med
- Dependencies: Slice 1 (terminal panel), Slice 3

### Slice 5: Brainstorming skill integration

Update the brainstorming skill to call the new MCP tools at structured moments.
Additive — terminal output unchanged.

- Scale: S · Complexity: Med
- Dependencies: Slice 2

### Slice 6: Convergence view

Visual timeline of all options: status transitions derived from accumulated
full-state snapshots, rendered as horizontal lanes. Shows which options were
explored, eliminated, or selected over time.

- Scale: S · Complexity: Med
- Dependencies: Slice 2 (event topics), Slice 3 (panel patterns)

### Dependency Graph

```
Slice 1 (terminal) ─────────────┐
                                ▼
Slice 2 (MCP + events) ──▶ Slice 3 (read-only) ──▶ Slice 4 (interactive)
        │
        ├──▶ Slice 5 (skill)
        └──▶ Slice 6 (convergence)
```

Slices 1 and 2 are independent and can be built in parallel.
Slices 5 and 6 are independent of each other once Slice 2 is complete.
Slice 4 depends on both Slice 1 (terminal to inject into) and Slice 3
(cards to add buttons to).

## Testing Strategy

Each slice specifies its testing requirements:

| Slice | Tests |
|-------|-------|
| Slice 1 | Unit tests for terminal WebSocket framing; `@QuarkusTest` integration test for PTY lifecycle (spawn, write, read, kill) |
| Slice 2 | Unit tests for `BrainstormMcpTools` following `DebateMcpToolsTest` pattern; `BrainstormSessionRegistry` tests following `DebateSessionRegistryTest` |
| Slice 3 | Panel rendering tests: renders cards from mock events, visual state transitions |
| Slice 4 | E2E (Playwright): click action button → verify `terminal-inject` event dispatched; integration with terminal panel |
| Slice 5 | End-to-end test: brainstorming skill → MCP tool calls → events received by panel |
| Slice 6 | Unit tests: accumulate snapshots → verify derived transitions; rendering tests |

## ARC42STORIES Updates

This spec introduces capabilities that require ARC42STORIES.MD updates:

- **§1 Introduction and Goals:** Add brainstorming alongside document review
- **§3 Context and Scope:** Add brainstorming MCP tools to system context diagram
- **§4 Solution Strategy:** New chapter — Brainstorming workflow: CLI authority,
  MCP event bridge, panel rendering
- **§5 Building Block View:** New components: `TerminalEndpoint`,
  `BrainstormMcpTools`, `BrainstormSessionRegistry`, brainstorming panels
- **§7 Deployment View:** Note pty4j as first native dependency; add
  target platform matrix (macOS aarch64/x86_64, Linux aarch64/x86_64)
- **New Journey: Brainstorming** — alongside Comparison and Review
