# E2E Tests: Debate Panel, Review Tracker, Cross-Panel Coordination

**Issue:** #55
**Date:** 2026-06-16

## 1. Goal

Add Playwright E2E tests that exercise the full server-to-browser delivery chain for the debate panel, review tracker, and cross-panel coordination. Every test drives state through `DebateMcpTools` (server-side), then asserts that SSE-delivered events produce the correct DOM structure in the browser.

## 2. Prerequisite: Add `declined` to `respondTo()` API

`EntryType.DECLINED` exists in the domain model and `DebateChannelProjection` handles it, but no MCP tool can produce it — `respondTo()` only accepts `agree`, `dispute`, `qualify`, `counter`. The #51 spec (line 674) flagged this as a prerequisite: "including DECLINED after prerequisite fix."

**Fix:** Add `"declined"` to `respondTo()`'s switch statement:

```java
case "declined" -> MessageType.DECLINE;
```

And update the error message and `@ToolArg` description to include `declined`. This encodes `entryType=DECLINED` in the META header and dispatches as `MessageType.DECLINE` — the same Qhorus type used by `dispute`, but with the correct `entryType` encoding.

`DECLINED` is semantically distinct from `DISPUTED` (ARC42STORIES line 818): "DECLINED is terminal (LLM declined to answer; point closed)" vs "DISPUTED is non-terminal (thread continues)." Making DECLINED producible through the MCP API closes a domain model gap.

File issue before implementation.

## 3. Test Infrastructure

### 3.1 Pattern

Each test class combines `@QuarkusTest @WithPlaywright` with CDI-injected `DebateMcpTools`. The lifecycle per test:

1. **Java side:** `startDebate()` → `raisePoint()` / `respondTo()` / `flagHuman()` / `postMemo()` to build Qhorus channel state
2. **Browser side:** navigate to `/?a=<fixtureAbsPath>&b=<fixtureAbsPath>&debate=<sessionId>`
3. **Wait:** Playwright `locator().waitFor()` on shadow DOM elements that prove SSE-delivered entries rendered
4. **Assert:** DOM structure, CSS classes, text content, event behaviour

### 3.2 DebateE2EFixtures

A static utility class (companion to `PlaywrightFixtures`, not a subclass — `PlaywrightFixtures` is `final` with private constructor).

| Method | Purpose |
|--------|---------|
| `startDebateSession(DebateMcpTools)` | Calls `startDebate("test-spec.md")`, extracts and returns sessionId. Overload `startDebateSession(DebateMcpTools, String specPath)` for custom spec paths. |
| `loadWithDebate(Page, URL, String sessionId)` | Navigates with `?a=<fixturePath("diff-a.md")>&b=<fixturePath("diff-b.md")>&debate=<sessionId>` using absolute paths via `PlaywrightFixtures.fixturePath()`, then calls `PlaywrightFixtures.waitForRender(page)` |
| `waitForDebateEntries(Page, int count)` | Waits until N `.entry` elements exist in `<drafthouse-debate>` shadow DOM |
| `waitForTrackerPoints(Page, int count)` | Waits until N `.point-item` elements exist in `<drafthouse-review-tracker>` shadow DOM |
| `listenForPointSelected(Page)` | Registers a JS listener: `document.addEventListener('point-selected', e => window.__pointSelectedDetail = e.detail)`. Call before the click that should trigger the event. |
| `getPointSelectedDetail(Page)` | Returns `window.__pointSelectedDetail` via `page.evaluate()`. Returns the detail object or null if no event fired. |
| `extractSessionId(String mcpResult)` | Regex extraction of `debateSessionId` from MCP tool JSON response |
| `extractNewSessionId(String mcpResult)` | Regex extraction of `newDebateSessionId` from `restartFromRound()` JSON response (distinct key from `startDebate`) |
| `extractPointId(String mcpResult)` | Regex extraction of `pointId` from MCP tool JSON response |

### 3.3 Test Isolation

- Each test creates its own `Page` in `@BeforeEach` and closes it in `@AfterEach`
- Each test that needs a debate session starts one and tears it down via `endDebate()` in `@AfterEach`
- This avoids SSE connection pool exhaustion (GE-20260529-a2095c) — closing the page closes the EventSource

### 3.4 SSE Timing

The `DebateEventResource` SSE endpoint polls every 500ms. After calling `DebateMcpTools` methods, Playwright's `locator().waitFor()` with default timeout (30s) handles the async gap. No manual sleep needed.

### 3.5 Shadow DOM Access

Playwright's `locator()` automatically pierces open shadow DOM. Selectors like `page.locator("drafthouse-debate .entry-raise")` work directly. For computed styles, pseudo-element content, or scroll position, use `page.evaluate()` with shadow-root-aware queries:

