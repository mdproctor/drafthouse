# Adopt casehub-pages Workbench via Quinoa — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace DraftHouse's hand-coded index.html shell with a casehub-pages workbench rendered via `loadSite()`, served through Quarkus Quinoa.

**Architecture:** Quinoa builds a TypeScript entry point (`src/main/webui/src/index.ts`) that imports the four existing Web Component panels, registers them with casehub-pages via `registerPanel()`, constructs a workbench layout using `split()`, `hostPanel()`, and calls `loadSite()`. The existing panels are unchanged — they already implement the `configure(props)` + `connectedCallback()` protocol that `hostPanel` expects. Topbar toggle buttons provide panel show/hide (matching the current UX). Inter-panel communication migrates from the custom `DebateEventBus` singleton to the standard `pages-event` CustomEvent pattern. `UiResource.java` is deleted — Quinoa serves all static assets.

**Tech Stack:** Quarkus Quinoa, esbuild, TypeScript, @casehubio/pages-runtime, @casehubio/pages-ui, @casehubio/pages-component, @casehubio/pages-viz

## Global Constraints

- casehub-pages packages are at version 0.2.0, linked via `file:` paths to `../../../../../../pages/packages/<name>` (relative from `server/runtime/src/main/webui/`)
- Quarkus 3.34.3 — use matching Quinoa extension version from quarkiverse
- Panels stay as vanilla JS (`.js`) — no TypeScript conversion in this issue
- esbuild must NOT tree-shake panel imports (they register custom elements as side effects) — set `"sideEffects": true` in package.json
- esbuild must NOT minify (garden GE-20260629-59c7e6: minification corrupts constants inside template literal HTML strings used by panels for Shadow DOM styles)
- marked.js and highlight.js stay as CDN script tags (panels reference them as globals) — npm migration is a separate issue
- Node.js ≥ 18 and npm are required at build time and test time (Quinoa runs `npm install` + `npm run build` during Quarkus startup; CI environments must have Node.js installed)
- Port remains 9001 (dev) / 9002 (test)
- `%test.ui.dir` property is removed — Quinoa serves test assets from its build output

---

### Task 1: Wire Quinoa into Maven build and create webui scaffold

**Files:**
- Modify: `server/runtime/pom.xml` — add quarkus-quinoa dependency
- Modify: `server/runtime/src/main/resources/application.properties` — add Quinoa config, remove `ui.dir`
- Create: `server/runtime/src/main/webui/package.json`
- Create: `server/runtime/src/main/webui/tsconfig.json`
- Create: `server/runtime/src/main/webui/esbuild.config.mjs`
- Create: `server/runtime/src/main/webui/public/index.html` — HTML shell with rendering pipeline
- Create: `server/runtime/src/main/webui/src/index.ts` — minimal entry point
- Create: `server/runtime/src/main/webui/.gitignore` — exclude dist/ from git
- Modify: `ARC42STORIES.MD` — update §2 constraint for Quinoa
- Delete: `server/runtime/src/main/java/io/casehub/drafthouse/UiResource.java` — replaced by Quinoa

**Interfaces:**
- Consumes: nothing (first task)
- Produces: Quinoa build pipeline producing `dist/index.html` + `dist/app.js`, served by Quarkus at `/`

- [ ] **Step 1: Add quarkus-quinoa dependency to pom.xml**

Add to `server/runtime/pom.xml` in the `<dependencies>` section, after the Quarkus dependencies:

```xml
<dependency>
    <groupId>io.quarkiverse.quinoa</groupId>
    <artifactId>quarkus-quinoa</artifactId>
    <version>2.5.2</version>
</dependency>
```

> **Note:** The version is hardcoded because `casehubio/parent` BOM does not manage Quinoa. Moving version management to the parent BOM is a follow-up coordination task.

- [ ] **Step 2: Update application.properties**

In `server/runtime/src/main/resources/application.properties`, remove the `ui.dir` default and `%test.ui.dir` line. Add Quinoa configuration:

```properties
# Quinoa frontend build
quarkus.quinoa.build-dir=dist
quarkus.quinoa.enable-spa-routing=true
quarkus.quinoa.package-manager-install=true
```

Remove these lines:
```properties
# (delete) %test.ui.dir=../..
```

- [ ] **Step 3: Create package.json**

Create `server/runtime/src/main/webui/package.json`:

```json
{
  "name": "drafthouse-webui",
  "private": true,
  "sideEffects": true,
  "scripts": {
    "build": "node esbuild.config.mjs",
    "dev": "node esbuild.config.mjs --watch",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@casehubio/pages-runtime": "file:../../../../../../pages/packages/pages-runtime",
    "@casehubio/pages-ui": "file:../../../../../../pages/packages/pages-ui",
    "@casehubio/pages-component": "file:../../../../../../pages/packages/pages-component",
    "@casehubio/pages-viz": "file:../../../../../../pages/packages/pages-viz"
  },
  "devDependencies": {
    "esbuild": "^0.25.0",
    "typescript": "^5.6.0"
  }
}
```

