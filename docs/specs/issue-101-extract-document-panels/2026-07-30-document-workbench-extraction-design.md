# Design: Extract Document Panels as @casehubio/agency-ui-document-workbench

**Issue:** casehubio/drafthouse#101
**Date:** 2026-07-30
**Status:** Draft

## Overview

Extract all 9 DraftHouse Lit panels into a single blocks-ui component package
(`@casehubio/agency-ui-document-workbench`) for cross-app embedding. Update the
blocks-ui showcase gallery with a new "Document Workbench" category. Refactor
DraftHouse to consume the extracted package instead of owning the panels directly.

## Convergence Analysis

Before extraction, each drafthouse panel was compared against existing blocks-ui
and pages components for reuse opportunities:

| Drafthouse Panel | Candidate | Verdict |
|------------------|-----------|---------|
| channel-feed | blocks-channel-feed | **Different.** Debate feed groups by round with type-specific styling (RAISE/COUNTER/DISPUTE borders, sub-task indentation, RESTART_CONTEXT separators). blocks-channel-feed groups by sender with threading, reactions, topics. Different data models, different interaction patterns. |
| document-timeline | blocks-timeline | **Different.** Timeline is a pair-selection picker (A/B snapshot comparison with shift-click). blocks-timeline is a single-selection progression viewer with status nodes. The A/B pair selection is fundamental and doesn't exist in blocks-timeline. |
| context-gauge | pages-viz PagesMeter | **Different.** Gauge is a 40px×8px inline topbar progress bar. PagesMeter is a full ECharts half-circle chart. Different form factors, different use cases. |
| review-tracker | approval-gate | **Partial.** Both handle human decisions, but review-tracker is a multi-point checklist deriving status from event streams. approval-gate is a single-decision gate. No practical reuse path. |
| document-diff, doc-picker, brainstorm-options, brainstorm-picker, workspace-status | — | No overlap found. |

All 9 panels extract as new components.

## Package Structure

```
blocks-ui/components/document-workbench/
  package.json          # @casehubio/agency-ui-document-workbench
  src/
    index.ts            # barrel export
    types.ts            # shared interfaces
    debate-feed.ts      # renamed from channel-feed (avoids blocks-channel-feed collision)
    document-diff.ts
    review-tracker.ts
    document-timeline.ts
    context-gauge.ts
    doc-picker.ts
    brainstorm-options.ts
    brainstorm-picker.ts
    workspace-status.ts
```

### Naming

`channel-feed` is renamed to `debate-feed` (custom element tag `debate-feed`)
to avoid collision with the existing `blocks-channel-feed` tag in
`@casehubio/agency-ui-channel-activity`.

All other panels keep their current tag names — no collisions exist.

### Shared Types (types.ts)

Interfaces currently duplicated inline across panels are consolidated:

```typescript
interface DebateStreamEntry {
  entryType: string;
  content: string;
  round: number;
  agentRole: string;
  timestamp?: string;
  pointId?: string;
  priority?: string;
  scope?: string;
  location?: string;
  commitHash?: string;
  documentPath?: string;
}

interface Snapshot {
  label: string;
  round: number;
  commitHash: string;
  documentPath: string;
}

interface TrailHighlight {
  raiseRound: number | null;
  fixRound: number | null;
  verifyRound: number | null;
}

interface BrainstormOptionData {
  id: string;
  title: string;
  description: string;
  tradeoffs: string;
  status: string;
}

interface OptionsPayload {
  sessionId: string;
  options: BrainstormOptionData[];
  state: string;
}
```

## Adaptation for Blocks-UI

### CSS Variables

Panels currently use drafthouse-specific CSS variables (`--ink`, `--muted`,
`--sepia`, `--chrome`, `--border`, `--accent`, `--approve`, `--warn`, `--error`).

**Migration strategy:** Where a direct pages-token equivalent exists, switch to it.
Where no equivalent exists, keep the variable with a pages-token fallback.

| Drafthouse var | Pages token | Notes |
|----------------|-------------|-------|
| `--ink` | `--pages-neutral-12` | Primary text |
| `--muted` | `--pages-neutral-8` | Secondary text |
| `--border` | `--pages-neutral-5` | Border color |
| `--border-light` | `--pages-neutral-4` | Light border |
| `--accent` | `--pages-accent-9` | Accent/interactive |
| `--accent-bg` | `--pages-accent-3` | Accent background |
| `--accent-tint` | `--pages-accent-2` | Light accent fill |
| `--error` | `--pages-error-9` | Error/dispute |
| `--warn` | `--pages-warning-9` | Warning/flag |
| `--approve` | `--pages-success-9` | Success/agree |
| `--sepia` | (no equivalent) | Keep as `var(--sepia, var(--pages-neutral-11))` |
| `--chrome` | (no equivalent) | Keep as `var(--chrome, var(--pages-neutral-2))` |
| `--bg` | (no equivalent) | Keep as `var(--bg, var(--pages-neutral-1))` |
| `--human-badge` | (no equivalent) | Keep as `var(--human-badge, #e67e22)` |

### API Base URL

Four panels make HTTP calls to drafthouse REST endpoints:

| Panel | Endpoints |
|-------|-----------|
| document-diff | `GET /api/file?path=`, `GET /api/debate/{id}/snapshot/{index}` |
| doc-picker | `POST /api/debate/{id}/comparison` |
| review-tracker | `POST /api/debate/{id}/human/comment`, `.../override`, `.../prioritise`, `.../batch` |
| brainstorm-options | `PATCH /api/brainstorm/{id}/options/{optionId}` |

