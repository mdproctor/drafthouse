# Document Workbench — Exploration

**Date:** 2026-07-05
**Status:** Exploration (pre-spec)
**Participants:** Mark Proctor, Claude

## 1. Origin

Two systems model structured conversations about documents:

- **design-review** — a headless Python orchestrator running adversarial debates between
  Claude agents (reviewer + implementor). Produces structured markdown files. Effective,
  proven at scale (~20 reviews across the CaseHub platform in a single night). File-based,
  git-evidenced, no UI needed. Evolving toward a four-phase pipeline (pre-review, spec
  review, code review, final review — see `~/claude/hortora/soredium/docs/specs/2026-07-02-adr-four-phase-review-pipeline.md`).

- **DraftHouse** — a Quarkus server with Web Component panels. Side-by-side markdown diff,
  debate event stream (Qhorus channels + WebSocket push via `DebateWebSocket` and
  `WebSocketEventBus`), review point tracking with status derivation, section highlighting.
  Currently a standalone app.

Both systems share a common foundation: the **P5 conversation protocol** extracted to
`casehub-blocks` (`io.casehub.blocks.conversation` — see `docs/superpowers/specs/2026-06-29-p5-conversation-protocol-extraction-design.md`). `ConversationState`,
`ConversationProjection`, `ConversationProtocol`, and `ConversationRenderer` provide
application-agnostic structured conversation infrastructure. DraftHouse's
`DebateChannelProjection` is already a ~15-line subclass of `ConversationProjection`,
implementing only three hook methods: `sentinel()`, `isPointInitiator()`, `statusAfter()`.

This exploration asked: what happens when we think about these together? The answer was
bigger than expected. DraftHouse isn't a viewer over design-review — it's a **document
workbench** that can host the full lifecycle of structured document work.

## 2. The Document Lifecycle

Documents pass through phases. Today each phase is a disconnected tool.

| Phase | What happens | Current tool | Turn time | Human role |
|-------|-------------|--------------|-----------|------------|
| **Genesis** | Blank page → structured Q&A → document | brainstorming skill | Seconds | Driver |
| **Refinement** | Discuss, annotate, improve existing artifact | Claude Code conversation | Seconds–minutes | Editor |
| **Adversarial review** | Formal debate with evidence-based verification | design-review skill | 5–10 min/turn | Selective observer |
| **Implementation** | Spec gets built into code | work-start → TDD | Minutes | Implementor |
| **Re-review** | Incremental review of changed sections | (not yet built) | Minutes | Observer |

Each tool throws its output over the wall to the next. The conversation context,
reasoning, and decisions are lost at each handoff.

A document workbench provides the **continuous surface** where the document lives
through all phases. The conversation spine — why each section says what it says —
is preserved and replayable.

## 3. Use Cases

### UC1: Single-file adversarial review
**Input:** One spec file.
**Flow:** Reviewer produces findings → implementor debates and applies fixes → reviewer
verifies. Rounds continue until convergence.
**Workbench value:** Watch the debate live or replay a completed one. Section highlighting
shows which parts of the spec each issue refers to. Document timeline shows how the spec
evolved across rounds.
**Related issues:** #72 (review pipeline orchestration), #71 (Claude-to-Claude conversation protocol)

### UC2: A/B diff review
**Input:** Two document versions (before/after).
**Flow:** MCP tools create review points grounded in the diff. Debate happens about
specific changes.
**Workbench value:** Side-by-side visual with section-scoped conversation. (Current
DraftHouse capability.)

### UC3: Multi-file review
**Input:** Spec + implementation code, or spec + related specs.
**Flow:** Reviewer checks implementation matches spec, or checks consistency across specs.
**Workbench value:** Multiple documents in the working set. Comparison pairs shift during
review. This is design-review's planned "code review" phase (four-phase pipeline Phase 3).
**Related issues:** #72 (review pipeline orchestration)

### UC4: Audit/compliance review
**Input:** Artifact + immutable reference standard (OWASP, GDPR, platform protocols).
**Flow:** Reviewer checks artifact against standard. Only the artifact changes.
**Workbench value:** Asymmetric comparison — one side is immutable. Review points reference
both the standard (as authority) and the artifact (as subject).

