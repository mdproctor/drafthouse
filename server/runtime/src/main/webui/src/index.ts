import { registerPanel, loadSite } from "@casehubio/pages-runtime";
import {
  rows, split, hostPanel, withId, html,
} from "@casehubio/pages-ui";

// Import panels — side-effect imports register custom elements
import "./panels/document-diff.js";
import "./panels/channel-feed.js";
import "./panels/review-tracker.js";
import "./panels/context-gauge.js";
import "./panels/doc-picker.js";
import "./panels/document-timeline.js";
import "./panels/workspace-status.js";
import "@casehubio/pages-component-terminal";

// ── Electron IPC Bridge ──────────────────────────────────────────────────

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
    const diffEl = document.querySelector("document-diff") as any;
    if (diffEl) diffEl.configure({ apiPort: cfg.port });
  });
  window.compare.onInitFiles((a: string, b: string) => {
    const diffEl = document.querySelector("document-diff") as any;
    if (diffEl) {
      diffEl.loadFile("a", a);
      diffEl.loadFile("b", b);
    }
  });
}

// Register panel types with pages
registerPanel("diff-viewer", "document-diff");
registerPanel("debate-feed", "channel-feed");
registerPanel("review-tracker", "review-tracker");
registerPanel("context-gauge", "context-gauge");
registerPanel("document-timeline", "document-timeline");
registerPanel("terminal", "pages-component-terminal");

// Parse URL params
const params = new URLSearchParams(window.location.search);
const pathA = params.get("a") || "";
const pathB = params.get("b") || "";
const debateParam = params.get("debate");
const mode = params.get("mode");

// Build workbench layout based on mode
const workbench = mode === "brainstorm" ? buildBrainstormLayout() : rows(
  // Topbar — all action controls; matches current index.html feature set
  html(`<div id="topbar" style="display:flex; align-items:center; gap:8px; padding:4px 12px; background:var(--topbar-bg); color:var(--topbar-fg);">
    <strong>DraftHouse</strong>
    <button id="btn-sync" class="active" title="Toggle scroll sync">⇅ Sync</button>
    <button id="btn-swap" title="Swap panels" disabled>⇄ Swap</button>
    <button id="btn-view-mode" title="Toggle split/unified view (u)">⫏ Split</button>
    <button id="btn-prev" title="Previous diff (p)" disabled>◀</button>
    <span id="diff-counter">— / —</span>
    <button id="btn-next" title="Next diff (n)" disabled>▶</button>
    <span id="diff-summary" style="font-size:11px; color:var(--muted);"></span>
    <span id="diff-legend" style="font-size:11px; display:flex; gap:6px; align-items:center;">
      <span style="display:inline-block;width:10px;height:10px;background:var(--diff-del);border:1px solid var(--diff-del-text);border-radius:2px;" class="legend-del"></span> A
      <span style="display:inline-block;width:10px;height:10px;background:var(--diff-ins);border:1px solid var(--diff-ins-text);border-radius:2px;" class="legend-ins"></span> B
    </span>
    <doc-picker></doc-picker>
    <workspace-status></workspace-status>
    <span style="flex:1" id="topbar-spacer"></span>
    <button id="btn-debate" class="active" title="Toggle debate panel">💬 Debate</button>
    <button id="btn-review" class="active" title="Toggle review panel">📋 Review</button>
  </div>`),

  // Main content — split replaces columns+dockBar (see R1-04)
  split("horizontal", [
    rows(
      hostPanel("document-timeline", { sessionId: debateParam || "" }),
      hostPanel("diff-viewer", { pathA, pathB }),
    ),
    split("vertical", [
      withId("debate", hostPanel("debate-feed", {})),
      withId("review", hostPanel("review-tracker", {})),
    ], { ratio: [60, 40] }),
  ], { ratio: [60, 40] }),

  // Status bar — passive status info separated from action controls (workbench convention)
  html(`<div class="statusbar" style="padding:2px 12px; font-size:11px; background:var(--statusbar-bg); color:var(--statusbar-fg);">
    <context-gauge></context-gauge>
  </div>`),
);

