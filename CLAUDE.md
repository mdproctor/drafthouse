# CLAUDE.md — DraftHouse

**Name:** casehub-drafthouse

## Project Type

**Type:** CaseHub application (Quarkus)

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` (workspace staging) |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` (workspace staging) |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (staging; promoted to project `docs/specs/` at epic close)
- `plans/` — implementation plans (ephemeral; stay in workspace only)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records (staging; promoted to project `docs/adr/` at epic close)
- `blog/` — project diary entries with INDEX.md
- `design/` — epic journal (created by `epic` at branch start)

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

## Overview

DraftHouse is an MCP-driven document review tool. Any LLM (Claude Code, Claudony, or
any MCP client) can open a document, show before/after versions, create reviewer LLM
agents, and have grounded conversations about specific parts of the document.

Currently provides side-by-side markdown comparison with LCS line diff, word-level
highlights, colour-coded minimap, and inline change annotations. The critique/review
features are Phase 2 (see research spec).

**Canonical location:** `~/claude/casehub/drafthouse/`
**GitHub repo:** `casehubio/drafthouse`

## Agentic Harness Goals

**Goal:** Production-grade MCP-driven document review tool. Any LLM client opens documents, loads before/after versions, initiates reviewer agents, and conducts selection-scoped conversations grounded in specific document regions. Phase 2 wires in Qhorus channels, LangChain4j reviewer agents, and JGit versioning to add structured critique with full CaseHub accountability.

**Architecture record:** `ARC42STORIES.MD` is the primary architecture record (Arc42Stories v0.1, CaseHub Application tier profile). `LAYER-LOG.md` is the source-of-truth draft that fed the migration; migration verified complete 2026-06-03 — retained for historical reference only. New layer entries go in `ARC42STORIES.MD §9.4` directly. See `../parent/docs/arc42stories-spec.md` and `../parent/docs/arc42stories-casehub-profile.md`.

## Platform Context

This repo is a CaseHub application-tier project. Before implementing any feature
that touches shared concerns (channels, audit, orchestration), check the platform
architecture:

```
../parent/docs/PLATFORM.md
../parent/docs/APPLICATIONS.md
```

## Reference Documents (casehub-parent)

| Document | What it covers |
|----------|---------------|
| `../garden/docs/protocols/casehub/HARNESS-INDEX.md` | CaseHub app protocols |
| `../garden/docs/protocols/universal/INDEX.md` | Universal Java/Quarkus protocols |

## Building the Server

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
```

Produces `server/runtime/target/drafthouse-server-runner.jar`.

## Running the App

```bash
java -jar server/runtime/target/drafthouse-server-runner.jar
```

Then open `http://localhost:9001/?a=/path/to/file-a.md&b=/path/to/file-b.md` in a browser.

- Quinoa serves the bundled webui (built from `server/runtime/src/main/webui/`)
- Query params `?a=` and `?b=` — initial file paths to load
- Query param `?debate=` — debate session ID to auto-connect

## Testing