### UC5: Continuous review
**Input:** Living document with change history.
**Flow:** After each change cycle, only new/changed sections are re-reviewed. Previous
review state carries forward.
**Workbench value:** "Already approved" vs "needs re-review" visual. Timeline shows
approval state over time.

### UC6: Document genesis
**Input:** Blank page + intent.
**Flow:** Human + AI conversation builds the document through structured Q&A. What the
brainstorming skill does today, but persistent and visual. Corresponds to the four-phase
pipeline's future direction: "structured design debates" where each design question gets
its own debate.
**Workbench value:** See the document take shape alongside the conversation. Click on
sections to discuss further. The reasoning behind each section is preserved.
**Related issues:** #84 (brainstorming UI), #53 (richer option exploration)

### UC7: Document refinement
**Input:** Existing artifact (e.g., brainstorming output) that needs discussion before
formal review. Corresponds to the four-phase pipeline's pre-review phase.
**Flow:** Human selects sections, discusses, refines. Interactive, short turns. May
involve multiple AI perspectives.
**Workbench value:** Bridge between genesis and adversarial review. The document is
"good enough to exist" but "not yet ready for formal review."
**Related issues:** #53 (richer option exploration), #60 (selection-scoped channels)

### UC8: Publisher workflow
**Input:** Draft content (blog post, article, documentation section).
**Flow:** Author + editor conversation. May include fact-checking, tone review, audience
targeting. Potentially multiple rounds with different reviewers (technical accuracy,
editorial style, legal compliance).
**Workbench value:** A reusable component embedded in a publishing app. The document
workbench becomes part of a larger case workflow.

## 4. Key Design Findings

### 4.1 UC1 and UC2 are the same abstraction at the timeline level

A single-file review IS an A/B review at a different timescale. Each round creates a
before/after pair via git commits. Round 1's spec is "before", the implementor's changes
produce "after", and the reviewer in round 2 reviews the diff.

The fields `commit_before` and `commit_after` in design-review's tracker already capture
this — they just don't visualize it.

**Implication:** The right abstraction is a **document timeline** — snapshots of the
document at each round. A comparison is any two points on that timeline. The diff view
shows the delta between any two snapshots. Issues are anchored to specific sections of
specific snapshots.

**Data model evolution required:** The current `DocumentEntry(path, label)` and
`ComparisonPair(pathA, pathB)` carry no version metadata. To unify UC1 and UC2:

```
DocumentSnapshot
├── path: String
├── label: String
├── source: SnapshotSource   ← discriminated union
│   ├── GitCommit(commitHash, timestamp)
│   ├── ExplicitSave(saveId, timestamp)
│   └── ContentCapture(contentHash, timestamp)
└── content: Supplier<String> ← lazy-loaded

DocumentTimeline
├── documentId: String
├── snapshots: List<DocumentSnapshot>  ← ordered by timestamp
└── comparisons: derived   ← any two snapshots form a comparison
```

`ComparisonPair` becomes `ComparisonPair(DocumentSnapshot a, DocumentSnapshot b)` —
enriched with version metadata. UC1 (same document, different git commits) and UC2
(different documents, same point in time) are both expressible. The `SnapshotSource`
discriminator tells the timeline whether a snapshot came from git, an explicit save
(genesis/refinement), or a content capture (conversation replay).

### 4.2 Files and channels are competing transports, not competing storage

The canonical representation is **`ConversationState`** (P5, `io.casehub.blocks.conversation`) —
the fold accumulator for any structured conversation. Both systems produce the same
semantic content; they differ only in transport.

What both systems capture per event:

| Concept | design-review (files) | DraftHouse (channels) | P5 model |
|---------|----------------------|----------------------|----------|
| Who spoke | role in filename | `role` metadata key | `ThreadEntry.role: String` |
| Which issue | R1-02 (regex-parsed from heading) | `correlationId` | `ConversationPoint.id` |
| Status assertion | FIXED / REJECTED / resolved / contested | RAISE / AGREE / COUNTER / DISPUTE | `statusAfter(entryType)` |
| Content | markdown body under heading | message content | `ThreadEntry.content` |
| Evidence | git commit hash, section ref | (not yet modeled) | (extensible via meta) |
| Location | buried in reviewer's prose | structured metadata field | `PointClassification.location` |
| Round | inferred from filename number | explicit metadata field | `ThreadEntry.round` |
| Document version | git history of spec file | (not yet modeled) | (not yet modeled) |