function buildBrainstormLayout() {
  const wsProto = location.protocol === "https:" ? "wss:" : "ws:";
  const terminalWsUrl = `${wsProto}//${location.host}/api/terminal?cols={cols}&rows={rows}`;

  return rows(
    html(`<div id="topbar" style="display:flex; align-items:center; gap:8px; padding:4px 12px; background:var(--topbar-bg); color:var(--topbar-fg);">
      <strong>DraftHouse</strong>
      <span style="font-size:12px; color:var(--muted);">Brainstorm</span>
      <span style="flex:1"></span>
    </div>`),
    split("horizontal", [
      hostPanel("terminal", { wsUrl: terminalWsUrl }),
    ], { ratio: [100] }),
  );
}

await loadSite(document.getElementById("app")!, workbench);

// ── Terminal injection bridge ────────────────────────────────────────
// Brainstorm panel (future Slice 4) dispatches terminal-inject events;
// this bridge routes them to the terminal component's sendInput() method.
if (mode === "brainstorm") {
  document.addEventListener("terminal-inject", ((e: CustomEvent) => {
    const terminal = document.querySelector("pages-component-terminal") as any;
    if (terminal) terminal.sendInput(e.detail.text);
  }) as EventListener);
}

// ── WebSocket connection ─────────────────────────────────────────────

import { createWebSocketSource } from "@casehubio/pages-data/dist/dataset/external/sources/websocket-source.js";

const wsProto = location.protocol === "https:" ? "wss:" : "ws:";
const wsSource = createWebSocketSource(`${wsProto}//${location.host}/api/ws`, {
  eventTarget: document.documentElement as HTMLElement,
});

// Dummy subscription to establish the connection — listener is a no-op.
// Server silently ignores unrecognized dataset patterns.
const noOpListener = () => {};
const noOpError = () => {};
wsSource.subscribe("_events" as any, { uuid: "_events" as any } as any, noOpListener, noOpError);

let currentSessionId: string | null = null;
let watchedFiles: string[] = [];

function connectDebateSession(sessionId: string): void {
  if (currentSessionId) {
    wsSource.unsubscribe(("debate:" + currentSessionId) as any);
    watchedFiles.forEach(f => wsSource.unsubscribe(("file:" + f) as any));
    watchedFiles = [];
  }
  currentSessionId = sessionId;
  wsSource.subscribe(("debate:" + sessionId) as any,
    { uuid: ("debate:" + sessionId) as any } as any, noOpListener, noOpError);

  const debateEl = document.querySelector("channel-feed") as any;
  const reviewEl = document.querySelector("review-tracker") as any;
  const diffEl = document.querySelector("document-diff") as any;

  if (debateEl) debateEl.configure({ debateSessionId: sessionId });
  if (reviewEl) reviewEl.configure({ debateSessionId: sessionId });

  const timelineEl = document.querySelector("document-timeline") as any;
  if (timelineEl) timelineEl.configure({ sessionId });

  const docPicker = document.querySelector('doc-picker') as any;
  if (docPicker) docPicker.setAttribute('session-id', sessionId);

  // Fetch initial documents — comparison comes via catch-up events
  fetch(`/api/debate/${sessionId}/documents`)
    .then(r => r.json())
    .then((data: any) => {
      if (data.currentComparison) {
        if (data.currentComparison.pathA && diffEl) diffEl.loadFile("a", data.currentComparison.pathA);
        if (data.currentComparison.pathB && diffEl) diffEl.loadFile("b", data.currentComparison.pathB);
        watchFiles(data.currentComparison.pathA, data.currentComparison.pathB);
      }
    })
    .catch(() => {});
}

function watchFiles(...paths: (string | null)[]): void {
  paths.filter(Boolean).forEach(p => {
    if (!watchedFiles.includes(p!)) {
      watchedFiles.push(p!);
      wsSource.subscribe(("file:" + p) as any,
        { uuid: ("file:" + p) as any }, noOpListener, noOpError);
    }
  });
}

export function getSessionId(): string | null {
  return currentSessionId;
}

