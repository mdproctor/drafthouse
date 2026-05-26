# DraftHouse Infrastructure Move — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate md-compare from `mdproctor/md-compare` to `casehubio/drafthouse` as a CaseHub application-tier project, removing Electron, renaming all artifacts, and integrating with the CaseHub build/CI/dashboard infrastructure.

**Architecture:** The Electron shell is dropped — Quarkus serves the UI directly, users open a browser. Java packages move from `io.mdcompare.server` to `io.casehub.drafthouse`. The repo transfers to `casehubio/drafthouse` with full git history. CaseHub parent gets BOM entries, CI workflow integration, dashboard registration, and website updates.

**Tech Stack:** Quarkus 3.34, Java 21, Maven, GitHub Actions, CaseHub parent POM

**Spec:** `docs/superpowers/specs/2026-05-26-document-review-tool-research.md`

**Out of scope:** Test migration (JS Playwright → Quarkus Playwright Java) — separate epic. DraftHouse feature development (MCP tools, Qhorus, LangChain4j) — separate epic. Sparge Electron removal — follow-on project.

---

## File Structure

### Files to create
- `server/src/main/java/io/casehub/drafthouse/FileResource.java` (moved from `io/mdcompare/server/`)
- `server/src/main/java/io/casehub/drafthouse/WatchResource.java`
- `server/src/main/java/io/casehub/drafthouse/PingResource.java`
- `server/src/main/java/io/casehub/drafthouse/UiResource.java`
- `server/src/main/java/io/casehub/drafthouse/CritiqueResource.java`
- `server/src/test/java/io/casehub/drafthouse/FileResourceTest.java` (moved)
- `server/src/test/java/io/casehub/drafthouse/WatchResourceTest.java`
- `server/src/test/java/io/casehub/drafthouse/PingResourceTest.java`
- `server/src/test/java/io/casehub/drafthouse/CritiqueResourceTest.java`
- `LAYER-LOG.md` (CaseHub requirement)
- `.githooks/pre-push` (CaseHub convention)
- `.github/workflows/publish.yml` (CaseHub CI)

### Files to delete
- `main.js` (Electron entry point)
- `preload.js` (Electron IPC bridge)
- `java-server.js` (Quarkus process manager for Electron)
- `package.json` (npm — no longer needed)
- `playwright.config.js` (JS Playwright config)
- `electron-tests/` (entire directory — 7 spec files + helpers + setup/teardown)
- `node_modules` (symlink to Sparge)
- `server/src/main/java/io/mdcompare/` (entire old package tree — after move)
- `server/src/test/java/io/mdcompare/` (entire old package tree — after move)

### Files to modify
- `server/pom.xml` — groupId, artifactId, version alignment
- `server/src/main/resources/application.properties` — output-name
- `index.html` — title, logo, remove Electron IPC, use relative URLs
- `styles.css` — no changes expected
- `CLAUDE.md` — complete rewrite for CaseHub conventions
- `HANDOFF.md` — update repo reference
- `docs/FEATURES.md` — update Phase 2 to reflect DraftHouse direction
- `.claude/settings.json` — remove Electron/npm permissions, update paths
- `.claude/settings.local.json` — remove Electron/npm permissions
- `.gitignore` — remove node_modules, add CaseHub patterns
- Blog entries (frontmatter only) — `projects: [md-compare]` → `projects: [drafthouse]`

### Files in CaseHub parent (separate repo — `~/claude/casehub/parent/`)
- `pom.xml` — add BOM entries
- `docs/PLATFORM.md` — add to dependency map
- `docs/APPLICATIONS.md` — add repo, deps, capabilities, deep-dive link
- `docs/repos/casehub-drafthouse.md` — new deep-dive doc
- `README.md` — add badge
- `docs/index.html` — add to APP_REPOS
- `.github/workflows/full-stack-build.yml` — add clone/build/outcome
- `.github/workflows/incremental-full-stack-build.yml` — add clone/SHA/decision/build
- `.github/workflows/dashboard.yml` — add to REPOS
- `.github/workflows/pr-dashboard.yml` — add to REPOS
- `build-all.sh` — add REPO_DIR, REPO_GH, DEPS, MODULE_PATH, REPOS

### Files in CaseHub website (`~/claude/casehub/casehubio.github.io/`)
- `index.html` — add SVG text element + project card

---

## Task 1: Create GitHub issue for infrastructure move

**Files:**
- None (GitHub API only)

- [ ] **Step 1: Create the issue**

```bash
gh issue create --repo mdproctor/md-compare \
  --title "Infrastructure: migrate md-compare → casehubio/drafthouse" \
  --label chore \
  --body "Migrate md-compare to CaseHub application tier as DraftHouse.

- Rename Maven artifacts, Java packages, UI references
- Remove Electron shell (browser-based UI via Quarkus)
- Transfer repo to casehubio org
- Integrate with CaseHub parent (BOM, CI, dashboards, website)
- Set up workspace, LAYER-LOG.md, githooks

Spec: docs/superpowers/specs/2026-05-26-document-review-tool-research.md"
```

Record the issue number — all commits in this plan reference it.

---

## Task 2: Rename Maven artifacts and Java packages

**Files:**
- Modify: `server/pom.xml`
- Modify: `server/src/main/resources/application.properties`
- Move: all 5 Java source files from `io/mdcompare/server/` → `io/casehub/drafthouse/`
- Move: all 4 Java test files from `io/mdcompare/server/` → `io/casehub/drafthouse/`

- [ ] **Step 1: Update pom.xml**