**Gaps:** DraftHouse needs commit evidence and document snapshots. design-review needs
structured location metadata.

**End-state architecture:** design-review stays file-based — that's its strength (works
offline, zero dependencies, git-evidenced, headless). The adapter is permanent
infrastructure, not a temporary bridge. Making design-review emit Qhorus channel messages
would require a running Qhorus instance in every Claude Code session, which defeats the
headless design that makes it effective. The adapter is **bi-directional**: file → entries
for visualization (batch parse on load or round completion), and UI → files for human
intervention (§4.3 concurrent HIL). It is not a live sync — there is no continuous
bidirectional synchronization — but both directions are part of the permanent design.

### 4.3 HIL is the unlock channels provide over files

design-review's human-in-the-loop is stop-the-world:
- `DECISION_NEEDED` signal → script pauses → user types in terminal → script resumes
- `.hil-timeout` marker file → external process writes "kill" or "extend"

This works but it's batch-mode HIL. You can't intervene on a specific issue while the
debate continues on others.

Channel-based HIL enables **concurrent human engagement:**
- Click an issue in the review tracker → add a human comment → agents see it next round
- Select text in the diff → "the original wording was intentional" → FLAG_HUMAN entry
- Watch the debate live and only intervene when something goes wrong
- Let most issues resolve autonomously; focus on the 2–3 that need human judgment

**This changes what's possible, not just what's visible.** The viewer framing undersells it.

### 4.4 Phase determines UI behaviour

Genesis is collaborative (short turns, questions and proposals). Adversarial review is
structured (long turns, evidence-based assertions, issue state machine). The same panel
components render differently based on the session's current phase.

| Phase | Debate panel behaviour | Tracker behaviour | Diff panel behaviour |
|-------|----------------------|-------------------|---------------------|
| Genesis | Conversational feed | Hidden or minimal | Editor mode (editable) |
| Refinement | Discussion threads | Suggestion list | Selection + annotation |
| Adversarial review | Round-grouped entries | Issue state machine | Read-only diff with highlighting |
| Implementation | Status updates | Checklist | Spec vs code diff |

Entry types may stay generic — the phase context determines rendering.