// Session lifecycle — auto-connect or show picker
document.addEventListener("pages-event", ((e: CustomEvent) => {
  const { topic, payload } = e.detail;
  if (topic === "sessions" && !currentSessionId) {
    if (payload.length === 1) {
      connectDebateSession(payload[0].debateSessionId);
    } else if (payload.length > 1) {
      showSessionPicker(payload);
    }
  }
  if (topic === "session-created" && !currentSessionId) {
    connectDebateSession(payload.debateSessionId);
  }
  if (topic === "comparison-changed") {
    const diff = document.querySelector("document-diff") as any;
    if (diff) {
      // Unwatch old files
      watchedFiles.forEach(f => wsSource.unsubscribe(("file:" + f) as any));
      watchedFiles = [];
      if (payload.pathA) { diff.loadFile("a", payload.pathA); }
      if (payload.pathB) { diff.loadFile("b", payload.pathB); }
      watchFiles(payload.pathA, payload.pathB);
    }
  }
}) as EventListener);

if (debateParam) {
  connectDebateSession(debateParam);
}
// No else — sessions event from WebSocket handles auto-discovery

// ── Topbar wiring ─────────────────────────────────────────────────────

const diffEl = document.querySelector("document-diff") as any;

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
  if (btn) btn.textContent = next === "split" ? "⫏ Split" : "☰ Unified";
});

function updateDiffUI(): void {
  if (!diffEl) return;
  const s = diffEl.getDiffSummary();
  const has = s.totalDiffs > 0;
  // Counter
  const counter = document.getElementById("diff-counter");
  if (counter) counter.textContent = s.currentIdx >= 0
    ? `${s.currentIdx + 1} / ${s.totalDiffs}`
    : "— / —";
  // Nav buttons — enabled whenever diffs exist (matching old shell behaviour)
  const prev = document.getElementById("btn-prev") as HTMLButtonElement | null;
  const next = document.getElementById("btn-next") as HTMLButtonElement | null;
  if (prev) prev.disabled = !has;
  if (next) next.disabled = !has;
  // Diff summary counts
  const summaryEl = document.getElementById("diff-summary");
  if (summaryEl && has) {
    summaryEl.textContent = `~${s.modified || 0} −${s.deleted || 0} +${s.inserted || 0}`;
  }
  // Enable swap button once diffs are loaded
  const swapBtn = document.getElementById("btn-swap") as HTMLButtonElement | null;
  if (swapBtn && has) swapBtn.disabled = false;
}

document.addEventListener("diff-updated", () => updateDiffUI());

// ── Cross-panel event routing ─────────────────────────────────────────

document.addEventListener("point-selected", ((e: CustomEvent) => {
  const { location } = e.detail;
  if (diffEl) {
    if (location) diffEl.scrollToLocation(location);
    diffEl.highlightSection(location);
  }
}) as EventListener);

document.addEventListener("point-deselected", (() => {
  if (diffEl) diffEl.clearHighlight();
}) as EventListener);

document.addEventListener("selection-changed", ((e: CustomEvent) => {
  const { side, startLine, endLine, selectedText } = e.detail;
  if (!selectedText) return;
  const sessionId = currentSessionId;
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
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
  if ((el as HTMLElement).isContentEditable) return true;
  // Check inside open shadow roots — document.activeElement stops at the host
  const sr = (el as HTMLElement).shadowRoot;
  if (sr?.activeElement) return isEditable(sr.activeElement);
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
    ${sessions.map(s => `<button class="session-btn" data-debatesessionid="${s.debateSessionId}" style="display:block;width:100%;margin:4px 0;padding:8px;text-align:left;">${s.debateSessionId}</button>`).join("")}
  </div>`;
  document.body.appendChild(picker);
  picker.addEventListener("click", (e) => {
    const btn = (e.target as HTMLElement).closest(".session-btn") as HTMLElement | null;
    if (btn) { picker.remove(); connectDebateSession(btn.dataset.debatesessionid!); }
  });
}

// Suppress browser drag/drop
document.addEventListener("dragover", e => e.preventDefault());
document.addEventListener("drop", e => e.preventDefault());