**All tests (Java server + Playwright E2E):**
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Run a single E2E class:
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ScrollSyncE2ETest
```

E2E tests live in `server/runtime/src/test/java/io/casehub/drafthouse/e2e/`. Fixture files are in `server/runtime/src/test/resources/fixtures/`.

Note: The `install` step is needed so `runtime` can resolve `api` from the local Maven repository. On a clean checkout, always run the full reactor `install -DskipTests` before selective test runs.

## Key Directories

| Path | Contents |
|---|---|
| `server/` | Multi-module Maven parent (api/ + runtime/ + claude-agent/) |
| `server/runtime/src/main/webui/` | TypeScript webui built with Quinoa — panels, workbench, WebSocket connection |
| `server/runtime/src/main/webui/src/index.ts` | Workbench entry point — casehub-pages layout, topbar, Electron IPC, WebSocket connection |
| `server/runtime/src/main/webui/src/panels/` | Web Component panels (Shadow DOM, adoptedStyleSheets) |
| `server/runtime/src/main/webui/src/panels/drafthouse-diff.js` | `<drafthouse-diff>` — two-panel markdown diff viewer + minimap + scroll sync |
| `server/runtime/src/main/webui/src/panels/drafthouse-debate.js` | `<drafthouse-debate>` — debate event conversation feed (pages-event subscriber) |
| `server/runtime/src/main/webui/src/panels/drafthouse-review-tracker.js` | `<drafthouse-review-tracker>` — review point status checklist (pages-event subscriber) |
| `server/runtime/src/main/webui/src/panels/drafthouse-context-gauge.js` | `<drafthouse-context-gauge>` — topbar context usage gauge (pages-event onMeta subscriber) |
| `server/api/` | Pure Java domain model — depends on casehub-blocks (context tracking, message meta, bounded projection) and qhorus-api; includes `debate/` package, `DebateSession`, `DebateSessionSnapshot`, `DebateSessionStore` SPI, `DocumentEntry`, `ComparisonPair`, `ResolvedReviewer` |
| `server/runtime/` | Quarkus 3.34.3 app — all resources, Qhorus, LangChain4j |
| `server/runtime/src/main/java/io/casehub/drafthouse/` | Java resources: Ping, File, Ui, DraftHouseMcpTools, DebateMcpTools, DraftHouseInstances, ReviewerChannelBackend, ReviewerChannelBackendFactory, ReviewSessionRegistryImpl, DebateSessionRegistryImpl, DebateChannelBackend, DebateChannelBackendFactory, DebateEventResource, WebSocketEventBus, DebateWebSocket, NoOpDebateSessionStore, JpaDebateSessionStore, DebateSessionEntity, DraftHouseReviewerRegistry, SimplePromptRenderer, ReviewerDescriptorSeeder, ReviewerResolver, debate/ |
| `server/claude-agent/` | Optional module — ClaudeAgentSdkDebateAgentProvider (stub, pending platform#55) |
| `server/runtime/src/main/resources/application.properties` | Quarkus config |
| `server/runtime/target/drafthouse-server-runner.jar` | Built uber-jar (not committed) |
| `docs/FEATURES.md` | Feature backlog and DraftHouse MVP roadmap |
| `docs/superpowers/specs/` | Design specs |
| `docs/superpowers/plans/` | Implementation plans |
| `ARC42STORIES.MD` | Primary architecture record (Arc42Stories v0.1) — §9.4 for layer entries |
| `LAYER-LOG.md` | Source-of-truth draft feeding ARC42STORIES.MD; retained until migration verified |
| `design/` | Branch scaffold — JOURNAL.md and .meta per epic branch |
| `sample-a.md`, `sample-b.md` | Demo content for manual testing |
| `wksp/blog/` | Project diary entries (workspace-routed — never commit to project repo) |

## Architecture

```
Quarkus Server (drafthouse-server-runner.jar)
  ├── GET /api/ping          ← health check
  ├── GET /api/file?path=    ← read any local file
  ├── WS  /api/ws            ← WebSocket push (debate events, session lifecycle, file changes — pages wire format)
  ├── GET /                  ← Quinoa serves bundled webui (TypeScript → app.js)
  ├── MCP tools (review)     ← start_review, update_selection, query_review, end_review, list_reviewers, get_reviewer_instructions
  ├── MCP tools (debate)     ← start_debate, raise_point, respond_to, flag_human, get_debate_summary, end_debate, report_context
  ├── MCP tools (documents)  ← add_document, remove_document, list_documents, set_comparison, export_debate_summary
  ├── POST /api/debate/{id}/selection  ← store selection scope on debate session
  ├── DELETE /api/debate/{id}/selection  ← clear selection scope
  ├── GET /api/debate/{id}/documents  ← list working set documents + current comparison
  ├── POST /api/debate/{id}/comparison  ← browser-initiated comparison change
  └── GET /api/debate/sessions     ← active debate session list

Browser UI (casehub-pages workbench + Web Component panels)
  ├── index.ts                     ← workbench shell (casehub-pages layout, topbar, WebSocket connection, Electron IPC)
  ├── <drafthouse-diff>            ← diff panel (Shadow DOM Web Component)
  │   ├── fetch /api/file          ← load file content
  │   ├── pages-event file-changed ← live reload on file change (via WebSocket)
  │   ├── marked.js + highlight.js ← render markdown
  │   ├── LCS line diff + word-level highlights
  │   ├── Canvas minimap           ← red=A-side, green=B-side changes
  │   └── Scroll sync via anchors  ← heading-based anchor matching
  ├── <drafthouse-debate>          ← debate feed (Shadow DOM Web Component)
  │   └── pages-event              ← debate events via WebSocket, grouped by round
  ├── <drafthouse-review-tracker>  ← review checklist (Shadow DOM Web Component)
  │   └── pages-event              ← derives status per pointId from event stream
  └── <drafthouse-context-gauge>   ← context usage gauge (Shadow DOM Web Component, topbar)
      └── pages-event (onMeta)     ← context-usage metadata events