Change groupId, artifactId, and version to align with CaseHub conventions:

```xml
<groupId>io.casehub</groupId>
<artifactId>casehub-drafthouse</artifactId>
<version>0.2-SNAPSHOT</version>
```

Leave the rest of the pom.xml unchanged for now (parent POM inheritance comes in a later task when the repo is in casehubio).

- [ ] **Step 2: Update application.properties**

Change:
```properties
quarkus.package.output-name=mdcompare-server
```
To:
```properties
quarkus.package.output-name=drafthouse-server
```

- [ ] **Step 3: Create new package directories**

```bash
mkdir -p server/src/main/java/io/casehub/drafthouse
mkdir -p server/src/test/java/io/casehub/drafthouse
```

- [ ] **Step 4: Move Java source files and update package declarations**

For each of the 5 source files (`FileResource.java`, `WatchResource.java`, `PingResource.java`, `UiResource.java`, `CritiqueResource.java`):

1. Copy to new location
2. Update `package io.mdcompare.server;` → `package io.casehub.drafthouse;`

```bash
# Move source files
git mv server/src/main/java/io/mdcompare/server/FileResource.java \
       server/src/main/java/io/casehub/drafthouse/FileResource.java
git mv server/src/main/java/io/mdcompare/server/WatchResource.java \
       server/src/main/java/io/casehub/drafthouse/WatchResource.java
git mv server/src/main/java/io/mdcompare/server/PingResource.java \
       server/src/main/java/io/casehub/drafthouse/PingResource.java
git mv server/src/main/java/io/mdcompare/server/UiResource.java \
       server/src/main/java/io/casehub/drafthouse/UiResource.java
git mv server/src/main/java/io/mdcompare/server/CritiqueResource.java \
       server/src/main/java/io/casehub/drafthouse/CritiqueResource.java
```

Then in each file, change:
```java
package io.mdcompare.server;
```
to:
```java
package io.casehub.drafthouse;
```

- [ ] **Step 5: Move Java test files and update package declarations**

```bash
git mv server/src/test/java/io/mdcompare/server/FileResourceTest.java \
       server/src/test/java/io/casehub/drafthouse/FileResourceTest.java
git mv server/src/test/java/io/mdcompare/server/WatchResourceTest.java \
       server/src/test/java/io/casehub/drafthouse/WatchResourceTest.java
git mv server/src/test/java/io/mdcompare/server/PingResourceTest.java \
       server/src/test/java/io/casehub/drafthouse/PingResourceTest.java
git mv server/src/test/java/io/mdcompare/server/CritiqueResourceTest.java \
       server/src/test/java/io/casehub/drafthouse/CritiqueResourceTest.java
```

Then in each test file, change:
```java
package io.mdcompare.server;
```
to:
```java
package io.casehub.drafthouse;
```

- [ ] **Step 6: Remove old package directories**

```bash
rm -rf server/src/main/java/io/mdcompare
rm -rf server/src/test/java/io/mdcompare
```

- [ ] **Step 7: Build to verify**

```bash
cd server && /opt/homebrew/bin/mvn clean test
```

Expected: 6 tests pass. The package rename is transparent to the REST endpoints (JAX-RS routing is annotation-based, not package-based).

- [ ] **Step 8: Commit**

```bash
git add -A server/
git commit -m "refactor(#N): rename Maven artifacts and Java packages to io.casehub.drafthouse

groupId: io.mdcompare → io.casehub
artifactId: mdcompare-server → casehub-drafthouse
package: io.mdcompare.server → io.casehub.drafthouse
output-name: mdcompare-server → drafthouse-server

Refs #N"
```

---

## Task 3: Remove Electron infrastructure

**Files:**
- Delete: `main.js`, `preload.js`, `java-server.js`, `package.json`
- Delete: `electron-tests/` (entire directory)
- Delete: `playwright.config.js`
- Delete: `node_modules` (symlink)
- Modify: `.gitignore`

- [ ] **Step 1: Delete Electron files**

```bash
git rm main.js preload.js java-server.js package.json playwright.config.js
```

- [ ] **Step 2: Delete JS test infrastructure**

```bash
git rm -r electron-tests/
```

- [ ] **Step 3: Remove node_modules symlink**

```bash
rm node_modules
```

(This is a symlink to Sparge's node_modules, not tracked by git, but should be removed from the working tree.)

- [ ] **Step 4: Update .gitignore**

Replace current content:
```
node_modules/
test-results/
playwright-report/
.superpowers/
```

With CaseHub conventions:
```
target/
.idea/
*.class
.DS_Store
.superpowers/
wksp
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(#N): remove Electron shell — browser-based UI via Quarkus

Deleted: main.js, preload.js, java-server.js, package.json,
playwright.config.js, electron-tests/ (7 spec files, 54 tests).
Test migration to Quarkus Playwright is a separate epic.
Quarkus serves UI directly via UiResource — open browser to localhost.

Refs #N"
```

---

## Task 4: Update index.html for browser-only operation

**Files:**
- Modify: `index.html`

The UI currently depends on three Electron IPC calls via `window.compare`:
1. `selectFile()` — native file dialog
2. `onInitConfig({ port })` — sets `API_PORT` for building API URLs
3. `onInitFiles(pathA, pathB)` — loads initial files from CLI args

Without Electron, the UI is served by Quarkus at the same origin. API calls use relative URLs. Initial files come from URL query params.

- [ ] **Step 1: Update title and logo**

Change line 6:
```html
<title>md-compare</title>
```
to:
```html
<title>DraftHouse</title>
```

Change line 34:
```html
<div id="logo">md-compare</div>
```
to:
```html
<div id="logo">DraftHouse</div>
```

- [ ] **Step 2: Replace API_PORT with relative URLs**

Change the `apiUrl` function (line 123-125):
```javascript
function apiUrl(path) {
  return `http://127.0.0.1:${API_PORT}${path}`;
}
```
to:
```javascript
function apiUrl(path) {
  return path;
}
```

Remove the `API_PORT` variable declaration (line 116):
```javascript
let API_PORT    = null;
```
→ delete this line.

- [ ] **Step 3: Replace selectFile with prompt**

Change the `selectFile` function (lines 174-177):
```javascript
async function selectFile(panel) {
  const path = await window.compare.selectFile();
  if (path) await loadFile(panel, path);
}
```
to:
```javascript
async function selectFile(panel) {
  const path = prompt('Enter file path:');
  if (path) await loadFile(panel, path);
}
```

- [ ] **Step 4: Replace Electron init with URL query params**

Replace the entire init section (lines 655-671):
```javascript
let _pendingFiles = null;