The `file:` paths resolve from `server/runtime/src/main/webui/` up to the casehub root, then into `pages/packages/`. `"sideEffects": true` prevents esbuild from tree-shaking panel imports that call `customElements.define()`.

- [ ] **Step 4: Create tsconfig.json**

Create `server/runtime/src/main/webui/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "outDir": "dist",
    "declaration": false,
    "noEmit": true,
    "allowJs": true
  },
  "include": ["src"]
}
```

`allowJs: true` lets TypeScript check `.js` panel imports without requiring conversion.

- [ ] **Step 5: Create esbuild.config.mjs**

Create `server/runtime/src/main/webui/esbuild.config.mjs`:

```javascript
import { build, context } from "esbuild";
import { copyFileSync, mkdirSync } from "fs";

const isWatch = process.argv.includes("--watch");

mkdirSync("dist", { recursive: true });
copyFileSync("public/index.html", "dist/index.html");

const options = {
  entryPoints: ["src/index.ts"],
  bundle: true,
  outfile: "dist/app.js",
  format: "esm",
  target: "es2020",
  minify: false,
  sourcemap: true,
};

if (isWatch) {
  const ctx = await context(options);
  await ctx.watch();
  console.log("Watching for changes...");
} else {
  await build(options);
}
```

`minify: false` is a DraftHouse-specific override — panels use template literal HTML strings for Shadow DOM styles, and esbuild minification corrupts string constants in these literals (GE-20260629-59c7e6). Other Quinoa host apps should use `minify: !isWatch` per the convention template. The `copyFileSync` copies `public/index.html` into `dist/` because DraftHouse has a custom HTML file with CDN-loaded libraries and syntax highlighting configuration — the convention template relies on Quinoa's default HTML serving instead.

- [ ] **Step 6: Create index.html with rendering pipeline**