```java
// Pseudo-element content
page.evaluate("() => {"
    + "const el = document.querySelector('drafthouse-debate')"
    + "  .shadowRoot.querySelector('.entry-flag_human');"
    + "return getComputedStyle(el, '::before').content;"
    + "}");

// Scroll position
page.evaluate("() => {"
    + "const c = document.querySelector('drafthouse-debate')"
    + "  .shadowRoot.querySelector('.debate-container');"
    + "return { scrollTop: c.scrollTop, scrollHeight: c.scrollHeight,"
    + "  clientHeight: c.clientHeight };"
    + "}");
```

### 3.6 Fixture Files

Reuse existing `diff-a.md` and `diff-b.md` — they have headings that `scrollToLocation()` can target. Heading structure:

- **diff-a.md:** Preface, Introduction, Features (Word Changes), Scroll Sync, Navigation, Summary
- **diff-b.md:** Introduction, Features (Word Changes), Scroll Sync, Navigation, New Section, Appendix B

Note: §3 resolves to **Features** on side A (Preface is H2 #1) but **Scroll Sync** on side B (no Preface). This asymmetry is correct — `scrollToLocation()` independently finds the matching heading on each side.

## 4. Test Classes

### 4.1 DebatePanelE2ETest

Tests that `<drafthouse-debate>` renders SSE-delivered debate entries with correct structure and visual treatment. 18 tests.

| Test | Server-side setup | Browser assertion |
|------|-------------------|-------------------|
| `placeholder_whenNoDebateSession` | None — navigate without `?debate=` | "Waiting for debate session…" visible |
| `emptyState_whenDebateStartedButNoEntries` | `startDebate()` | "No entries yet" placeholder visible |
| `roundDivider_appearsOnFirstEntry` | Raise point in round 1 | `.round-divider` with text "Round 1" |
| `raiseEntry_rendersWithCorrectStructure` | Raise point: P1, ISOLATED, §3.2 | `.entry-raise` present; `.badge-priority-p1`; `.badge-scope` with "ISOLATED"; `.badge-location` with "§3.2"; `.entry-agent` with "Reviewer" |
| `agreeEntry_hasCorrectClass` | Raise + agree | `.entry-agree` present |
| `counterEntry_hasCorrectClass` | Raise + counter | `.entry-counter` present |
| `disputeEntry_hasCorrectClass` | Raise + dispute | `.entry-dispute` present |
| `qualifyEntry_hasCorrectClass` | Raise + qualify | `.entry-qualify` present |
| `declinedEntry_hasReducedOpacity` | Raise + respondTo "declined" | `.entry-declined` present (prerequisite: §2 API fix) |
| `flagHumanEntry_rendersWarningBanner` | Raise + flagHuman | `.entry-flag_human` visible; `page.evaluate()` through shadow root confirms `getComputedStyle(el, '::before').content` contains "HUMAN ATTENTION REQUIRED" |
| `memoEntry_rendersWithMemoClass` | postMemo | `.entry-memo` present |
| `restartContext_rendersCenteredBranchMarker` | Raise point round 1, respond round 2, then `restartFromRound(round=1)` — extract `newDebateSessionId` via `extractNewSessionId()`, navigate to the **new** session. `@AfterEach` must call `endDebate()` on **both** the original and branched sessions. | `.entry-restart_context` present with text "── session branched ──"; no hover effect (cursor: default) |
| `multipleRounds_showsSeparateDividers` | Raise in round 1, respond in round 2 | Two `.round-divider` elements: "Round 1" and "Round 2" |
| `autoScroll_scrollsToLatestEntry` | Raise 5+ points (enough to overflow container) | Shadow-root-aware `page.evaluate()` reads `.debate-container` scroll state: `scrollTop + clientHeight` is within 50px of `scrollHeight` |
| `pointSelected_firesCustomEvent` | Raise point with pointId, click the entry | `listenForPointSelected(page)` before click; `getPointSelectedDetail(page)` after click returns object with correct `pointId` and `location` |
| `subTaskRequest_rendersIndented` | `requestSubagent()` | `.entry-sub_task_request` present with indented styling (dashed border, smaller font) |
| `subTaskFinding_rendersWithPointBadge` | `requestSubagent()` + await MockDebateAgentProvider completion | `.entry-sub_task_finding` present; point badge (`.badge` with `→` prefix) visible |
| `subTaskError_rendersWithErrorStyling` | `requestSubagent(taskType="NONEXISTENT")` — no handler matches, `ChannelAgentDispatcher.dispatchError()` fires | `.entry-sub_task_error` present with indented styling; content contains "No handler matched" |

### 4.2 ReviewTrackerE2ETest

Tests that `<drafthouse-review-tracker>` derives correct `ReviewStatus` per pointId from the SSE entry sequence and renders the checklist. 16 tests.

| Test | Server-side setup | Browser assertion |
|------|-------------------|-------------------|
| `placeholder_whenNoDebateSession` | None | "Waiting for debate session…" visible |
| `emptyState_showsNoPoints` | `startDebate()` | "No review points yet" placeholder |
| `raisedPoint_showsOpenStatus` | Raise point | `.point-item.status-open` with `.point-icon` text `○` |
| `agreedPoint_showsStrikethrough` | Raise + agree | `.point-item.status-agreed` with `.point-icon` text `✓`; `.point-summary` has `line-through` text-decoration |
| `declinedPoint_showsStrikethrough` | Raise + respondTo "declined" | `.point-item.status-declined` with strikethrough, reduced opacity (prerequisite: §2 API fix) |
| `counteredPoint_showsActiveStatus` | Raise + counter | `.point-item.status-active` with `.point-icon` text `⟳` |
| `disputedPoint_showsDisputedStatus` | Raise + dispute | `.point-item.status-disputed` with `.point-icon` text `✕` |
| `qualifiedPoint_showsActiveWithAccentBorder` | Raise + qualify | `.point-item.qualify-active` present |
| `flagHuman_showsPendingHumanStatus` | Raise + flagHuman | `.point-item.status-pending_human` with `.point-icon` text `⚑` |
| `progressBar_reflectsResolutionRatio` | Raise 3 points, agree 1 | Progress label "1 of 3 resolved"; `page.evaluate()` reads `parseFloat(progressFill.style.width)` — assert `>= 33 && <= 34` |
| `hideResolvedFilter_hidesAgreedAndDeclined` | Raise 3, agree 1, respondTo "declined" 1; toggle checkbox | Only 1 `.point-item` visible (the OPEN one). (Prerequisite: §2 API fix for the declined point.) |
| `hideResolvedFilter_allResolved_showsMessage` | Raise 2, agree both; toggle checkbox | "All points resolved" placeholder |
| `sortOrder_openBeforeAgreed` | Raise 2 points, agree the first | First `.point-item` is `.status-open`, second is `.status-agreed` |
| `agentTrail_showsActionSequence` | Raise → counter → agree on same point | `.point-trail` contains "REV raised → IMP countered" segment |
| `locationReference_displayedOnPoint` | Raise with location §3.2 | `.point-location` with text "§3.2" |
| `pointSelected_firesCustomEvent` | Click point item | `listenForPointSelected(page)` before click; `getPointSelectedDetail(page)` after click returns object with correct `pointId` |

### 4.3 CrossPanelE2ETest

Tests that the shell routes `point-selected` events from debate/tracker panels to the diff panel's `scrollToLocation()`. 4 tests.

| Test | Server-side setup | Browser action | Assertion |
|------|-------------------|----------------|-----------|
| `debateEntry_scrollsDiffToSectionRef` | Raise point with location "§3" | Click entry in debate panel | Both diff panels' `scrollTop > 0` (side A scrolls to "Features", side B scrolls to "Scroll Sync" — different headings per side, both correct) |
| `trackerPoint_scrollsDiffToSectionRef` | Raise point with location "§3" | Click point in review tracker | Same — both panels' `scrollTop > 0` |
| `pointWithoutLocation_noScroll` | Raise point without location, click it | Click entry | Both diff panels' `scrollTop` unchanged |
| `textReference_scrollsToMatchingHeading` | Raise point with location "Scroll Sync" | Click entry | Both diff panels scroll (heading "Scroll Sync" exists in both fixtures) |

## 5. Migration Resilience

These tests assert **panel behaviour**, not shell layout. When `@casehub/ui` replaces the workspace shell:

- **34 panel tests unaffected** — they test within shadow DOM boundaries (`<drafthouse-debate>`, `<drafthouse-review-tracker>`) which are the same Web Components regardless of shell
- **4 cross-panel tests may need selector updates** — they depend on the shell's `document.addEventListener('point-selected', ...)` wiring, which `@casehub/ui` may route differently
- **Test infrastructure unaffected** — `DebateE2EFixtures`, server-side setup, SSE delivery are shell-independent

## 6. Files

| File | Purpose |
|------|---------|
| `e2e/DebateE2EFixtures.java` | Static utility: debate session helpers, SSE wait helpers, event capture helpers, ID extractors |
| `e2e/DebatePanelE2ETest.java` | Debate panel rendering tests (18 tests) |
| `e2e/ReviewTrackerE2ETest.java` | Review tracker status derivation tests (16 tests) |
| `e2e/CrossPanelE2ETest.java` | Cross-panel event routing tests (4 tests) |

All in `server/runtime/src/test/java/io/casehub/drafthouse/e2e/`.

## 7. Out of Scope

- **Context gauge E2E tests** — `<drafthouse-context-gauge>` is topbar-only and tested via `report_context` MCP tool integration tests
- **Session discovery polling** — the `setInterval`-based picker is shell infrastructure, replaced by `@casehub/ui`