window.compare.onInitConfig(async ({ port }) => {
  API_PORT = port;
  if (_pendingFiles) {
    const [pathA, pathB] = _pendingFiles;
    _pendingFiles = null;
    if (pathA) await loadFile('a', pathA);
    if (pathB) await loadFile('b', pathB);
  }
});

window.compare.onInitFiles(async (pathA, pathB) => {
  if (API_PORT === null) { _pendingFiles = [pathA, pathB]; return; }
  if (pathA) await loadFile('a', pathA);
  if (pathB) await loadFile('b', pathB);
});
```
with:
```javascript
// Load initial files from URL query params: ?a=/path/to/a.md&b=/path/to/b.md
(async () => {
  const params = new URLSearchParams(window.location.search);
  const pathA = params.get('a');
  const pathB = params.get('b');
  if (pathA) await loadFile('a', pathA);
  if (pathB) await loadFile('b', pathB);
})();
```

- [ ] **Step 5: Update critique button title**

Change line 44:
```html
<button id="btn-critique" onclick="toggleCritique()" title="Open critique panel (Phase 2)" style="opacity:.4" disabled>✦ Critique</button>
```
to:
```html
<button id="btn-critique" onclick="toggleCritique()" title="Open critique panel" style="opacity:.4" disabled>✦ Critique</button>
```

- [ ] **Step 6: Verify manually**

```bash
cd server && /opt/homebrew/bin/mvn package -DskipTests
java -Dui.dir=/Users/mdproctor/claude/md-compare -jar target/drafthouse-server-runner.jar
```

Open `http://localhost:9001/?a=/Users/mdproctor/claude/md-compare/sample-a.md&b=/Users/mdproctor/claude/md-compare/sample-b.md` in a browser. Verify:
- Title shows "DraftHouse"
- Logo shows "DraftHouse"
- Both panels load and render markdown
- Diff highlights appear
- Minimap renders
- Choose button opens a prompt dialog
- Scroll sync works

Kill the server after verification.

- [ ] **Step 7: Commit**

```bash
git add index.html
git commit -m "refactor(#N): update UI for browser-only operation

Title and logo: md-compare → DraftHouse.
API URLs: absolute with port → relative (same origin).
File selection: Electron native dialog → prompt().
Initial files: Electron IPC → URL query params (?a=&b=).

Refs #N"
```

---

## Task 5: Update documentation and config files

**Files:**
- Modify: `HANDOFF.md`
- Modify: `docs/FEATURES.md`
- Modify: `.claude/settings.json`
- Modify: `.claude/settings.local.json`
- Modify: `blog/2026-05-25-mdp01-bug-that-count-was-hiding.md` (frontmatter)
- Modify: `blog/2026-05-25-mdp02-scroll-sync-two-invisible-bugs.md` (frontmatter)

- [ ] **Step 1: Update HANDOFF.md**

Replace the `GitHub repo` reference:
```
| GitHub repo | `mdproctor/md-compare` |
```
with:
```
| GitHub repo | `casehubio/drafthouse` |
```

- [ ] **Step 2: Update docs/FEATURES.md**

Update the Phase 2 section to reflect DraftHouse direction. Replace:
```markdown
### Phase 2 — LLM Critique

- [ ] Wire `POST /api/critique` to Claude API (streaming)
- [ ] Critique panel content: "what changed, why it's better/worse"
- [ ] LangChain4j integration in Quarkus server
- [ ] Streaming prose display in the critique panel

### Phase 3 — Interactive critique (longer term)

- [ ] Select a passage → request inline rewrite
- [ ] Generated version appears in right pane
```
with:
```markdown
### Phase 2 — DraftHouse MVP

See research spec: `docs/superpowers/specs/2026-05-26-document-review-tool-research.md`

- [ ] MCP tool surface (start_review, push_revision, get_cursor_context, get_diff, end_review)
- [ ] Qhorus channels for conversation threading
- [ ] Single LLM reviewer via LangChain4j
- [ ] Git worktree versioning (JGit)
- [ ] Quarkus Playwright E2E tests (replacing deleted JS Playwright suite)

### Post-MVP

- [ ] Selection-scoped conversation channels
- [ ] Multi-LLM reviewers with personality library
- [ ] ReviewStrategy SPI
- [ ] Multi-document working sets
- [ ] GraalVM native image
```

- [ ] **Step 3: Update .claude/settings.json**