Create `server/runtime/src/main/webui/public/index.html`. This includes the CDN-loaded rendering libraries (marked.js + highlight.js with pinned versions), DRL syntax highlighting registration, `marked.use()` renderer configuration, and inline design tokens from `styles.css`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>DraftHouse</title>
    <!-- Pinned CDN libraries — panels reference these as globals -->
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/marked/9.1.6/marked.min.js"></script>
    <script>
        // Register DRL language for highlight.js
        hljs.registerLanguage('drl', function(hljs) {
            return {
                keywords: 'rule when then end package import function global declare',
                contains: [hljs.C_LINE_COMMENT_MODE, hljs.C_BLOCK_COMMENT_MODE, hljs.QUOTE_STRING_MODE, hljs.NUMBER_MODE]
            };
        });
        // Configure marked renderer to use hljs for code blocks
        marked.use({
            renderer: {
                code({ text, lang }) {
                    const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext';
                    const highlighted = hljs.highlight(text, { language }).value;
                    return `<pre><code class="hljs language-${language}">${highlighted}</code></pre>`;
                }
            }
        });
    </script>
    <style>
        :root {
            --bg: #faf6f1; --chrome: #eee8e0; --border: #d5cec6;
            --ink: #3b3228; --sepia: #5c4a3a; --muted: #8a7e74;
            --accent: #8b5e3c; --accent-light: #c9a882;
            --diff-del: #f8d7d7; --diff-ins: #d7f8d7;
            --diff-del-text: #a33; --diff-ins-text: #3a3;
            --topbar-bg: #eee8e0; --topbar-fg: #3b3228;
            --statusbar-bg: #e8e2da; --statusbar-fg: #8a7e74;
        }
        body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); color: var(--ink); }
        #app { width: 100vw; height: 100vh; display: flex; flex-direction: column; }
        [data-component-type="rows"] { flex: 1; }
        [data-component-type="rows"] > [data-slot] { display: flex; flex-direction: column; flex: 1; min-height: 0; }
        [data-component-type="split"] { flex: 1; min-height: 0; }
        button { cursor: pointer; border: 1px solid var(--border); background: var(--chrome); color: var(--ink); padding: 2px 8px; border-radius: 3px; font-size: 12px; }
        button:hover { background: var(--accent-light); }
        button.active { background: var(--accent); color: #fff; }
        button:disabled { opacity: 0.4; cursor: default; }
        .hidden { display: none !important; }
        kbd { display: inline-block; padding: 1px 5px; border: 1px solid var(--border); border-radius: 3px; background: var(--chrome); font-family: monospace; font-size: 11px; }
    </style>
</head>
<body>
    <div id="app"></div>
    <script type="module" src="app.js"></script>
</body>
</html>
```

- [ ] **Step 7: Create minimal index.ts**

Create `server/runtime/src/main/webui/src/index.ts`:

```typescript
const app = document.getElementById("app")!;
app.textContent = "Quinoa works";
```

- [ ] **Step 8: Create webui .gitignore**

Create `server/runtime/src/main/webui/.gitignore`:

```
dist/
```

(`node_modules/` is already covered by the project root `.gitignore`; `dist/` is not — esbuild output would be staged by `git add -A` without this.)

- [ ] **Step 9: Delete UiResource.java**

Delete `server/runtime/src/main/java/io/casehub/drafthouse/UiResource.java` BEFORE building — its catch-all `@Path("{path:.+}")` route would conflict with Quinoa's static serving and produce ambiguous routing during verification. The `/api/*` routes are served by other JAX-RS resources and are unaffected.

- [ ] **Step 10: Update ARC42STORIES.MD §2**

In `ARC42STORIES.MD`, update the §2 Constraints table entry from:
> Browser-only UI | No Electron, no npm build step

to:
> Browser-only UI | No Electron; npm build via Quinoa at compile time — no Node.js at runtime

This fulfills issue #75 step 1 (absorbs #74).

- [ ] **Step 11: Build and verify**

Run:
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests
```
Expected: BUILD SUCCESS. Quinoa runs `npm install` + `npm run build` during the Maven build.

Run the server:
```bash
java -jar server/runtime/target/drafthouse-server-runner.jar
```

Open `http://localhost:9001/` — should show "Quinoa works". Verify `http://localhost:9001/api/ping` returns 200 (SPA routing passes `/api/*` to JAX-RS).

- [ ] **Step 12: Commit**

```bash
git add server/runtime/pom.xml server/runtime/src/main/resources/application.properties server/runtime/src/main/webui/ server/runtime/src/main/java/io/casehub/drafthouse/UiResource.java ARC42STORIES.MD
git commit -m "feat: wire Quinoa + casehub-pages build pipeline, delete UiResource

Refs #75"
```

---

### Task 2: Move panels and build workbench layout

**Files:**
- Move: `panels/drafthouse-diff.js` → `server/runtime/src/main/webui/src/panels/drafthouse-diff.js`
- Move: `panels/drafthouse-debate.js` → `server/runtime/src/main/webui/src/panels/drafthouse-debate.js`
- Move: `panels/drafthouse-review-tracker.js` → `server/runtime/src/main/webui/src/panels/drafthouse-review-tracker.js`
- Move: `panels/drafthouse-context-gauge.js` → `server/runtime/src/main/webui/src/panels/drafthouse-context-gauge.js`
- Move: `panels/debate-event-bus.js` → `server/runtime/src/main/webui/src/panels/debate-event-bus.js` (temporary — replaced in Task 3)
- Modify: `server/runtime/src/main/webui/src/index.ts` — full workbench layout
- Delete: `panels/panel-registry.js` — replaced by `registerPanel()`

**Interfaces:**
- Consumes: Quinoa build pipeline from Task 1; panel Web Components with `configure(props)` + `connectedCallback()` protocol
- Produces: Full workbench layout with all four panels rendered via `loadSite()`; session discovery and cross-panel event routing operational

- [ ] **Step 1: Move panel files to webui/src/panels/**

Move all panel JS files from the project root `panels/` directory into `server/runtime/src/main/webui/src/panels/`. This includes the four panel components and `debate-event-bus.js`. Do NOT move `panel-registry.js` — it is replaced by `registerPanel()`.

The panel files need one modification each: their `import` paths for `debate-event-bus.js` must be updated from `'./debate-event-bus.js'` to match the new relative location (which stays the same since all files move together — `'./debate-event-bus.js'` remains correct).

- [ ] **Step 2: Write the full workbench entry point**

Replace `server/runtime/src/main/webui/src/index.ts` with the full workbench:

```typescript
import { registerPanel, loadSite } from "@casehubio/pages-runtime";
import {
  rows, split, hostPanel, withId, html,
} from "@casehubio/pages-ui";

// Import panels — side-effect imports register custom elements
import "./panels/drafthouse-diff.js";
import "./panels/drafthouse-debate.js";
import "./panels/drafthouse-review-tracker.js";
import "./panels/drafthouse-context-gauge.js";

// Register panel types with pages
registerPanel("diff-viewer", "drafthouse-diff");
registerPanel("debate-feed", "drafthouse-debate");
registerPanel("review-tracker", "drafthouse-review-tracker");
registerPanel("context-gauge", "drafthouse-context-gauge");

// Parse URL params
const params = new URLSearchParams(window.location.search);
const pathA = params.get("a") || "";
const pathB = params.get("b") || "";
const debateParam = params.get("debate");

// Build workbench layout
const workbench = rows(
  // Topbar — all action controls; matches current index.html feature set
  html(`<div id="topbar" style="display:flex; align-items:center; gap:8px; padding:4px 12px; background:var(--topbar-bg); color:var(--topbar-fg);">
    <strong>DraftHouse</strong>
    <button id="btn-sync" title="Toggle scroll sync">⇅ Sync</button>
    <button id="btn-swap" title="Swap panels" disabled>⇄ Swap</button>
    <button id="btn-view-mode" title="Toggle unified/split view">View: Split</button>
    <button id="btn-prev" title="Previous diff (p)" disabled>◀</button>
    <span id="diff-counter">— / —</span>
    <button id="btn-next" title="Next diff (n)" disabled>▶</button>
    <span id="diff-summary" style="font-size:11px; color:var(--muted);"></span>
    <span id="diff-legend" style="font-size:11px; display:flex; gap:6px; align-items:center;">
      <span style="display:inline-block;width:10px;height:10px;background:var(--diff-del);border:1px solid var(--diff-del-text);border-radius:2px;" class="legend-del"></span> A
      <span style="display:inline-block;width:10px;height:10px;background:var(--diff-ins);border:1px solid var(--diff-ins-text);border-radius:2px;" class="legend-ins"></span> B
    </span>
    <span id="doc-badge" style="display:none; cursor:pointer;">📄 <span id="doc-count">0</span></span>
    <span style="flex:1" id="topbar-spacer"></span>
    <button id="btn-debate" class="active" title="Toggle debate panel">💬 Debate</button>
    <button id="btn-review" class="active" title="Toggle review panel">📋 Review</button>
  </div>`),

  // Main content — split replaces columns+dockBar (see R1-04)
  split("horizontal", [
    hostPanel("diff-viewer", { pathA, pathB }),
    split("vertical", [
      withId("debate", hostPanel("debate-feed", {})),
      withId("review", hostPanel("review-tracker", {})),
    ], { ratio: [60, 40] }),
  ], { ratio: [60, 40] }),

  // Status bar — passive status info separated from action controls (workbench convention)
  html(`<div class="statusbar" style="padding:2px 12px; font-size:11px; background:var(--statusbar-bg); color:var(--statusbar-fg);">
    <drafthouse-context-gauge></drafthouse-context-gauge>
  </div>`),
);

await loadSite(document.getElementById("app")!, workbench);

// ── Session discovery ─────────────────────────────────────────────────

import { debateEventBus } from "./panels/debate-event-bus.js";

function connectDebateSession(sessionId: string): void {
  debateEventBus.connect(sessionId);

  const debateEl = document.querySelector("drafthouse-debate") as any;
  const reviewEl = document.querySelector("drafthouse-review-tracker") as any;
  const diffEl = document.querySelector("drafthouse-diff") as any;

  if (debateEl) debateEl.configure({ debateSessionId: sessionId });
  if (reviewEl) reviewEl.configure({ debateSessionId: sessionId });

  // Fetch initial documents and comparison
  fetch(`/api/debate/${sessionId}/documents`)
    .then(r => r.json())
    .then((data: any) => {
      if (data.comparison) {
        if (data.comparison.a && diffEl) diffEl.loadFile("a", data.comparison.a);
        if (data.comparison.b && diffEl) diffEl.loadFile("b", data.comparison.b);
      }
    })
    .catch(() => {});

  // Subscribe to metadata events for document/comparison changes
  debateEventBus.subscribe({
    onEntries: () => {},
    onMeta: (data: any) => {
      if (data.type === "comparison-changed" && diffEl) {
        if (data.a) diffEl.loadFile("a", data.a);
        if (data.b) diffEl.loadFile("b", data.b);
      }
    },
  });
}

function startSessionDiscovery(): void {
  const interval = setInterval(async () => {
    try {
      const res = await fetch("/api/debate/sessions");
      const sessions = await res.json();
      if (sessions.length === 1) {
        clearInterval(interval);
        connectDebateSession(sessions[0].id);
      } else if (sessions.length > 1) {
        clearInterval(interval);
        showSessionPicker(sessions);
      }
    } catch {
      // Server not ready yet
    }
  }, 5000);
}

if (debateParam) {
  connectDebateSession(debateParam);
} else {
  startSessionDiscovery();
}

// ── Topbar wiring ─────────────────────────────────────────────────────

const diffEl = document.querySelector("drafthouse-diff") as any;

document.getElementById("btn-sync")?.addEventListener("click", () => {
  if (diffEl) {
    const synced = diffEl.toggleSync();
    document.getElementById("btn-sync")?.classList.toggle("active", synced);
  }
});

document.getElementById("btn-swap")?.addEventListener("click", () => {
  diffEl?.swapPanels();
});

document.getElementById("btn-prev")?.addEventListener("click", () => {
  diffEl?.prevDiff();
  updateDiffUI();
});

document.getElementById("btn-next")?.addEventListener("click", () => {
  diffEl?.nextDiff();
  updateDiffUI();
});

document.getElementById("btn-view-mode")?.addEventListener("click", () => {
  if (!diffEl) return;
  const next = diffEl.viewMode === "split" ? "unified" : "split";
  diffEl.setViewMode(next);
  const btn = document.getElementById("btn-view-mode");
  if (btn) btn.textContent = `View: ${next.charAt(0).toUpperCase() + next.slice(1)}`;
});

function updateDiffUI(): void {
  if (!diffEl) return;
  const s = diffEl.getDiffSummary();
  const counter = document.getElementById("diff-counter");
  if (counter) counter.textContent = s.totalDiffs > 0
    ? `${s.currentIdx + 1} / ${s.totalDiffs}`
    : "— / —";
  const prev = document.getElementById("btn-prev") as HTMLButtonElement | null;
  const next = document.getElementById("btn-next") as HTMLButtonElement | null;
  if (prev) prev.disabled = s.currentIdx <= 0;
  if (next) next.disabled = s.currentIdx >= s.totalDiffs - 1;
  // Diff summary counts
  const summaryEl = document.getElementById("diff-summary");
  if (summaryEl && s.totalDiffs > 0) {
    summaryEl.textContent = `~${s.modified || 0} −${s.deleted || 0} +${s.inserted || 0}`;
  }
  // Enable swap button once diffs are loaded
  const swapBtn = document.getElementById("btn-swap") as HTMLButtonElement | null;
  if (swapBtn && s.totalDiffs > 0) swapBtn.disabled = false;
}

document.addEventListener("diff-updated", () => updateDiffUI());

// ── Cross-panel event routing ─────────────────────────────────────────

document.addEventListener("point-selected", ((e: CustomEvent) => {
  const { location } = e.detail;
  if (location && diffEl) diffEl.scrollToLocation(location);
}) as EventListener);

document.addEventListener("selection-changed", ((e: CustomEvent) => {
  const { side, startLine, endLine, selectedText } = e.detail;
  if (!selectedText) return;
  const sessionId = debateEventBus.sessionId;
  if (!sessionId) return;
  fetch(`/api/debate/${sessionId}/selection`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ side, startLine, endLine, selectedText }),
  }).catch(() => {});
}) as EventListener);

// ── Keyboard shortcuts ────────────────────────────────────────────────

function isEditable(el: Element | null): boolean {
  if (!el) return false;
  const tag = el.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA") return true;
  if ((el as HTMLElement).isContentEditable) return true;
  const root = el.getRootNode();
  if (root instanceof ShadowRoot) return isEditable(root.host);
  return false;
}

document.addEventListener("keydown", (e: KeyboardEvent) => {
  if (isEditable(document.activeElement)) return;
  switch (e.key) {
    case "n": diffEl?.nextDiff(); updateDiffUI(); break;
    case "p": diffEl?.prevDiff(); updateDiffUI(); break;
    case "u":
      document.getElementById("btn-view-mode")?.click();
      break;
    case "s":
      if (e.metaKey || e.ctrlKey) {
        e.preventDefault();
        document.getElementById("btn-sync")?.click();
      }
      break;
    case "?": {
      const overlay = document.getElementById("shortcuts-overlay");
      if (overlay) overlay.classList.toggle("hidden");
      const backdrop = document.getElementById("shortcuts-backdrop");
      if (backdrop) backdrop.classList.toggle("hidden");
      break;
    }
    case "Escape": {
      document.getElementById("shortcuts-overlay")?.classList.add("hidden");
      document.getElementById("shortcuts-backdrop")?.classList.add("hidden");
      break;
    }
  }
});

// ── Keyboard shortcuts overlay ───────────────────────────────────────
document.body.insertAdjacentHTML("beforeend", `
  <div id="shortcuts-backdrop" class="hidden" style="position:fixed;inset:0;background:rgba(0,0,0,0.3);z-index:999;"></div>
  <div id="shortcuts-overlay" class="hidden" style="position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:24px;z-index:1000;min-width:300px;">
    <h3 style="margin:0 0 12px;">Keyboard Shortcuts</h3>
    <div><kbd>n</kbd> Next diff</div>
    <div><kbd>p</kbd> Previous diff</div>
    <div><kbd>u</kbd> Toggle unified/split</div>
    <div><kbd>Ctrl+S</kbd> Toggle sync</div>
    <div><kbd>?</kbd> Toggle this overlay</div>
    <div><kbd>Esc</kbd> Close overlay</div>
  </div>
`);
document.getElementById("shortcuts-backdrop")?.addEventListener("click", () => {
  document.getElementById("shortcuts-overlay")?.classList.add("hidden");
  document.getElementById("shortcuts-backdrop")?.classList.add("hidden");
});

// ── Panel toggle buttons ─────────────────────────────────────────────
// Dispatch pages-dock-toggle events — loadSite() already handles the DOM:
// hides/shows slot containers (the actual flex children), adjacent drag
// handles, and collapses the parent split when all children are hidden.
// See pages-runtime/src/site.ts:775-825.
let debateVisible = true;
let reviewVisible = true;

function updatePanelVisibility(): void {
  const app = document.getElementById("app")!;
  app.dispatchEvent(new CustomEvent("pages-dock-toggle", {
    bubbles: true, detail: { panelId: "debate", visible: debateVisible },
  }));
  app.dispatchEvent(new CustomEvent("pages-dock-toggle", {
    bubbles: true, detail: { panelId: "review", visible: reviewVisible },
  }));
  document.getElementById("btn-debate")?.classList.toggle("active", debateVisible);
  document.getElementById("btn-review")?.classList.toggle("active", reviewVisible);
}

document.getElementById("btn-debate")?.addEventListener("click", () => {
  debateVisible = !debateVisible;
  updatePanelVisibility();
});
document.getElementById("btn-review")?.addEventListener("click", () => {
  reviewVisible = !reviewVisible;
  updatePanelVisibility();
});

// ── Session discovery (multi-session picker) ─────────────────────────

function showSessionPicker(sessions: any[]): void {
  const picker = document.createElement("div");
  picker.id = "session-picker";
  picker.style.cssText = "position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,0.4);z-index:1000;";
  picker.innerHTML = `<div style="background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:24px;min-width:300px;">
    <h3 style="margin:0 0 12px;">Select Session</h3>
    ${sessions.map(s => `<button class="session-btn" data-id="${s.id}" style="display:block;width:100%;margin:4px 0;padding:8px;text-align:left;">${s.id}</button>`).join("")}
  </div>`;
  document.body.appendChild(picker);
  picker.addEventListener("click", (e) => {
    const btn = (e.target as HTMLElement).closest(".session-btn") as HTMLElement | null;
    if (btn) { picker.remove(); connectDebateSession(btn.dataset.id!); }
  });
}

// Suppress browser drag/drop
document.addEventListener("dragover", e => e.preventDefault());
document.addEventListener("drop", e => e.preventDefault());
```

- [ ] **Step 3: Delete old shell files**

Delete these files from the project root — they are fully replaced:
- `index.html` — replaced by `webui/public/index.html` + `index.ts`
- `styles.css` — design tokens moved to new index.html; layout replaced by pages
- `panels/panel-registry.js` — replaced by `registerPanel()`

Keep the `panels/` directory contents intact until the move in Step 1 is verified. After Step 1 copies are confirmed, delete the originals.

- [ ] **Step 4: Build and verify panels render**

Run:
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests
java -jar server/runtime/target/drafthouse-server-runner.jar
```

Open `http://localhost:9001/?a=/path/to/sample-a.md&b=/path/to/sample-b.md`. Verify:
- Diff panel renders with side-by-side markdown
- Topbar buttons (sync, swap, prev/next, view mode) work
- Debate and Review panels visible, toggle buttons in topbar work
- Keyboard shortcuts (n/p/u) work

- [ ] **Step 5: Run E2E tests and fix selectors**

Run:
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Tests that interact with custom element selectors (`drafthouse-diff`, `drafthouse-debate`) and shadow DOM will pass without changes. Tests that depend on shell DOM elements need verification:

**Tests that should pass without changes:**
- `KeyboardShortcutsE2ETest` (3 tests) — `#shortcuts-overlay` and `#shortcuts-backdrop` preserved in workbench
- `DiffLegendE2ETest` (3 tests) — `#diff-legend`, `.legend-del`, `.legend-ins` preserved in topbar
- `DiffSummaryE2ETest` (4 tests) — `#diff-summary` preserved in topbar
- `NavigationE2ETest` (2 tests) — counter format `"N / M"` (with spaces) matches test assertions

**Tests to DELETE:**
- `HappyPathE2ETest.panelsDirectoryServesJavaScript` — panels are bundled into `app.js`, not served as individual JS files

**Tests to verify and fix if needed:**
- Any test using `#topbar`, `#btn-sync`, `#btn-swap`, `#btn-prev`, `#btn-next` — IDs are preserved
- Tests using `#doc-badge` — element is present but starts hidden
- Tests checking panel visibility — toggle mechanism changed from direct DOM to `data-component-id` selectors

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: build workbench layout with casehub-pages split/hostPanel

Replace index.html shell with loadSite() workbench. Move panels to
webui/src/panels/. Delete panel-registry.js, old shell files.

Refs #75"
```

---

### Task 3: Migrate DebateEventBus to pages-event

**Files:**
- Create: `server/runtime/src/main/webui/src/sse-bridge.ts` — SSE connection dispatching pages-events
- Modify: `server/runtime/src/main/webui/src/panels/drafthouse-debate.js` — subscribe via pages-event
- Modify: `server/runtime/src/main/webui/src/panels/drafthouse-review-tracker.js` — subscribe via pages-event
- Modify: `server/runtime/src/main/webui/src/panels/drafthouse-context-gauge.js` — subscribe via pages-event
- Modify: `server/runtime/src/main/webui/src/index.ts` — use SSE bridge instead of debateEventBus
- Delete: `server/runtime/src/main/webui/src/panels/debate-event-bus.js`

**Interfaces:**
- Consumes: Working workbench layout from Task 2; SSE endpoint at `/api/debate/{id}/events`
- Produces: All inter-panel communication via `pages-event` CustomEvents; no singleton event bus

- [ ] **Step 1: Create SSE bridge**

Create `server/runtime/src/main/webui/src/sse-bridge.ts`:

```typescript
let eventSource: EventSource | null = null;
let currentSessionId: string | null = null;

export function connectSSE(sessionId: string): void {
  if (currentSessionId === sessionId && eventSource) return;
  disconnectSSE();
  currentSessionId = sessionId;
  eventSource = new EventSource(`/api/debate/${encodeURIComponent(sessionId)}/events`);

  eventSource.onmessage = (msg) => {
    let data: unknown;
    try { data = JSON.parse(msg.data); } catch { return; }
    if (data === "heartbeat" || (data as any)?.type === "heartbeat") return;

    if (Array.isArray(data)) {
      document.dispatchEvent(new CustomEvent("pages-event", {
        bubbles: true, composed: true,
        detail: { topic: "debate-entries", payload: data },
      }));
    } else if ((data as any).type) {
      document.dispatchEvent(new CustomEvent("pages-event", {
        bubbles: true, composed: true,
        detail: { topic: (data as any).type, payload: data },
      }));
    }
  };

  eventSource.onerror = () => {
    document.dispatchEvent(new CustomEvent("pages-event", {
      bubbles: true, composed: true,
      detail: { topic: "sse-reconnect", payload: {} },
    }));
  };
}

export function disconnectSSE(): void {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  currentSessionId = null;
}

export function getSessionId(): string | null {
  return currentSessionId;
}
```

- [ ] **Step 2: Update drafthouse-debate.js**

In `server/runtime/src/main/webui/src/panels/drafthouse-debate.js`, remove the `import { debateEventBus }` line. Replace the `#initialize()` method's event bus subscription with pages-event listeners:

Remove:
```javascript
import { debateEventBus } from './debate-event-bus.js';
```

In `#initialize()`, replace the `debateEventBus.subscribe(...)` call with:
```javascript
document.addEventListener('pages-event', (e) => {
    const { topic, payload } = e.detail;
    if (topic === 'debate-entries') {
        this.#entries.push(...payload);
        this.#render();
        // auto-scroll logic unchanged
    } else if (topic === 'sse-reconnect') {
        this.#entries = [];
        this.#render();
    }
});
```

- [ ] **Step 3: Update drafthouse-review-tracker.js**

Same pattern — remove `debateEventBus` import, replace subscription with:
```javascript
document.addEventListener('pages-event', (e) => {
    const { topic, payload } = e.detail;
    if (topic === 'debate-entries') {
        this.#entries.push(...payload);
        this.#render();
    } else if (topic === 'sse-reconnect') {
        this.#entries = [];
        this.#render();
    }
});
```

- [ ] **Step 4: Update drafthouse-context-gauge.js**

Remove `debateEventBus` import, replace subscription with:
```javascript
document.addEventListener('pages-event', (e) => {
    const { topic, payload } = e.detail;
    if (topic === 'context-usage') {
        // existing onMeta handler logic for context-usage
        // (windowSizeChars, percentage, fill width/color, threshold)
    } else if (topic === 'sse-reconnect') {
        this.#reset();
    }
});
```

- [ ] **Step 5: Update index.ts to use SSE bridge**

In `server/runtime/src/main/webui/src/index.ts`:

Replace:
```typescript
import { debateEventBus } from "./panels/debate-event-bus.js";
```
with:
```typescript
import { connectSSE, disconnectSSE, getSessionId } from "./sse-bridge.js";
```

In `connectDebateSession()`:
- Replace `debateEventBus.connect(sessionId)` with `connectSSE(sessionId)`
- Remove the `debateEventBus.subscribe(...)` call for metadata — the SSE bridge dispatches pages-events automatically
- Add a document-level pages-event listener for `comparison-changed`:
```typescript
document.addEventListener("pages-event", ((e: CustomEvent) => {
    const { topic, payload } = e.detail;
    if (topic === "comparison-changed" && diffEl) {
        if (payload.a) diffEl.loadFile("a", payload.a);
        if (payload.b) diffEl.loadFile("b", payload.b);
    }
}) as EventListener);
```

In the `selection-changed` handler:
- Replace `debateEventBus.sessionId` with `getSessionId()`

- [ ] **Step 6: Delete debate-event-bus.js**

Delete `server/runtime/src/main/webui/src/panels/debate-event-bus.js`.

- [ ] **Step 7: Build and verify event flow**

Run:
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

All E2E tests should pass. The debate panel, review tracker, and context gauge should receive events via pages-event. Cross-panel coordination (point-selected → diff scroll, selection-changed → REST POST) should work as before.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: migrate DebateEventBus to pages-event pattern

Replace singleton event bus with SSE bridge dispatching pages-event
CustomEvents. Each panel subscribes via document.addEventListener
instead of debateEventBus.subscribe.

Refs #75"
```

---

### Task 4: Final cleanup and E2E verification

**Files:**
- Delete: `panels/` directory (if any originals remain after Task 2)
- Delete: `styles.css` (project root — design tokens migrated in Task 2)
- Modify: E2E tests — remove `panelsDirectoryServesJavaScript`, verify all others pass
- Modify: `server/runtime/src/main/webui/public/index.html` — add Electron IPC support if needed

**Interfaces:**
- Consumes: Complete workbench from Tasks 1-3
- Produces: Clean project with no legacy UI files; all E2E tests green

- [ ] **Step 1: Delete remaining legacy files**

Remove from the project root:
- `panels/` directory (entire directory — all files moved to `webui/src/panels/`)
- `styles.css` (design tokens already in new index.html)
- `index.html` (already deleted in Task 2, verify)

- [ ] **Step 2: Remove panelsDirectoryServesJavaScript test**

In `server/runtime/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java`, delete the `panelsDirectoryServesJavaScript` test method. Panels are bundled into `app.js` now — individual JS files are not served.

- [ ] **Step 3: Run full E2E test suite**

Run:
```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: all tests pass. If any test fails on a selector, fix it:
- `#topbar`, `#btn-sync`, `#btn-swap`, `#btn-prev`, `#btn-next` — these IDs are preserved in the `html()` topbar component, so they should still work
- `[data-diff-chunk]` — inside shadow DOM, unchanged
- `drafthouse-diff`, `drafthouse-debate`, `drafthouse-review-tracker` — custom element tags, unchanged

- [ ] **Step 4: Verify Electron compatibility**

If Electron support is needed, add the IPC bridge to `index.ts`:

```typescript
declare global {
  interface Window {
    compare?: {
      onInitConfig: (callback: (cfg: any) => void) => void;
      onInitFiles: (callback: (a: string, b: string) => void) => void;
    };
  }
}

if (window.compare) {
  window.compare.onInitConfig((cfg: any) => {
    const diffEl = document.querySelector("drafthouse-diff") as any;
    if (diffEl) diffEl.configure({ apiPort: cfg.apiPort });
  });
  window.compare.onInitFiles((a: string, b: string) => {
    const diffEl = document.querySelector("drafthouse-diff") as any;
    if (diffEl) {
      diffEl.loadFile("a", a);
      diffEl.loadFile("b", b);
    }
  });
}
```

- [ ] **Step 5: Update CLAUDE.md key directories**

Update the Key Directories table in `CLAUDE.md` to reflect the new file structure:
- Remove entries for `index.html`, `styles.css`, `panels/`, `panels/panel-registry.js`, `panels/debate-event-bus.js`
- Add entries for `server/runtime/src/main/webui/` and its contents
- Update the Architecture diagram to show Quinoa instead of UiResource

- [ ] **Step 6: File deferred feature issue**

Create a GitHub issue for the document badge dropdown:

```bash
gh issue create --title "feat: implement document badge dropdown for A/B slot assignment" \
  --body "The \`#doc-badge\` element is present in the topbar but the full dropdown with document assignment buttons (~60 lines of DOM manipulation from the original index.html) was deferred during the Quinoa migration (#75). Implement the dropdown using pages components."
```

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "chore: clean up legacy UI files, update CLAUDE.md for Quinoa

Delete root-level panels/, styles.css, index.html. Remove
panelsDirectoryServesJavaScript test. Update Key Directories
and Architecture sections.

Closes #75"
```

---

### Deferred Features

These features from the original `index.html` are not included in this migration. A GitHub issue is filed in Task 4 Step 6:

1. **Document badge dropdown** (A/B slot assignment UI) — the `#doc-badge` element is present in the topbar, but the full dropdown with document assignment buttons (~60 lines of DOM manipulation) is deferred. File as a drafthouse issue.

Panel drag-to-resize is NOT deferred — the `split()` component already implements drag-to-resize via `wireSplit()` (inserts drag handle elements between slot containers) and `attachDragHandler()` (full mousedown → mousemove → mouseup dragging with `minSizes` support). See `pages-component/src/renderer/interactive.ts:668-739`.
