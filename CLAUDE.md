# CLAUDE.md — md-compare

## Project Type

**Type:** custom (Electron + Quarkus + Playwright)

**Blog directory:** `blog/` (entries go into this project repo, not mdproctor.github.io)

## Overview

md-compare is a desktop side-by-side markdown comparison tool. It renders two `.md` files as HTML, computes a line-level LCS diff, shows a colour-coded minimap, and annotates changed blocks inline. Built for comparing writing drafts and style variants.

**Canonical location:** `~/claude/md-compare/`

## Starting a Claude Session

```bash
cd ~/claude/md-compare
claude --add-dir /Users/mdproctor/claude/sparge
```

The `--add-dir sparge` flag gives access to Sparge's patterns: `java-server.js` process manager, Electron wiring, Archive Room CSS tokens, and the Quarkus server structure. md-compare was extracted from Sparge — refer to Sparge's diary entries and DESIGN.md for the rationale behind shared decisions.

## Running the App

```bash
# Launch with sample files (uses Sparge's Electron binary — no local npm install needed)
/Users/mdproctor/claude/sparge/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron \
  /Users/mdproctor/claude/md-compare \
  /Users/mdproctor/claude/md-compare/sample-a.md \
  /Users/mdproctor/claude/md-compare/sample-b.md
```

The Electron app spawns the Quarkus server (`server/target/mdcompare-server-runner.jar`) on a dynamic port. The UI loads at `http://127.0.0.1:{port}/`.

## Building the Server

```bash
cd server && /opt/homebrew/bin/mvn package -DskipTests
# → server/target/mdcompare-server-runner.jar (~19 MB uber-jar)
```

## Testing

**Java server tests (6 tests):**
```bash
cd server && /opt/homebrew/bin/mvn test
```

**Playwright E2E tests (54 passing, 2 intentionally skipped):**
```bash
./node_modules/.bin/playwright test --reporter=list
```

The 2 skipped tests are scroll-sync tests that self-skip when the fixture content fits in the viewport — correct behaviour, not failures.

**Shared JVM:** `global-setup.js` starts a single Quarkus JVM shared by all specs. Each spec gets its own Electron window but no JVM. Suite completes in ~10s.

**Stale process note:** If global-setup crashes with "Target page, context or browser has been closed", kill stray processes first: `pkill -9 -f "Electron|mdcompare|quarkus"`. Happens when a previous run was cancelled without cleanup.

**Test fixtures:** Written to `$TMPDIR/mdcompare-test-{a,b}.md` by `global-setup.js` before each Playwright run.

## Key Directories

| Path | Contents |
|---|---|
| `index.html` | All UI: HTML, styles.css link, JS — the entire renderer |
| `styles.css` | Archive Room CSS tokens + panel/diff/minimap styles |
| `main.js` | Electron main: spawns Quarkus (skipped when `QUARKUS_PORT` env set), native file dialog IPC |
| `preload.js` | IPC bridge: `selectFile`, `onInitConfig`, `onInitFiles` |
| `java-server.js` | Quarkus process manager (state machine, crash recovery) |
| `server/` | Quarkus 3.34 Maven project |
| `server/src/main/java/io/mdcompare/server/` | Java resources: Ping, File, Watch, Ui, Critique |
| `server/target/mdcompare-server-runner.jar` | Built uber-jar (not committed) |
| `electron-tests/e2e/` | Playwright specs + helpers + global-setup/teardown |
| `playwright.config.js` | `workers:1` (sequential — specs share one Quarkus JVM via global-setup) |
| `node_modules/` | Symlink → Sparge's `node_modules` (gitignored) |
| `docs/FEATURES.md` | Feature backlog and planned work |
| `docs/superpowers/specs/` | Design specs (brainstorming output) |
| `docs/superpowers/plans/` | Implementation plans |
| `sample-a.md`, `sample-b.md` | Demo content for manual testing |

## Architecture

```
Electron (main.js)
  └── JavaServer (java-server.js) → Quarkus JAR
        ├── GET /api/ping          ← health poll at startup
        ├── GET /api/file?path=    ← read any local file
        ├── GET /api/watch?path=   ← SSE file-change stream
        ├── GET /                  ← serve index.html (from -Dui.dir)
        └── POST /api/critique     ← 501 stub (Phase 2)

Electron (renderer: index.html)
  ├── fetch /api/file              ← load file content
  ├── EventSource /api/watch       ← live reload on file change (ref-counted)
  ├── marked.js + highlight.js     ← render markdown
  ├── panels { a, b } object       ← single source of truth: path, content, label per panel
  ├── syncPanelDOM()               ← renders panels[x] state to DOM
  ├── swapPanels()                 ← swaps A↔B atomically (paths, content, labels)
  ├── nextDiff() / prevDiff()      ← n/p keyboard + ↑↓ buttons; viewport-recalibrating nav
  ├── scrollToChunk(ci)            ← scrolls both panels to annotated diff element
  ├── updateDiffSummary()          ← ~N −N +N topbar label; hover tooltip explains symbols
  ├── annotateWordDiffs()          ← word-level highlights within mod blocks; DOM-walking, preserves inline formatting
  ├── LCS line diff                ← compare A and B
  ├── Canvas minimap               ← red=A-side changes, green=B-side changes
  └── annotateRendered()           ← border-top/bottom on changed blocks
```

## Node / Electron Setup

- **No local `node_modules`** — `node_modules/` is a symlink to `~/claude/sparge/node_modules/`
- **Electron binary:** `~/claude/sparge/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron`
- **Playwright binary:** same `node_modules`, Playwright 1.59 installed there
- **npm:** available at `/opt/homebrew/bin/npm` (Homebrew Node 26)

## Quarkus Server Notes

- Version: 3.34.3
- `ui.dir` JVM arg controls where `UiResource` reads `index.html` + `styles.css` from
- Test port defaults to 8081 (the `%test.quarkus.http.port=9002` property is silently ignored — RestAssured auto-configures regardless)
- `WatchResource` uses Java 21 virtual threads + `WatchService`; annotation is `@RestSseElementType` (RESTEasy Reactive), not `@SseElementType`

## Feature Tracking

See `docs/FEATURES.md` for the backlog of planned features.

## Work Tracking

**Issue tracking: enabled**
**GitHub repo:** `mdproctor/md-compare`

### Automatic behaviours

- **Before implementing anything:** check for an open issue; create one if none exists (Phase 2)
- **Before a multi-task session:** create an epic + child issues before writing code (Phase 1)
- **At every commit:** confirm issue linkage (`Refs #N` or `Closes #N`) in the commit message (Phase 3)

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

Use `no-issue: <reason>` for commits that genuinely don't need an issue (e.g. typo fix, .gitignore update).

## What NOT to do

- Do not edit `node_modules/` directly — it's Sparge's modules
- Do not commit `server/target/` — uber-jar is build output
- Do not add `additionalDirectories` to `.claude/settings.json` — that key is not supported; use `--add-dir` at launch