Replace with:
```json
{
  "permissions": {
    "allow": [
      "Bash(git:*)",
      "Bash(git -C *:*)",
      "Bash(ls:*)",
      "Bash(grep:*)",
      "Bash(find . *:*)",
      "Bash(/opt/homebrew/bin/mvn *)",
      "Bash(java *)",
      "Bash(curl *)",
      "Bash(pgrep *)",
      "Bash(kill *)",
      "Bash(mkdir -p *)",
      "Bash(pkill -f drafthouse-server)",
      "Bash(pkill -f \"java.*quarkus\")"
    ]
  }
}
```

- [ ] **Step 4: Truncate .claude/settings.local.json**

Most entries are stale session-specific permissions (specific PIDs, Electron references). Replace with:
```json
{
  "permissions": {
    "allow": [
      "Bash(gh repo *)",
      "Bash(gh auth *)",
      "Bash(gh label *)",
      "Bash(gh issue *)",
      "Bash(gh pr *)",
      "Bash(open *)",
      "WebSearch"
    ]
  }
}
```

- [ ] **Step 5: Update blog frontmatter**

In `blog/2026-05-25-mdp01-bug-that-count-was-hiding.md`, change:
```yaml
projects: [md-compare]
```
to:
```yaml
projects: [drafthouse]
```

In `blog/2026-05-25-mdp02-scroll-sync-two-invisible-bugs.md`, same change:
```yaml
projects: [md-compare]
```
to:
```yaml
projects: [drafthouse]
```

(The third blog entry has no `projects:` field — no change needed.)

- [ ] **Step 6: Commit**

```bash
git add HANDOFF.md docs/FEATURES.md .claude/settings.json .claude/settings.local.json blog/
git commit -m "docs(#N): update documentation and config for DraftHouse rename

HANDOFF.md: repo reference updated.
FEATURES.md: Phase 2/3 replaced with DraftHouse MVP roadmap.
Claude settings: Electron/npm permissions removed.
Blog frontmatter: projects field updated.

Refs #N"
```

---

## Task 6: Rewrite CLAUDE.md for DraftHouse

**Files:**
- Modify: `CLAUDE.md`

This is a complete rewrite — the project identity changes from "md-compare (Electron + Quarkus)" to "DraftHouse (CaseHub application, Quarkus-only)".

- [ ] **Step 1: Write new CLAUDE.md**

Replace the entire file with content that reflects:
- Project type: CaseHub application
- No Electron, no npm — pure Quarkus
- Server build: `cd server && /opt/homebrew/bin/mvn package -DskipTests`
- Run: `java -Dui.dir=. -jar server/target/drafthouse-server-runner.jar` then open browser
- Tests: `cd server && /opt/homebrew/bin/mvn test` (6 Java tests)
- E2E tests: deferred (Quarkus Playwright — separate epic)
- Architecture: Quarkus serves UI + REST API, browser client
- Key directories updated (no main.js, preload.js, java-server.js, electron-tests/)
- Java package: `io.casehub.drafthouse`
- CaseHub conventions: issue tracking, commit footers, work tracking
- Peer repos hard boundary (list all casehubio repos)
- Reference to research spec and FEATURES.md