```

## Architectural Direction

DraftHouse uses **casehub-pages workbench** with Web Component panels. The workbench is built with `@casehubio/pages-ui` layout primitives (`rows()`, `split()`, `html()`) and rendered via `@casehubio/pages-runtime`. Panels are custom elements with Shadow DOM encapsulation, registered via `registerPanel()`, and orchestrated through the `pages-event` system.

**Practical implications:**
- Panels are Web Components with `configure(props)` — the method pages-runtime calls to initialize
- Shadow DOM encapsulation ensures panels can't leak styles or state; CSS custom properties on `:root` provide theming
- `pages-event` is the communication backbone — WebSocket events are dispatched by pages data pipeline, panels subscribe to topics
- The workbench layout is declarative TypeScript (not DOM manipulation) — composition via `rows()`, `split()`, `hostPanel()`
- TypeScript source is in `server/runtime/src/main/webui/src/`, bundled by Quinoa → `app.js`
- Quinoa serves the bundled app at `/` — no separate static file server needed

**Claudony repo:** `~/claude/claudony/` (standalone tier peer — see Peer Repos table)

## Quarkus Server Notes

- Version: 3.34.3 (quarkus-langchain4j 1.9.1, quarkus-websockets-next, casehub-qhorus 0.2-SNAPSHOT, casehub-blocks 0.2-SNAPSHOT, quarkus-quinoa 2.8.3)
- Java package: `io.casehub.drafthouse`
- Quinoa serves bundled TypeScript webui from `server/runtime/src/main/webui/` — bundles on every build
- Port: 9001 (default), configurable via `quarkus.http.port`
- Uber-jar build: `quarkus.package.type=uber-jar`

## Design Documents

- **Research spec:** `docs/superpowers/specs/2026-05-26-document-review-tool-research.md`
- **Feature backlog:** `docs/FEATURES.md`

## Work Tracking

**Issue tracking: enabled**
**GitHub repo:** `casehubio/drafthouse`

### Automatic behaviours

- **Before implementing anything:** check for an open issue; create one if none exists
- **Before a multi-task session:** create an epic + child issues before writing code
- **At every commit:** confirm issue linkage (`Refs #N` or `Closes #N`)

### Labels in use

| Label | Meaning |
|---|---|
| `epic` | Multi-issue body of work |
| `enhancement` | New feature or capability |
| `bug` | Something broken |
| `refactor` | Code restructuring, no behaviour change |
| `test` | Test coverage additions or fixes |
| `chore` | Tooling, config, maintenance |
| `documentation` | Docs additions or corrections |

### Commit footer format

```
Refs #N      ← work in progress, issue stays open
Closes #N    ← this commit completes the issue
```

Use `no-issue: <reason>` for commits that genuinely don't need an issue.

## Peer Repos — Hard Boundary

DraftHouse is part of the casehubio platform. The peer repos are:

| Tier | Repos |
|---|---|
| Foundation | casehub-engine, casehub-ledger, casehub-work, casehub-qhorus, casehub-connectors, casehub-eidos, casehub-platform, casehub-blocks |
| Application | casehub-devtown, casehub-aml, casehub-clinical, casehub-life, casehub-drafthouse |
| Standalone | quarkmind, claudony, openclaw |

**Claudony is the primary integration target.** When designing new UI or channel-like
features, check Claudony's architecture first and align where possible.

**Do not duplicate** abstractions or SPIs that belong in a foundation module. Check
`../parent/docs/PLATFORM.md` for ownership boundaries before adding shared concerns.

## What NOT to Do

- Do not commit `server/target/` — uber-jar is build output
- Do not remove the Electron shell — it is the distribution mechanism for website downloads
- Do not add `additionalDirectories` to `.claude/settings.json` — use `--add-dir` at launch
