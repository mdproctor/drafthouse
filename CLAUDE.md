# CLAUDE.md — DraftHouse

## Project Type

**Type:** CaseHub application (Quarkus)

**Blog directory:** `blog/` (local project blog until workspace is configured)

## Overview

DraftHouse is an MCP-driven document review tool. Any LLM (Claude Code, Claudony, or
any MCP client) can open a document, show before/after versions, create reviewer LLM
agents, and have grounded conversations about specific parts of the document.

Currently provides side-by-side markdown comparison with LCS line diff, word-level
highlights, colour-coded minimap, and inline change annotations. The critique/review
features are Phase 2 (see research spec).

**Canonical location:** `~/claude/casehub/drafthouse/`
**GitHub repo:** `casehubio/drafthouse`

## Platform Context

This repo is a CaseHub application-tier project. Before implementing any feature
that touches shared concerns (channels, audit, orchestration), check the platform
architecture:

```
../parent/docs/PLATFORM.md
../parent/docs/APPLICATIONS.md
```

## Building the Server

```bash
cd server && /opt/homebrew/bin/mvn package -DskipTests
```

Produces `server/target/drafthouse-server-runner.jar`.

## Running the App

```bash
java -Dui.dir=/Users/mdproctor/claude/casehub/drafthouse \
  -jar server/target/drafthouse-server-runner.jar
```

Then open `http://localhost:9001/?a=/path/to/file-a.md&b=/path/to/file-b.md` in a browser.

- `ui.dir` — tells UiResource where to find `index.html` and `styles.css`
- Query params `?a=` and `?b=` — initial file paths to load

## Testing

**Java server tests (6 tests):**
```bash
cd server && /opt/homebrew/bin/mvn test
```

**E2E tests:** Deferred — Quarkus Playwright migration is a separate epic.

## Key Directories

| Path | Contents |
|---|---|
| `index.html` | All UI: HTML, JS, styles.css link — the entire renderer |
| `styles.css` | Archive Room CSS tokens + panel/diff/minimap styles |
| `server/` | Quarkus 3.34 Maven project |
| `server/src/main/java/io/casehub/drafthouse/` | Java resources: Ping, File, Watch, Ui, Critique |
| `server/src/main/resources/application.properties` | Quarkus config |
| `server/target/drafthouse-server-runner.jar` | Built uber-jar (not committed) |
| `docs/FEATURES.md` | Feature backlog and DraftHouse MVP roadmap |
| `docs/superpowers/specs/` | Design specs |
| `docs/superpowers/plans/` | Implementation plans |
| `LAYER-LOG.md` | CaseHub tutorial layer log |
| `sample-a.md`, `sample-b.md` | Demo content for manual testing |
| `blog/` | Project diary entries |

## Architecture

```
Quarkus Server (drafthouse-server-runner.jar)
  ├── GET /api/ping          ← health check
  ├── GET /api/file?path=    ← read any local file
  ├── GET /api/watch?path=   ← SSE file-change stream
  ├── GET /                  ← serve index.html (from -Dui.dir)
  └── POST /api/critique     ← 501 stub (Phase 2)

Browser UI (index.html)
  ├── fetch /api/file              ← load file content (relative URLs)
  ├── EventSource /api/watch       ← live reload on file change
  ├── marked.js + highlight.js     ← render markdown
  ├── URL query params (?a=&b=)    ← initial file loading
  ├── LCS line diff                ← compare A and B
  ├── Word-level diff highlights   ← within modified blocks
  ├── Canvas minimap               ← red=A-side, green=B-side changes
  └── Scroll sync via anchors      ← heading-based anchor matching
```

## Architectural Direction

DraftHouse is designed as a **standalone tool today, Claudony plugin tomorrow**. All
diff, rendering, and critique logic must be modular — cleanly separable from the
Electron shell and Quarkus serving layer so it can be embedded as a Claudony channel
view type when Claudony's plugin API stabilises.

**Practical implications:**
- Keep diff engine, word-level highlighting, and LLM critique anchoring as pure
  functions with no UI framework coupling
- Look at Claudony's channel architecture when designing new features — borrow
  patterns and share code where possible (channel model, message rendering, etc.)
- Don't build a second channel system — design for eventual convergence
- The Electron shell and browser mode are distribution wrappers, not the product

**Claudony repo:** `~/claude/claudony/` (standalone tier peer — see Peer Repos table)

## Quarkus Server Notes

- Version: 3.34.3
- Java package: `io.casehub.drafthouse`
- `ui.dir` JVM property controls where `UiResource` reads static assets from
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
| Foundation | casehub-engine, casehub-ledger, casehub-work, casehub-qhorus, casehub-connectors, casehub-eidos, casehub-platform |
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