**Phase transition model (directional):** Each phase creates a **new session** linked
by a shared document identity. The document identity is **path-based**: the canonical
file path (e.g., `specs/2026-07-05-document-workbench-exploration.md`) is the identifier.
This matches how design-review already works — `.spec-path` stores the absolute path,
and the tracker references it. For genesis (where the document doesn't exist yet), the
identity is assigned when the document is first saved.

Path-based identity is fragile across renames. The workbench should maintain a rename
registry (old path → new path) so that sessions created against the pre-rename path
remain linked. This is analogous to how git detects renames by content similarity —
simple, imperfect, but sufficient for the common case. A UUID-based identity model
would be more robust but requires introducing a new entity (`DocumentWorkbench` or
similar) that doesn't exist in the current data model. The follow-up spec should
evaluate whether the rename frequency justifies the additional entity.

Reasons for separate sessions per phase:

- Phase-specific conversation history shouldn't pollute other phases. Genesis Q&A
  has different semantics from adversarial review findings.
- The four-phase pipeline spec already uses separate trackers per phase with separate
  round counters. Separate sessions align with this.
- Phase regression (returning to refinement after review finds fundamental issues)
  means creating a new refinement session, not rewinding the review session. The
  review's findings are preserved for reference.
- The `DebateSession` class supports N participants — different phases use different
  participant sets, which is cleanly modeled as separate sessions.

Phase transitions are **human-initiated**. The document workbench shows available
phases and the human decides when to advance. The design-review orchestrator may
trigger phase advancement when running headlessly, but the UI should never
auto-advance.

### 4.5 New agent topologies

design-review is strictly reviewer ↔ implementor, mediated by a Python PM.
DraftHouse defines `AgentType { REV, IMP, SUPERVISOR, MODERATOR, SELECTOR }` as an
app-level enum; at the protocol level (P5), roles are strings via `ConversationProtocol.ROLE`.

Channels make additional participants trivial:
- **SUPERVISOR** watches for quality degradation (sycophantic agreement, missing evidence)
- **SELECTOR** triages issues by impact before the implementor starts
- **MODERATOR** reasons about impasses (vs auto-escalating to DEFERRED)
- **HUMAN** is just another participant, not a blocking gate

### 4.6 Brainstorming conversations become durable

The brainstorming skill today runs in Claude Code's terminal. The conversation — the
reasoning behind each design decision — disappears when the session ends. Only the
final spec survives.

Through DraftHouse channels, the reasoning is preserved. Six months later, someone
asks "why did we design it this way?" and the answer is in the debate record, not
in someone's memory.

## 5. Platform Integration

### 5.1 DraftHouse as the document layer

DraftHouse stops being a standalone app and becomes the **document layer** across
CaseHub. Any application that needs "discuss and refine this artifact" gets it
through composable pages panels.

- **Claudony** embeds DraftHouse panels for document work
- **DevTown** uses the workbench for spec review during development
- **Any domain app** (AML, Clinical, Life) embeds the workbench as part of case work

### 5.2 blocks-ui alignment

blocks-ui provides reusable case management components (built with Lit):
- `<work-item-inbox>` — list view with tabs, filters, live updates
- `<work-item-detail>` — detail panel with actions
- `<work-item-workbench>` — orchestrating composition
- `<case-timeline>` — lifecycle progression
- `<channel-activity>` — Qhorus channel feed (stub — not yet implemented)

The document workbench follows the same pattern:
- `<document-diff>` — side-by-side or unified diff (exists as `<drafthouse-diff>`)
- `<document-debate>` — conversation feed (exists as `<drafthouse-debate>`)
- `<document-tracker>` — issue/point tracking (exists as `<drafthouse-review-tracker>`)
- `<document-timeline>` — version history with comparison picker (new)
- `<document-editor>` — editing surface for genesis/refinement phases (new)
- `<document-workbench>` — orchestrating composition with phase-aware layout (new)

DraftHouse's existing panels are **vanilla Shadow DOM** Web Components (`HTMLElement`
subclasses with `attachShadow()`, raw DOM manipulation). blocks-ui uses **Lit**. Extraction
to blocks-ui requires migrating to Lit — this is a prerequisite for the reusable component
vision, not a given. Panels already communicate through `pages-event` custom events and
embed via `hostPanel()` / `registerPanel()`, which are framework-agnostic.

### 5.3 The reusable component vision

A publisher building a content workflow doesn't care about DraftHouse. They want:
- A document editing surface
- A structured review process
- Version tracking
- Human + AI collaboration

They get all of this by embedding `<document-workbench>` in their app and wiring it
to their data source (REST endpoint or pages dataset). The same component serves
spec review, blog editing, contract negotiation, and clinical report authoring.

This is the CaseHub rapid-development thesis: domain-specific apps compose from
platform components. The document workbench is a platform component.

**Extraction readiness (directional):**
- **Ready (stateless renderers):** `<drafthouse-diff>` renders content passed to it via
  `pages-event` — no server dependency. Migrate to Lit, rename, extract.
- **Needs dependency inversion:** `<drafthouse-debate>` and `<drafthouse-review-tracker>`
  consume `DebateStreamEntry` shape and subscribe to debate topics. The data contract is
  already generic (`pages-event` payloads), but the topic names and payload shapes are
  DraftHouse-specific. Extracting requires defining a stable event contract.
- **New components:** `<document-timeline>`, `<document-editor>` — design from the start
  with the blocks-ui contract, not as DraftHouse internals.

## 6. Architecture: The Adapter

### 6.1 Bi-directional adapter

```
┌──────────────────┐         ┌─────────────┐         ┌──────────────────┐
│  design-review   │         │   Adapter    │         │   DraftHouse     │
│  (file-based)    │◄───────►│  (bridge)    │◄───────►│   (channels)     │
│                  │         │              │         │                  │
│  responses/      │ parse   │  debate      │ emit    │  WebSocket       │
│  tracker.md      │────────►│  events      │────────►│  event bus       │
│  spec.md         │         │              │         │                  │
│  progress.log    │◄────────│  file        │◄────────│  HIL entries     │
│                  │ write   │  writer      │ receive │  human comments  │
└──────────────────┘         └─────────────┘         └──────────────────┘
```