The CLAUDE.md structure should follow CaseHub app conventions (see `casehub-aml` or `casehub-clinical` as templates), but adapted for DraftHouse's unique situation (not yet on parent POM, still has flat `server/` structure rather than `api/` + `app/`).

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(#N): rewrite CLAUDE.md for DraftHouse as CaseHub application

Refs #N"
```

---

## Task 7: Add CaseHub app metadata

**Files:**
- Create: `LAYER-LOG.md`
- Create: `.githooks/pre-push`
- Create: `.github/workflows/publish.yml`

- [ ] **Step 1: Create LAYER-LOG.md**

```markdown
# LAYER-LOG — DraftHouse

> DraftHouse is an MCP-driven document review tool where any LLM can open documents,
> show before/after versions, create reviewer agents, and have selection-scoped
> conversations about specific parts of the document.

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
`git log --grep="#N" --oneline` (replace N with the infrastructure issue number)
```

- [ ] **Step 2: Create .githooks/pre-push**

```bash
mkdir -p .githooks
```

Write `.githooks/pre-push`:
```bash
#!/bin/bash
# pre-push hook: always prompt for /git-squash review before pushing.
# Bypass with: git push --no-verify

remote="$1"

while read local_ref local_sha remote_ref remote_sha; do
  [ "$local_sha" = "0000000000000000000000000000000000000000" ] && continue

  if [ "$remote_sha" = "0000000000000000000000000000000000000000" ]; then
    base=$(git rev-parse --verify origin/main 2>/dev/null || git rev-parse --verify origin/master 2>/dev/null || echo "")
    [ -z "$base" ] && continue
    range="$base..$local_sha"
  else
    range="$remote_sha..$local_sha"
  fi

  total=$(git log --oneline "$range" 2>/dev/null | wc -l | tr -d ' ')
  [ "$total" -eq 0 ] && continue

  echo ""
  echo "╔══════════════════════════════════════════════════════════╗"
  echo "║  Pre-push: $total commit(s) about to push               "
  echo "╠══════════════════════════════════════════════════════════╣"
  echo "║  Run /git-squash to review history before pushing,      ║"
  echo "║  or use --no-verify to skip.                            ║"
  echo "╚══════════════════════════════════════════════════════════╝"
  echo ""
  exit 1
done

exit 0
```

```bash
chmod +x .githooks/pre-push
```

- [ ] **Step 3: Create .github/workflows/publish.yml**

```yaml
name: CI

on:
  repository_dispatch:
    types: [upstream-published]
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      - name: Build
        if: github.event_name == 'pull_request'
        run: cd server && mvn --batch-mode install
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and publish
        if: github.event_name != 'pull_request'
        run: cd server && mvn --batch-mode deploy -DskipTests
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Note: No downstream dispatch needed — DraftHouse is a leaf node (nothing depends on it). The `repository_dispatch` trigger ensures it rebuilds when upstream CaseHub modules publish.

- [ ] **Step 4: Commit**

```bash
git add LAYER-LOG.md .githooks/pre-push .github/workflows/publish.yml
git commit -m "chore(#N): add CaseHub app metadata — LAYER-LOG, githook, CI

LAYER-LOG.md: Layer 0 scaffold entry.
.githooks/pre-push: squash review prompt (CaseHub convention).
.github/workflows/publish.yml: CI with repository_dispatch trigger.

Refs #N"
```

---

## Task 8: GitHub transfer and local folder move

**Files:**
- None modified — repo-level operations

**Prerequisites:** All rename/removal commits from Tasks 2–7 must be pushed to `mdproctor/md-compare` before transfer.

- [ ] **Step 1: Push all changes**

```bash
git push origin main
```

- [ ] **Step 2: Transfer the repository**

```bash
gh repo transfer mdproctor/md-compare casehubio --new-name drafthouse
```

This preserves full git history, issues, stars, and redirects the old URL. The user must have admin access to both `mdproctor/md-compare` and the `casehubio` org.

**Wait for the transfer to complete** — GitHub processes transfers asynchronously. Verify:

```bash
gh repo view casehubio/drafthouse --json nameWithOwner
```

Expected: `{"nameWithOwner":"casehubio/drafthouse"}`

- [ ] **Step 3: Move local folder**

```bash
mv ~/claude/md-compare ~/claude/casehub/drafthouse
```

- [ ] **Step 4: Update git remotes**

Per CaseHub convention: `origin` = personal fork, `upstream` = casehubio org.

```bash
# Fork to personal account
gh repo fork casehubio/drafthouse --clone=false

# Rewire remotes
git -C ~/claude/casehub/drafthouse remote set-url origin https://github.com/mdproctor/drafthouse.git
git -C ~/claude/casehub/drafthouse remote add upstream https://github.com/casehubio/drafthouse.git
```

Verify:
```bash
git -C ~/claude/casehub/drafthouse remote -v
```

Expected: `origin` → `mdproctor/drafthouse`, `upstream` → `casehubio/drafthouse`.

- [ ] **Step 5: Update global Claude memory files**

The Claude memory directory is keyed by the project path. The old path was `-Users-mdproctor-claude-md-compare`. The new path will be `-Users-mdproctor-claude-casehub-drafthouse`.

```bash
# Check existing memory directory
ls ~/.claude/projects/-Users-mdproctor-claude-md-compare/memory/
```

The memory files (`MEMORY.md`, `feedback_workflow_norms.md`, `feedback_wrap_means_handoff.md`) should be moved to the new path. Claude Code will create the new directory structure on first launch from the new path.

```bash
mkdir -p ~/.claude/projects/-Users-mdproctor-claude-casehub-drafthouse/memory/
cp ~/.claude/projects/-Users-mdproctor-claude-md-compare/memory/* \
   ~/.claude/projects/-Users-mdproctor-claude-casehub-drafthouse/memory/
```

- [ ] **Step 6: Update IntelliJ recent projects**

IntelliJ stores recent project paths in `~/Library/Application Support/JetBrains/IntelliJIdea*/options/recentProjects.xml`. The old path `~/claude/md-compare/server` needs updating.

Open IntelliJ → File → Open → navigate to `~/claude/casehub/drafthouse/server` to register the new location. The old entry in recent projects can be removed from the Welcome screen.

Also delete the old `.idea` directory if it contains stale paths:
```bash
rm -rf ~/claude/casehub/drafthouse/server/.idea
```

IntelliJ will regenerate it on next open.

---

## Task 9: CaseHub parent — BOM and docs

**Files (all in `~/claude/casehub/parent/`):**
- Modify: `pom.xml`
- Modify: `docs/PLATFORM.md`
- Modify: `docs/APPLICATIONS.md`
- Create: `docs/repos/casehub-drafthouse.md`
- Modify: `README.md`

- [ ] **Step 1: Add BOM entries to parent pom.xml**

Add under `<dependencyManagement>` → `<dependencies>`, after the last casehub module block:

```xml
<!-- ================================================================ -->
<!-- casehub-drafthouse                                               -->
<!-- ================================================================ -->
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-drafthouse</artifactId>
  <version>${casehub.version}</version>
</dependency>
```

- [ ] **Step 2: Update PLATFORM.md**

Add DraftHouse to the Repository Map table (application tier section):
```markdown
Application tier (devtown, aml, clinical, life, drafthouse): see [APPLICATIONS.md](APPLICATIONS.md).
```

Add to the Cross-Repo Dependency Map (after devtown entries):
```markdown
| `casehub-qhorus-api` | `drafthouse` | `app` | channel routing |
| `casehub-qhorus` (runtime) | `drafthouse` | `app` | runtime dep |
```

Note: DraftHouse initially depends only on Qhorus. Engine, ledger, work dependencies come in later epics.

- [ ] **Step 3: Update APPLICATIONS.md**

Add to Repository Map table:
```markdown
| `casehub-drafthouse` | [casehubio/drafthouse](https://github.com/casehubio/drafthouse) | MCP-driven document review — multi-LLM critique, selection-scoped conversations, version-tracked revisions | Scaffold |
```

Add to Platform Dependencies:
```markdown
casehub-drafthouse — depends on: qhorus (initially; engine + ledger + work added later)
```

Add to Per-Repo Deep Dives table:
```markdown
| `casehub-drafthouse` | [docs/repos/casehub-drafthouse.md](https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-drafthouse.md) |
```

- [ ] **Step 4: Create deep-dive doc**

Create `docs/repos/casehub-drafthouse.md`:

```markdown
# casehub-drafthouse — Deep Dive

## What It Is

DraftHouse is an MCP-driven document review tool. Any LLM (Claude Code, Claudony, or
any MCP client) can open a document, show before/after versions, create reviewer LLM
agents, and have grounded conversations about specific parts of the document.

Evolved from md-compare — a side-by-side markdown comparison tool. Promoted to CaseHub
application tier to leverage Qhorus for conversation channels and LangChain4j for
provider-agnostic LLM calls.

## What It Owns

- Document comparison UI (side-by-side rendered markdown with LCS diff)
- MCP tool surface for LLM-driven document review
- Reviewer agent lifecycle (personality library, conversation strategies)
- Document version history (git worktree-based)

## What It Does NOT Own

Everything in the CaseHub foundation: audit trail (casehub-ledger), channels and
messaging (casehub-qhorus), case orchestration (casehub-engine), human task inbox
(casehub-work), outbound notifications (casehub-connectors), agent identity
(casehub-eidos).

## Dependencies

```
casehub-qhorus    — channels, typed messages, instance registry
LangChain4j       — provider-agnostic LLM calls (Quarkus extension)
JGit              — version history via git worktrees
```

Future: casehub-engine (if case orchestration needed), casehub-eidos (agent identity).

## Module Structure

Currently flat (`server/` with Quarkus app). Will adopt `api/` + `app/` hexagonal
structure when the first CaseHub foundation dependency is wired in.

## Key Epics

1. Scaffold — infrastructure migration from md-compare (done)
2. MCP tool surface — start_review, push_revision, get_cursor_context, get_diff, end_review
3. Qhorus channels — conversation threading per review session
4. LangChain4j reviewer — single internal reviewer agent
5. Selection-scoped conversations — per-selection Qhorus channels with anchored UI
6. Multi-LLM reviewers — personality library, ReviewStrategy SPI

## Design Documents

- Research spec: `docs/superpowers/specs/2026-05-26-document-review-tool-research.md` (in drafthouse repo)
```

- [ ] **Step 5: Update README.md**

Add badge under the Applications section:
```markdown
| [casehub-drafthouse](https://github.com/casehubio/drafthouse) | [![casehub-drafthouse](https://github.com/casehubio/drafthouse/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/drafthouse/actions/workflows/publish.yml) |
```

- [ ] **Step 6: Commit parent changes**

```bash
git -C ~/claude/casehub/parent add pom.xml docs/PLATFORM.md docs/APPLICATIONS.md \
  docs/repos/casehub-drafthouse.md README.md
git -C ~/claude/casehub/parent commit -m "chore: register casehub-drafthouse — BOM, docs, badge"
git -C ~/claude/casehub/parent push upstream main
```

---

## Task 10: CaseHub parent — CI workflows and build scripts

**Files (all in `~/claude/casehub/parent/`):**
- Modify: `.github/workflows/full-stack-build.yml`
- Modify: `.github/workflows/incremental-full-stack-build.yml`
- Modify: `.github/workflows/dashboard.yml`
- Modify: `.github/workflows/pr-dashboard.yml`
- Modify: `build-all.sh`
- Modify: `docs/index.html`

- [ ] **Step 1: Update full-stack-build.yml**

Add to the clone step (after the `life` clone, inside the `include_applications` block):
```bash
git clone --quiet "https://x-access-token:${GITHUB_TOKEN}@github.com/casehubio/drafthouse.git" casehub/drafthouse
```

Add build step (after life's build step, inside the `if: inputs.include_applications` block):
```yaml
- name: "Build: drafthouse"
  id: drafthouse
  if: inputs.include_applications
  continue-on-error: true
  env:
    SKIP_TESTS: ${{ inputs.skip_tests }}
    SKIP_ITS: ${{ inputs.skip_integration_tests }}
    MAVEN_EXTRA: ${{ inputs.maven_args }}
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  run: |
    FLAGS=""
    [ "$SKIP_TESTS" = "true" ] && FLAGS="-DskipTests"
    [ "$SKIP_ITS" = "true" ] && FLAGS="$FLAGS -DskipITs"
    START=$SECONDS
    cd casehub/drafthouse/server && mvn install --batch-mode $FLAGS $MAVEN_EXTRA
    echo $((SECONDS - START)) > ../../../.build-times/drafthouse
```

Add outcome variable in the summary env block:
```yaml
OUTCOME_DRAFTHOUSE: ${{ steps.drafthouse.outcome }}
```

Add to the `MODULES` array:
```bash
[ "$INCLUDE_APPLICATIONS" = "true" ] && MODULES+=(openclaw devtown aml clinical life drafthouse)
```

Add to the `OUTCOMES` map:
```bash
[drafthouse]="$OUTCOME_DRAFTHOUSE"
```

Add to the exclusion logic:
```bash
elif { [ "$module" = "openclaw" ] || [ "$module" = "devtown" ] || [ "$module" = "aml" ] || [ "$module" = "clinical" ] || [ "$module" = "life" ] || [ "$module" = "drafthouse" ]; } && [ "$INCLUDE_APPLICATIONS" != "true" ]; then
```

Add to the `GH_REPO` map:
```bash
[drafthouse]="casehubio/drafthouse"
```

- [ ] **Step 2: Update incremental-full-stack-build.yml**

Add to the clone step (same pattern as full-stack):
```bash
git clone --quiet "https://x-access-token:${GITHUB_TOKEN}@github.com/casehubio/drafthouse.git" casehub/drafthouse
```

Add SHA collection:
```bash
DH=$(git -C casehub/drafthouse rev-parse HEAD)
```

Add `drafthouse` to the MODULES array.

Add build decision and execution (after life's block):
```bash
D=$(./scripts/incremental-build-decision.sh \
  --module drafthouse --current-sha "$CUR" --previous-sha "${PRV:-none}" \
  --dep "parent:${CUR_P}:${PRV_P:-none}" \
  --dep "qhorus:${CUR_Q}:${PRV_Q:-none}")
```

(Only `parent` and `qhorus` as deps initially — add more as DraftHouse wires them in.)

- [ ] **Step 3: Update dashboard.yml**

Add `casehubio/drafthouse` to the REPOS printf list (after `casehubio/life`):
```bash
REPOS=$(printf '%s\n' \
  casehubio/parent \
  casehubio/platform \
  casehubio/ledger \
  casehubio/eidos \
  casehubio/connectors \
  casehubio/work \
  casehubio/qhorus \
  casehubio/engine \
  casehubio/claudony \
  casehubio/openclaw \
  casehubio/devtown \
  casehubio/aml \
  casehubio/clinical \
  casehubio/life \
  casehubio/drafthouse \
  mdproctor/quarkmind)
```

- [ ] **Step 4: Update pr-dashboard.yml**

Same change as dashboard.yml — add `casehubio/drafthouse` to the REPOS list.

- [ ] **Step 5: Update build-all.sh**

Add entries (after `life` entries in each section):
```bash
REPO_DIR[drafthouse]="../drafthouse/server"
REPO_GH[drafthouse]="drafthouse"
DEPS[drafthouse]="qhorus"
MODULE_PATH[drafthouse]="../drafthouse/server"
```

Note: DraftHouse has a `server/` subdir containing the Maven project, unlike other CaseHub apps that build from the repo root. This will change when the project adopts the `api/` + `app/` module structure (follow-up epic).

Add to the `REPOS+=()` line under `--include-apps`:
```bash
REPOS+=(openclaw devtown aml clinical life drafthouse)
```

- [ ] **Step 6: Update docs/index.html (dashboard HTML)**

Add to the `APP_REPOS` array:
```javascript
const APP_REPOS = [
  { org: 'casehubio', name: 'devtown' },
  { org: 'casehubio', name: 'aml' },
  { org: 'casehubio', name: 'clinical' },
  { org: 'casehubio', name: 'life' },
  { org: 'casehubio', name: 'drafthouse' },
  { org: 'mdproctor',  name: 'quarkmind' },
];
```

- [ ] **Step 7: Commit**

```bash
git -C ~/claude/casehub/parent add .github/workflows/ build-all.sh docs/index.html
git -C ~/claude/casehub/parent commit -m "chore: add casehub-drafthouse to CI workflows, build scripts, dashboard"
git -C ~/claude/casehub/parent push upstream main
```

---

## Task 11: CaseHub website

**Files (in `~/claude/casehub/casehubio.github.io/`):**
- Modify: `index.html`

- [ ] **Step 1: Add DraftHouse to SVG architecture diagram**

In the APPLICATION tier band, add a `<text>` element (pick an x-coordinate that doesn't overlap existing labels — after `life` at x=390):
```html
<text x="432" y="72" font-size="15" fill="#b8d8e0" font-family="'JetBrains Mono','Fira Code','Courier New',monospace">drafthouse</text>
```

- [ ] **Step 2: Add project card**

Add a `<div class="project-card">` block in the Applications tab:
```html
<div class="project-card">
  <span class="card-repo">casehub-drafthouse</span>
  <span class="card-headline">MCP-driven document review with multi-LLM critique and selection-scoped conversations</span>
  <p class="card-desc">DraftHouse lets any LLM open documents, show before/after versions, create reviewer agents with distinct personalities, and have grounded conversations anchored to specific text selections — producing version-tracked revisions with a full audit trail via Qhorus channels.</p>
  <a href="https://github.com/casehubio/drafthouse" class="card-link" target="_blank" rel="noopener">View on GitHub &#x2197;</a>
</div>
```

- [ ] **Step 3: Commit and push**

```bash
git -C ~/claude/casehub/casehubio.github.io add index.html
git -C ~/claude/casehub/casehubio.github.io commit -m "feat: add DraftHouse to architecture diagram and applications"
git -C ~/claude/casehub/casehubio.github.io push
```

---

## Task 12: Workspace setup

**Files:**
- Create: `~/claude/public/casehub/drafthouse/` (workspace directory tree)
- Create: `wsp-casehub-drafthouse` GitHub repo
- Modify: `~/claude/casehub/drafthouse/` (add `wksp` symlink)

- [ ] **Step 1: Create workspace directory structure**

```bash
mkdir -p ~/claude/public/casehub/drafthouse/{adr,blog,plans,snapshots,specs}
```

- [ ] **Step 2: Create symlinks**

```bash
# proj → project repo
ln -s ~/claude/casehub/drafthouse ~/claude/public/casehub/drafthouse/proj

# CLAUDE.md → project CLAUDE.md
ln -s ~/claude/casehub/drafthouse/CLAUDE.md ~/claude/public/casehub/drafthouse/CLAUDE.md

# wksp → workspace (in project root)
ln -s ~/claude/public/casehub/drafthouse ~/claude/casehub/drafthouse/wksp
```

- [ ] **Step 3: Create workspace stubs**

Create `HANDOFF.md` stub in workspace:
```markdown
# Handover

*No handover yet — first session.*
```

Create `IDEAS.md` stub:
```markdown
# Ideas

*Capture ideas and possibilities here.*
```

Create `INDEX.md` stubs in each artifact subdir (`adr/`, `blog/`, `plans/`, `snapshots/`, `specs/`):
```markdown
# Index

*No entries yet.*
```

Create `.gitignore`:
```
proj
CLAUDE.md
.DS_Store
```

- [ ] **Step 4: Initialize workspace git repo**

```bash
gh repo create mdproctor/wsp-casehub-drafthouse --private
git init ~/claude/public/casehub/drafthouse
git -C ~/claude/public/casehub/drafthouse remote add origin https://github.com/mdproctor/wsp-casehub-drafthouse.git
git -C ~/claude/public/casehub/drafthouse add .
git -C ~/claude/public/casehub/drafthouse commit -m "init: workspace scaffold for casehub-drafthouse"
git -C ~/claude/public/casehub/drafthouse push -u origin main
```

- [ ] **Step 5: Update parent workspace .gitignore**

Add `/drafthouse` to `~/claude/public/casehub/.gitignore` so the parent workspace doesn't track this child.

```bash
echo "/drafthouse" >> ~/claude/public/casehub/.gitignore
git -C ~/claude/public/casehub add .gitignore
git -C ~/claude/public/casehub commit -m "chore: exclude drafthouse workspace from parent"
git -C ~/claude/public/casehub push
```

---

## Task 13: Dispatch chain verification

**Files:**
- None modified directly — verification only, with issues filed if gaps found

- [ ] **Step 1: Verify DraftHouse receives upstream dispatches**

DraftHouse depends on Qhorus. Check if Qhorus dispatches to DraftHouse:

```bash
gh api repos/casehubio/qhorus/contents/.github/workflows/publish.yml --jq '.content' | base64 -d | grep -A5 "Trigger downstream"
```

If DraftHouse is not in Qhorus's dispatch list, file an issue:

```bash
gh issue create --repo casehubio/qhorus \
  --title "chore: add drafthouse to CI dispatch chain" \
  --body "DraftHouse depends on Qhorus. Add casehubio/drafthouse to the downstream dispatch list in publish.yml so DraftHouse rebuilds when Qhorus publishes."
```

- [ ] **Step 2: Verify DraftHouse's own publish.yml has repository_dispatch**

Already added in Task 7. Confirm:

```bash
grep repository_dispatch ~/claude/casehub/drafthouse/.github/workflows/publish.yml
```

Expected: `types: [upstream-published]`

---

## Task 14: Final verification

- [ ] **Step 1: Verify Maven build**

```bash
cd ~/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn clean test
```

Expected: 6 tests pass.

- [ ] **Step 2: Verify manual launch**

```bash
cd ~/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn package -DskipTests
java -Dui.dir=~/claude/casehub/drafthouse -jar target/drafthouse-server-runner.jar
```

Open browser to `http://localhost:9001/`. Verify the UI loads with "DraftHouse" branding.

Kill the server.

- [ ] **Step 3: Verify GitHub repo**

```bash
gh repo view casehubio/drafthouse --json nameWithOwner,description
```

- [ ] **Step 4: Verify CI**

```bash
gh workflow list --repo casehubio/drafthouse
```

- [ ] **Step 5: Verify dashboard**

Open the CaseHub dashboard HTML locally and confirm DraftHouse appears in the Applications section.

- [ ] **Step 6: Close the infrastructure issue**

```bash
gh issue close N --repo casehubio/drafthouse --comment "Infrastructure migration complete. All artifacts renamed, Electron removed, repo transferred, CaseHub integration done."
```

---

## Summary of commits

| # | Scope | Message |
|---|-------|---------|
| 1 | server/ | `refactor(#N): rename Maven artifacts and Java packages to io.casehub.drafthouse` |
| 2 | root | `refactor(#N): remove Electron shell — browser-based UI via Quarkus` |
| 3 | index.html | `refactor(#N): update UI for browser-only operation` |
| 4 | docs/config | `docs(#N): update documentation and config for DraftHouse rename` |
| 5 | CLAUDE.md | `docs(#N): rewrite CLAUDE.md for DraftHouse as CaseHub application` |
| 6 | metadata | `chore(#N): add CaseHub app metadata — LAYER-LOG, githook, CI` |
| 7 | parent | `chore: register casehub-drafthouse — BOM, docs, badge` |
| 8 | parent | `chore: add casehub-drafthouse to CI workflows, build scripts, dashboard` |
| 9 | website | `feat: add DraftHouse to architecture diagram and applications` |
| 10 | workspace | `init: workspace scaffold for casehub-drafthouse` |

## Follow-up epics (not in this plan)

1. **Quarkus Playwright test migration** — port the 54 JS Playwright tests to `@QuarkusTest` + `@WithPlaywright` in Java. Add `quarkus-playwright` extension to pom.xml.
2. **DraftHouse MVP** — MCP tool surface, Qhorus channels, LangChain4j reviewer, JGit versioning.
3. **Sparge Electron removal** — same Electron→Quarkus migration, proven by DraftHouse.
4. **Parent POM inheritance** — convert DraftHouse's flat `server/pom.xml` to inherit from `casehub-parent` with proper module structure (`api/` + `app/`).