Each of these panels gains an `apiBaseUrl` string property (default `''`).
All fetch calls prepend `${this.apiBaseUrl}/api/...`. For same-origin apps
(drafthouse itself) the default works. Cross-origin consumers set the property.

### Event Model

All panels use `onPagesEvent()` from `@casehubio/pages-component` — no change.
This is a peer dependency of the package.

Inter-panel communication via DOM custom events is unchanged:
- `document-timeline` ↔ `document-diff`: `timeline-comparison-changed`
- `review-tracker` / `debate-feed` → `document-timeline`: `point-selected`, `point-deselected`

### configure() Method

All panels implement `configure(props)` — the pages-runtime initialization hook.
This is the standard blocks-ui pattern. No change needed.

### marked Dependency

`document-diff` is the only consumer of `marked` (markdown rendering). It becomes
a peer dependency of the package — consuming apps supply it. This avoids bundling
marked into apps that only embed other panels.

## Showcase Gallery

### Navigation

New category in `examples/src/shell.ts` NAV array:

```typescript
{
  label: 'Document Workbench',
  items: [
    { id: 'document-diff',      label: 'Document Diff',      hash: '#document-workbench/document-diff' },
    { id: 'debate-feed',        label: 'Debate Feed',         hash: '#document-workbench/debate-feed' },
    { id: 'review-tracker',     label: 'Review Tracker',      hash: '#document-workbench/review-tracker' },
    { id: 'document-timeline',  label: 'Document Timeline',   hash: '#document-workbench/document-timeline' },
    { id: 'context-gauge',      label: 'Context Gauge',       hash: '#document-workbench/context-gauge' },
    { id: 'doc-picker',         label: 'Doc Picker',          hash: '#document-workbench/doc-picker' },
    { id: 'brainstorm-options', label: 'Brainstorm Options',   hash: '#document-workbench/brainstorm-options' },
    { id: 'brainstorm-picker',  label: 'Brainstorm Picker',   hash: '#document-workbench/brainstorm-picker' },
    { id: 'workspace-status',   label: 'Workspace Status',    hash: '#document-workbench/workspace-status' },
  ],
}
```

### Mock Data

New `examples/mock-data/document-workbench.ts` providing:

- **Debate entries:** 8-10 entries across 3 rounds — RAISE, COUNTER, AGREE,
  FLAG_HUMAN, COMMENT, HUMAN_OVERRIDE — with realistic content, priorities,
  scopes, and locations
- **Documents:** Two short markdown strings (~20 lines each) with enough
  difference for the diff panel to show meaningful LCS highlighting
- **Snapshots:** 3-4 ROUND_SNAPSHOT entries with labels ("Initial", "Round 1
  fixes", "Round 2 refinement")
- **Brainstorm options:** 4 options with mixed statuses (one recommended, one
  eliminated, one selected, one pending)
- **Context usage:** A numeric percentage for the gauge
- **Documents list:** 2-3 document entries for the doc-picker dropdown
- **Workspace progress:** Agent status, cost, elapsed time for workspace-status

### Page Files

9 new page files at `examples/src/pages/`:
- `document-diff-page.ts`
- `debate-feed-page.ts`
- `review-tracker-page.ts`
- `document-timeline-page.ts`
- `context-gauge-page.ts`
- `doc-picker-page.ts`
- `brainstorm-options-page.ts`
- `brainstorm-picker-page.ts`
- `workspace-status-page.ts`

Each follows the existing page pattern: mount the component, feed mock data
via properties or by dispatching pages-events, add a brief description.

### Vite Config

Add alias to `examples/vite.config.ts`:

```typescript
{ find: '@casehubio/agency-ui-document-workbench',
  replacement: resolve(__dirname, '../components/document-workbench/src') },
```

### main.ts

Add imports for the 9 new pages to the bootstrap sequence.

## DraftHouse Consumer Migration

After extraction:

1. **Delete** all 9 files from `server/runtime/src/main/webui/src/panels/`
2. **Update** `index.ts` to import from `@casehubio/agency-ui-document-workbench`
   instead of local `./panels/*`
3. **No apiBaseUrl needed** — drafthouse runs same-origin, default `''` works
4. **Dependency resolution** via existing Maven SNAPSHOT WebJar pattern (portal:
   in package.json → `.casehub-packages/`)

## Garden Context

Two garden entries inform this work:

- **GE-20260729-f3f3a1** — CommitmentStatePill double registration: when promoting
  a component to blocks-ui-core, barrel imports from both packages cause
  `customElements.define()` collision. Mitigation: do NOT re-export document-workbench
  components from blocks-ui-core. Consumers import from the workbench package directly.
- **GE-20260720-9c817e** — Cross-repo Vite alias pattern: the examples app already
  uses this pattern for all component packages. Follow the same convention.

## Scope Boundary

**In scope:**
- Extract 9 panels to blocks-ui component package
- CSS token migration with fallbacks
- API base URL property on 4 panels
- Shared types consolidation
- Showcase gallery pages with mock data
- DraftHouse migration to consumer

**Out of scope:**
- Refactoring document-diff (1167 lines, imperative DOM) — works as-is, refactor later
- Publishing to npm registry — local development via Vite aliases and Maven SNAPSHOT
- Claudony integration — separate issue after extraction proves the package works