**File → entries direction:** The adapter parses a design-review workspace into
individual `DebateStreamEntry` objects — one per issue heading, one per response, one per
confirmation. The entries are pushed to `WebSocketEventBus.pushDebateEntries(channelId,
entries)`. Client-side panels and/or `DebateChannelProjection` fold these entries into
`ConversationState` on the consumption side.

The pipeline is:
1. Parse response files → structured entries (entryType, agentRole, round, pointId, content)
2. Convert each entry to `DebateStreamEntry` with correct metadata
3. Push entries to `WebSocketEventBus`
4. Consumer-side projection folds entries into `ConversationState`

`ConversationState` is the shared data model — but it is the **output of consumption**,
not an intermediate step in the push pipeline. The adapter produces entries; the consumer
produces state.

This is a **parser**, not a `ConversationProjection` subclass. `ConversationProjection`
folds Qhorus `MessageView` instances incrementally — design-review files are not Qhorus
messages, and replay is one-shot (parse all files), not incremental. A direct parser
producing `DebateStreamEntry` objects is simpler and more correct.

**Channel → File direction:** When a human adds a comment or flag in DraftHouse, the
adapter writes it to a file in the workspace (e.g., `decisions/human-round-{n}.md`).
The design-review orchestrator picks it up in the next round as additional context.

### 6.2 Entry type mapping

This table maps design-review file signals to DraftHouse entry types (protocol-level
strings per P5). The resulting `ConversationPoint` status is derived by
`statusAfter()` — e.g., `FLAG_HUMAN` entry type → `ESCALATED` status via the base
`ConversationProjection`.

| design-review signal | Direction | Entry type | Derived status |
|---------------------|-----------|------------|----------------|
| Issue raised (new ###) | file → channel | `RAISE` | `OPEN` |
| FIXED | file → channel | `AGREE` (with commit evidence) | `AGREED` |
| REJECTED | file → channel | `COUNTER` | `ACTIVE` |
| ESCALATED | file → channel | `FLAG_HUMAN` | `ESCALATED` |
| resolved (reviewer confirmed) | file → channel | `VERIFIED` (new) | `VERIFIED` (new) |
| accepted (reviewer accepted rejection) | file → channel | `AGREE` | `AGREED` |
| contested | file → channel | `DISPUTE` | `DISPUTED` |
| DECLINED | file → channel | `DECLINED` | `DECLINED` |
| DECISION_NEEDED | file → channel | `FLAG_HUMAN` | `ESCALATED` |
| Human comment | channel → file | — | Written to decisions/ |

`VERIFIED` is a new string constant added to the protocol vocabulary via
`ConversationProtocol` (P5 is explicitly extensible — domain entry types are dispatched
via `handleDomain()` and `statusAfter()`, not hardcoded).

**VERIFIED semantics:**
- **Terminal status.** `VERIFIED` must be added to `resolvedStatuses` in
  `ConversationRendererConfig` (currently `{"AGREED", "DECLINED"}`). A verified fix is
  resolved — the review tracker should treat it as done, not open.
- **Role constraint: reviewer-only.** VERIFIED means "I checked the diff and the fix is
  correct." The implementor cannot verify their own fix — that's self-grading. This is a
  semantic constraint documented here; enforcement (rejecting VERIFIED entries from
  non-reviewer roles) can be added to `statusAfter()` or the adapter's entry construction.
- **Distinct from AGREED.** AGREED means the reviewer accepts a rejection (the spec
  doesn't change). VERIFIED means the reviewer confirms a fix is correct (the spec
  changed and the change is good). Both are terminal, but they carry different information
  about what happened: AGREED = "no change needed", VERIFIED = "change confirmed correct."

### 6.3 Document timeline mapping

The document timeline is an abstraction over multiple versioning backends, not a
git-history viewer. Different phases produce snapshots from different sources:

| Phase | Snapshot source | Trigger |
|-------|----------------|---------|
| Adversarial review | Git commit | Each implementor round |
| Genesis | Explicit save | Human saves in-progress document |
| Refinement | Explicit save | Human saves after discussion |
| Publisher workflow | Content capture | Draft version snapshot |

For adversarial review, each implementor round produces a git commit. The adapter
captures `commit_before` and `commit_after` as `DocumentSnapshot` instances with
`GitCommit` source. For other phases, snapshots are `ExplicitSave` or
`ContentCapture` sources with content stored directly.

The timeline panel shows:
```
Round 0 (original) ──► Round 1 (+9 issues fixed) ──► Round 2 (+1 issue fixed) ──► Round 3 (verified)
```
Click any two points to see the diff. Issues are anchored to the round they were raised/fixed in.

## 7. UX Considerations

### 7.1 This is not a chat

At 5+ minute intervals between rounds, the UI cannot feel like a messaging app waiting
for a reply. It's closer to court proceedings — written briefs submitted on a clock.

**Progress indicator, not a spinner.** Show which round we're in, which agent is working,
elapsed time. The WebSocket push infrastructure (`DebateWebSocket` + `WebSocketEventBus`,
shipped in the WebSocket push spec) already provides the delivery mechanism. The `.status`
file content could be pushed to the UI via a new `agent-status` topic on the existing
WebSocket connection.

**Batch arrival.** When a reviewer response arrives, 5–10 issues appear at once. The
debate panel should handle this gracefully — a "Round 2: 3 new issues" divider, not
individual entry animations.

**Deep reading between rounds.** While waiting, the user reads and understands previous
rounds. The UI should support: expand/collapse issues, search within issue threads,
cross-reference to spec sections, side-by-side issue content and diff highlight.

**Notification model.** "Round 3 complete — reviewer verified 8 of 9 issues." The user
doesn't need to watch continuously. This is a background process with periodic check-ins.

**Chess clock.** Show each agent's cumulative time and cost. Round duration varies
(round 1: ~10 min; round 3: ~3 min as context is cached and issues converge).

### 7.2 Per-issue threaded view

The reviewer's monolithic markdown has issues as `###` headings. The implementor responds
per-issue with `### R1-02: FIXED`. The reviewer confirms in `## Addressed Items`. This is
a three-message conversation per issue spread across three files.

To show as a threaded conversation per point:
1. Parse reviewer-N.md → individual issues with IDs
2. Parse implementor-N.md → responses keyed by issue ID
3. Parse reviewer-(N+1).md → confirmations keyed by issue ID
4. Group by issue ID → conversation thread

The design-review parser already does steps 1–3. The review tracker panel already groups
by pointId. Feed it the parsed entries and the threading works.

### 7.3 Phase-adaptive layout

The pages workbench layout (using `rows()`, `split()`, `hostPanel()`) adapts to the
current phase:

| Phase | Left panel | Right panel | Bottom panel |
|-------|-----------|-------------|-------------|
| Genesis | Document editor | Conversation | — |
| Refinement | Document (read + select) | Conversation | Suggestion list |
| Adversarial review | Diff view | Debate feed | Review tracker |
| Implementation | Spec (read-only) | Code diff | Implementation checklist |

Panel transitions are smooth — the same components rearrange, they don't reload.

## 8. Open Questions

### Architecture
- **Is the document timeline a first-class data model concept?**
  *Tentative: Yes, first-class.* Non-review use cases (genesis, refinement) don't use
  git, so the timeline can't be derived from git history alone. `DocumentSnapshot` with
  `SnapshotSource` discriminator (§4.1) is the direction.
- ~~How does phase transition work?~~
  *Answered in §4.4:* separate sessions per phase, linked by document ID, human-initiated
  transitions.
- **Where does the adapter live?**
  *Tentative: In DraftHouse server.* Shared abstractions (`ConversationState`, parser
  utilities) go in `casehub-blocks` per P5's pattern. The DraftHouse-specific wiring
  (WebSocket push, `DebateStreamEntry` conversion) stays in `server/runtime`.

### Data model
- ~~Does DraftHouse need a VERIFIED entry type?~~
  *Answered in §6.2:* Yes, as a string constant. `ConversationProtocol` is explicitly
  extensible via `statusAfter()` — add `VERIFIED` alongside existing domain entry types.
- **How do document snapshots relate to ComparisonPair?**
  *Tentative: Yes, a `ComparisonPair` becomes two `DocumentSnapshot` references.* See
  §4.1 data model evolution.
- **What's the identity model for human participants?**
  *Open — needs deeper analysis.* `DebateSession.participants` uses `AgentType` as
  key. Humans need a different identity scheme (e.g., user principal from casehub-eidos).

### Platform
- **Where do extracted document panels live?**
  *Tentative: `blocks-ui-docs` package.* Document panels depend on `ConversationState`
  types from `casehub-blocks` but not on `DebateSession` or DraftHouse-specific wiring.
  A separate package avoids coupling work-item and document concerns.
- ~~How do DraftHouse and blocks-ui panels compose?~~
  *Already answered by pages:* `pages-event` custom events and `hostPanel()` /
  `registerPanel()` are framework-agnostic composition mechanisms. Panels from different
  packages compose in the same workbench.
- **Design token extensions?**
  *Tentative: blocks-ui-core tokens cover it.* Document panels should use the same
  colour ramps, spacing, and typography as work-item panels for visual coherence.

### UX
- **Streaming granularity?**
  *Tentative: Per-round.* The 5–10 minute round cadence suits batch arrival (§7.1).
  Per-issue streaming risks partial display of a round that later fails.
- **Mid-round human intervention?**
  *Open — needs deeper analysis.* Currently design-review's agents don't see human
  input until the next round. Real-time injection would require changes to the
  orchestrator's prompt assembly, not just the UI.
- **Genesis editor technology?**
  *Tentative: Markdown textarea.* Start simple, iterate. A blocks-based editor
  (Notion-style) is a much larger investment with unclear payoff for a dev tool.

## 9. Recommended First Step

**Replay adapter.** Take a completed design-review workspace and render it in DraftHouse.

1. A new MCP tool: `load_workspace(path)` → parses all response files into individual
   `DebateStreamEntry` objects via a direct parser (see §6.1), creates a `DebateSession`,
   pushes entries via `WebSocketEventBus`
2. **Document snapshot serving:** extract `commit_before` and `commit_after` from the
   tracker or response metadata. Serve historical document content via `git show
   <commit>:<path>` for each round's version. The diff panel needs the document at each
   round's snapshot — not the current filesystem content — to show what the reviewer
   actually saw. A new endpoint (e.g., `/api/file/at-commit?path=...&commit=...`) serves
   this. `FileResource` currently reads only from the current filesystem
   (`Files.readString(Paths.get(filePath))`); historical serving is additive.
3. No changes to design-review
4. No live watching (that's step 2)
5. No HIL (that's step 3)
6. Proves the entry-level parsing and document snapshot serving work end-to-end
7. Gives a visual to react to — the first time anyone sees a design-review debate
   rendered as an interactive UI with section highlighting and threaded issue tracking,
   showing the document as it was at each round

This is a weekend's work and delivers immediate value: every completed design-review
becomes browsable in DraftHouse instead of requiring manual markdown file navigation.

## 10. Cross-References

### Related specs
- **P5 Conversation Protocol Extraction** (`docs/superpowers/specs/2026-06-29-p5-conversation-protocol-extraction-design.md`) — the shared conversation model this exploration builds on
- **WebSocket Push Design** (`docs/superpowers/specs/2026-07-03-websocket-push-design.md`) — the push infrastructure the adapter uses
- **Four-Phase Pipeline** (`~/claude/hortora/soredium/docs/specs/2026-07-02-adr-four-phase-review-pipeline.md`) — design-review's evolution; UC6/UC7 correspond to pre-review, UC3 to code review

### Related issues
- **#72** — Review pipeline orchestration (UC1, adapter, four-phase pipeline)
- **#71** — Claude-to-Claude conversation protocol (UC1, §4.5 agent topologies)
- **#84** — Brainstorming UI (UC6, document genesis)
- **#53** — Richer option exploration (UC7, refinement)
- **#60** — Selection-scoped conversation channels (§4.3, concurrent HIL)
