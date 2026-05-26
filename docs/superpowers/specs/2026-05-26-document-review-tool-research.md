# Research Spec: CaseHub DraftHouse

> **Name:** DraftHouse
> **Repo:** `casehubio/drafthouse`
> **Artifact:** `casehub-drafthouse`
> **Package:** `io.casehub.drafthouse`
> **Config prefix:** `casehub.drafthouse`
> **Status:** Research — approved name, pending repo creation
> **Date:** 2026-05-26
> **Origin:** Brainstorming conversation in md-compare session
> **Scope:** Exhaustive capture of all requirements, decisions, insights, and open questions from the design conversation. This document is the input to repo creation and implementation planning.
>
> **Naming rationale:** DraftHouse mirrors DevTown — DevTown is a place where developer agents build software; DraftHouse is a place where editor/writer agents draft and refine documents. "Draft" covers the full lifecycle (first draft, revision, critique, final draft) without implying only editing or only authoring.

---

## 1. Genesis and Context

### 1.1 Starting point

md-compare is an Electron + Quarkus desktop tool for side-by-side markdown comparison. It renders two `.md` files as HTML, computes a line-level LCS diff, shows a colour-coded minimap, and annotates changed blocks inline. Built for comparing writing drafts and style variants.

The existing feature backlog included a Phase 2 item: "Wire `POST /api/critique` to Claude API (streaming)". A 501-stub `CritiqueResource` exists at `POST /api/critique`. The UI has a critique panel placeholder (button disabled, panel hidden).

### 1.2 How the conversation evolved

The critique feature discussion revealed that the original framing (md-compare calls an LLM for critique) was too narrow. Through iterative refinement, the vision expanded significantly:

1. **Started as:** md-compare calls an LLM to critique changes between two documents
2. **Evolved to:** two separate features (before/after pipeline + inline critique)
3. **Merged into:** a single unified MCP tool that any LLM can drive
4. **Expanded to:** multi-LLM support with personality library and conversation channels
5. **Promoted to:** a CaseHub application-tier project built on the agentic harness

Each evolution is documented below with the reasoning that drove it.

---

## 2. Requirements — Exhaustive List

### 2.1 Core concept

An MCP server that any LLM (Claude Code, Claudony, or any MCP client) can use to open a document, show before/after versions, create reviewer LLM agents, and have grounded conversations about specific parts of the document. The tool is both a viewer the LLM controls and a context source that feeds the conversation.

### 2.2 Functional requirements

#### R1 — MCP tool surface
The app exposes MCP tools so an external LLM can drive it. The initiating LLM (e.g. Claude in Claude Code) launches the app, loads documents, creates reviewers, and interacts — all via MCP tool calls.

**Trigger workflow:** "Me and one LLM are talking, we realise we need help. So it uses the MCP to start this app, load the file, create author LLMs, and then we interact via the tool."

#### R2 — Push content to panels
The LLM can push a document to panel A (original/before) and a document (the changed version) to panel B (after). This is the fundamental A/B comparison.

#### R3 — Cursor and selection context
The user can place a cursor or make a text selection in either panel. The tool exposes this context to the LLM — what text is selected, which side (A or B), whether it's in a changed region, and what the corresponding text on the other side looks like.

**Purpose:** enables contextual questions without quoting. "Why is this here?", "What does this mean?", "Why did you change this?" — the LLM sees exactly what the user is pointing at.

#### R4 — Selection-scoped conversation channels
Each text selection or cursor position creates a scoped conversation thread between the user and the LLM(s). A small input appears anchored to the selection. The user types a message, the LLM responds in that thread. Each selection has its own independent conversation channel.

**Key insight from conversation:** "If I highlight an area, or leave the cursor — in the tool there should be a window where I can type this, and it creates a channel for me and the LLM, that is scoped to that selection. Then every selection or area is its own conversation channel."

#### R5 — Document-level conversation
For structural changes or document-wide feedback that doesn't anchor to a single selection, there must be a way to have a conversation about the whole document. The user suggested this might naturally stay in the CLI (the initiating Claude), but a document-level channel in the tool is also possible.

**Open question (flagged by user):** "I don't know how we do structural changes here, or things that span the entire document."

#### R6 — Multi-LLM support (1..n reviewers)
The tool supports multiple LLM reviewers, each running separately with their own memory and space. Each reviewer is a distinct participant with its own identity, perspective, and conversation state.

**"Own memory"** = a folder per LLM instance (conversation history, context files).

#### R7 — Personality library
A library of preconfigured reviewer personalities (e.g. "clarity editor", "devil's advocate", "conciseness reviewer"). The initiating LLM can select from the library or create custom reviewers ad-hoc.

"Both :) it would be nice to have a library of personalities."

#### R8 — Conversation style SPI
How multiple LLMs interact should be pluggable. The user selects the conversation style. Examples: independent opinions, round-robin, consensus.

"I can see a need to SPI this, because this could be a conversation style and the user selects."

#### R9 — Version history via git worktree
Every changed version of the document must be preserved. A git worktree is created for each review session to avoid commits to main. The worktree lifecycle (create, commit each version, clean up) should be mechanical — no user interaction needed for version management.

"The document needs every changed version, maybe via a git worktree — to avoid commits to main."

#### R10 — Multi-document working set
The tool should be able to handle a working set of documents, not just a single file pair. The LLM might provide a tree structure of folders and files to review. The user navigates within the set, selects a file, sees its diff, and has conversations about any of them.

"Another requirement is actually the need to look and review a working set of documents (the LLM would provide, but could be the result of a tree structure of folders)."

#### R11 — Provider-agnostic LLM calls
Must not be locked to Claude or any single provider. LangChain4j provides the decoupling — code against `ChatLanguageModel` / `StreamingChatLanguageModel` interfaces, swap providers via config.

"We should also SPI this so it's not just Claude, or LangChain4j should provide us that decoupling."

Current provider: Vertex AI with environment variables for auth.

#### R12 — GraalVM native image
The Quarkus backend should compile to a native image for fast startup and small footprint.

"We can make it a QuarkusNative application to ensure it's fast and small."

### 2.3 Non-functional requirements

#### NR1 — The LLM decides the workflow
The MCP surface provides tools; the LLM orchestrates. No hardcoded modes or features. Whether the LLM is showing a rewrite, critiquing, or iterating — same tools, LLM decides.

"No feature 1 or feature 2. It's a full MCP tool for which the LLM can push a document to the first and a document (the changed document) to the second. The LLM can optionally critique in either of the windows, with contextual conversations."

#### NR2 — Standalone once launched
The initiating LLM sets up the session (load file, configure reviewers), then the tool runs internally. The initiating LLM can stay connected and participate, or step back. The tool must work standalone after launch — not require a continuous MCP connection for basic functionality.

#### NR3 — CaseHub application tier
The app promotes from `mdproctor/md-compare` to a CaseHub application-tier project in the `casehubio` organisation. It follows CaseHub conventions: parent POM, module structure, CI, naming, harness guide, LAYER-LOG.md.

---

## 3. Architecture Decisions — Made

### D1 — Embed Qhorus, don't connect externally
Qhorus runs inside the app as a dependency, not as a separate service. The Qhorus store abstraction means it can be lifted out later without code changes.

"For this iteration, I'd be OK with embedding this, maybe that changes later."

### D2 — LangChain4j for LLM abstraction
Code against LangChain4j interfaces. `application.properties` picks the provider. Switching providers means swapping the Maven dependency and config lines — zero code changes.

### D3 — Qhorus channels for conversation threading
Each selection-scoped conversation is a Qhorus channel. Typed messages (QUERY, RESPONSE, COMMAND, STATUS) give structure. Correlation IDs thread back-and-forth exchanges. The normative ledger provides a free audit trail.

### D4 — JGit for version management
JGit (pure Java git library) manages worktree lifecycle mechanically inside Quarkus. No shell-out needed, GraalVM native compatible.

### D5 — Hybrid architecture (Approach B)
The app is both an MCP server (external LLMs drive it) and an LLM client (internal LLMs via LangChain4j). External and internal agents participate in the same Qhorus channels.

### D6 — Embedding CaseHub components is acceptable
Any CaseHub dependency can be pulled in. The constraint is pragmatic, not architectural — use what's needed, don't build parallel abstractions.

"I'm completely fine with embedding any parts of CaseHub that we need."

### D7 — Eidos for agent identity (deferred)
Agent identity uses simple UUID + personality name for now. Eidos integration comes in a later epic when Eidos is ready.

---

## 4. Architecture Decisions — Open

### O1 — Where does document-level conversation live?
Options discussed: document-level Qhorus channel in the tool, or just the CLI conversation. Not decided.

### O2 — How do multiple LLM opinions appear in the UI?
Options discussed:
- (A) Separate channels per LLM per selection
- (B) Shared channel, messages interleave, distinguished by sender
- (C) Shared channel, UI groups by sender

Not decided — depends on ReviewStrategy SPI implementation.

### O3 — How does the personality library get stored and managed?
Config files (yaml/json) discussed but not finalised. Questions: where do they live? Can users create and share personalities? Is there a marketplace concept?

### O4 — What Qhorus features are needed vs dormant?
Confirmed needed: channels, typed messages, instance registry, correlation IDs, ledger.
Probably dormant for now: commitments/obligations, watchdogs, cases.
User said: "We have the normative layer, work and cases, but I'm not initially sure how those can be incorporated."

### O5 — Module structure within the CaseHub repo
CaseHub apps typically follow `api/`, `runtime/`, `app/` or similar. The right structure for this app isn't decided. It has a unique constraint: Electron frontend + Quarkus backend + MCP server.

### O6 — Relationship to Claudony
Claudony is the integration tier that manages agent sessions and multi-agent coordination. For now, the app embeds Qhorus directly. Migration path to Claudony orchestration is clean but not planned.

### O7 — How structural changes are handled in selection-scoped channels
Flagged by user as a known gap. Paragraph/sentence-level changes anchor naturally to selections. Restructuring (reordering sections, merging paragraphs) doesn't anchor to a single selection. No solution proposed yet.

---

## 5. Technical Context

### 5.1 Existing md-compare infrastructure

**Diff engine:** `lineDiff()` produces chunks with `{ op, aStart, aEnd, bStart, bEnd }` keyed by line number. Operations: `eq`, `del`, `ins`, `mod` (adjacent del+ins merged).

**DOM annotation:** `annotateRendered()` tags HTML elements with `data-diff-chunk` indices. `annotateWordDiffs()` highlights changed words within mod blocks via DOM-walking LCS diff, preserving inline formatting.

**Minimap:** Canvas-based. Red = A-side changes, green = B-side changes. Click-to-scroll.

**File watch:** SSE `EventSource` on `/api/watch?path=`. Ref-counted per path. Live reload on file change.

**Panels state:** `panels = { a: { path, content, label }, b: { path, content, label } }`. `syncPanelDOM()` renders state to DOM. `swapPanels()` swaps A↔B atomically.

**Critique panel:** Button (`#btn-critique`) disabled with `opacity:.4`. Panel (`#critique-panel`) hidden. Header and body divs exist but empty.

**Test infrastructure:** 54 Playwright tests passing, 2 intentionally skipped. Shared JVM via `global-setup.js`. Suite runs in ~10s.

### 5.2 Qhorus capabilities relevant to this app

**Channels:** Named, typed, durable message surfaces with declared update semantics (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE). Channels persist across reconnections and carry full normative history.

**Typed messages:** 9-type speech-act taxonomy: QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, EVENT.

**Instance registry:** Agents register with capability tags and three addressing modes (by id, by capability, by role).

**Correlation IDs:** Thread back-and-forth exchanges within a channel.

**MessageObserver SPI:** Global broadcast across all channels. Cross-cutting concerns (monitoring, logging).

**ChannelBackend SPI:** Per-channel targeted delivery. `HumanParticipatingChannelBackend` bridges human input into Qhorus message dispatch, threading correlationId through the pipeline.

**Normative ledger:** Every message creates a `MessageLedgerEntry` with SHA-256 tamper evidence. Full audit trail comes automatically.

**Channel gateway:** Backend-agnostic fan-out. Multiple backends can register on the same channel.

**MCP tool surface:** ~50 tools exposed via `QhorusMcpTools` at the `/mcp` Streamable HTTP endpoint.

**Normative channel layout:** 3-channel pattern (work/observe/oversight) with type constraints. This pattern could map to review sessions but isn't confirmed as the right topology.

### 5.3 CaseHub platform position

**Tier:** Application (alongside devtown, aml, clinical, life, quarkmind)

**Dependencies:**
```
casehub-qhorus          — channels, messaging, instances, ledger
casehub-platform-api    — Path, Preferences (if needed)
casehub-eidos (later)   — agent identity
LangChain4j             — provider-agnostic LLM calls
JGit                    — version history via worktrees
```

**Not needed initially:** casehub-engine (no case orchestration), casehub-work (no human task inbox/SLA), casehub-connectors (no outbound notifications).

**Build order position:** After casehub-qhorus, before nothing (leaf node in the dependency graph).

**Agentic harness guide applies:** LAYER-LOG.md, tutorial layers, blog entries, GitHub issues/epics.

### 5.4 LangChain4j in Quarkus

**Multi-model:** Quarkus LangChain4j supports multiple named model instances via `@Named` beans. Each gets its own provider config in `application.properties`.

**Provider decoupling:** Code against `ChatLanguageModel` / `StreamingChatLanguageModel`. The Quarkus extension `quarkus-langchain4j-vertex-ai-anthropic` handles Vertex auth, streaming, and SSE.

**Switching providers:** Swap Maven dependency + config. Zero code changes.

**Current provider:** Vertex AI with environment variables for auth.

**GraalVM readiness:** LangChain4j's Vertex provider is GraalVM-ready in Quarkus.

### 5.5 Market landscape (as of 2026-05-26)

**Nothing like this exists.** Exhaustive search found:

- **[word-mcp-live](https://github.com/ykarapazar/word-mcp-live)** — live-edits Word docs with tracked changes. Word-specific, no markdown, no side-by-side diff.
- **[code-review-mcp](https://github.com/praneybehl/code-review-mcp)** — LLM-powered critique of code diffs. Not prose.
- **[LiquidText](https://www.lawnext.com/2025/04/liquidtext-innovative-annotation-tool-for-complex-documents-adds-real-time-collaboration.html)** — desktop annotation tool with notes sidebar. No LLM integration, no MCP, not open source.
- **[ultimate_mcp_server](https://github.com/Dicklesworthstone/ultimate_mcp_server)** — multi-model evaluation/comparison + document processing. Generic, not purpose-built for prose review.
- **MCP Apps spec (Jan 2026)** — allows servers to return interactive UI, but nothing in the wild uses it for document comparison.

The combination of MCP-driven + markdown diff + multi-LLM review + selection-scoped conversations is genuinely novel.

---

## 6. Proposed MCP Tool Surface

| Tool | Purpose |
|---|---|
| `start_review(paths, reviewers?)` | Create a review session: git worktree, load document(s), optionally spin up reviewer agents. `paths` can be a single file, a list, or a directory tree. |
| `add_reviewer(personality, model?)` | Add a reviewer agent from the personality library or ad-hoc with custom spec |
| `push_revision(content, file?)` | Push a new document version to panel B (or a specific file in a multi-doc set), auto-committed to worktree |
| `get_cursor_context()` | Read the user's current selection: text, side (A/B), diff state, surrounding context, corresponding text on the other side |
| `get_threads()` | List active conversation threads (selection-scoped channels) |
| `post_to_thread(thread_id, message)` | Post to a selection-scoped conversation |
| `get_diff(file?)` | Current diff state between panels A and B |
| `list_versions(file?)` | Git log of all document versions in the worktree |
| `show_version(ref, panel?)` | Load a specific version into either panel |
| `end_review(keep_worktree?)` | Close session, clean up or keep worktree for later merge |

---

## 7. Proposed Channel Topology

```
review-{sessionId}/
  ├── overview       APPEND   Document-level conversation (structural feedback)
  ├── sel/{hash}     APPEND   Per-selection conversation threads (created on demand)
  └── observe        APPEND   Telemetry only (allowedTypes: EVENT)
```

For multi-document working sets, channels are scoped per file:
```
review-{sessionId}/
  ├── overview                   APPEND   Cross-document conversation
  ├── doc/{fileHash}/overview    APPEND   Per-document conversation
  ├── doc/{fileHash}/sel/{hash}  APPEND   Per-selection threads
  └── observe                    APPEND   Telemetry
```

---

## 8. Proposed Review Session Lifecycle

```
start_review("essay.md")
  1. git worktree add .reviews/{session-id} -b review/{session-id}
  2. Copy source document(s) into worktree
  3. Commit: "review: initial version"
  4. Load into panel A
  5. Create Qhorus channels (overview, observe)
  6. Register reviewer agents as Qhorus instances (if requested)

push_revision(content)
  1. Write new content to worktree
  2. Commit: "review: version N"
  3. Panel A = previous version, Panel B = new version
  4. Diff updates automatically via existing SSE watch

User selects text → creates sel/{hash} channel on demand
  1. Check if channel exists for this selection range
  2. If not, create APPEND channel
  3. Show anchored chat input
  4. User types → QUERY dispatched on channel
  5. Reviewer LLMs respond as RESPONSE

end_review(keep_worktree=true)
  1. User chooses: keep worktree (merge later) or discard
  2. Clean up Qhorus channels (or preserve for audit)
  3. Remove worktree if discarded
```

---

## 9. MVP Scope

The user explicitly requested a fast MVP:

> "I want an MVP quickly. So an LLM can open up a document and do A/B testing. Then we just continue to build out from there. We should try bring in Qhorus for the channels, and get some MVP going there too."

### MVP includes:
- **MCP tool surface** — `start_review`, `push_revision`, `get_cursor_context`, `get_diff`, `end_review` (minimum viable set)
- **A/B document comparison** — LLM pushes content to panel A and B, user sees rendered diff (leverages existing diff engine)
- **Qhorus channels** — at least one channel per review session for basic conversation threading
- **Single LLM reviewer** — one internal reviewer agent via LangChain4j
- **Git worktree versioning** — mechanical version history for each revision
- **CaseHub repo scaffolding** — new repo in casehubio, parent POM integration

### MVP defers:
- Multi-LLM support and personality library
- ReviewStrategy SPI
- Selection-scoped conversation channels (requires UI work)
- Multi-document working sets
- Eidos identity integration
- Normative layer features (commitments, obligations)
- GraalVM native image (build JVM-first, native later)

---

## 10. Post-MVP Roadmap (ordered by conversation priority)

1. **Selection-scoped conversation channels** — the core interaction model. Each selection creates a Qhorus channel with a chat input anchored to the selection.
2. **Multi-LLM reviewers** — multiple LLMs with independent memory, each registered as Qhorus instances with capability tags.
3. **Personality library** — preconfigured reviewer profiles (yaml/json). Initiating LLM selects from library or creates ad-hoc.
4. **ReviewStrategy SPI** — pluggable conversation styles (independent, round-robin, consensus).
5. **Multi-document working sets** — tree of files navigable within the tool, per-file diff and conversations.
6. **Document-level conversation** — overview channel for structural and whole-document feedback.
7. **Eidos identity** — agent identity generation when Eidos is ready.
8. **GraalVM native image** — compile to native for fast startup and small footprint.
9. **Claudony migration** — lift Qhorus out, point Claudony at MCP endpoint for orchestration.

---

## 11. Technology Stack

| Concern | Choice | Notes |
|---|---|---|
| UI shell | Electron | Existing, proven. Spawns Quarkus server. |
| Backend | Quarkus 3.34+ | Current md-compare version. Will align to CaseHub parent Quarkus version. |
| LLM abstraction | LangChain4j (Quarkus extension) | `quarkus-langchain4j-vertex-ai-anthropic` for Vertex. Provider-agnostic. |
| Auth | Vertex AI via ADC / environment variables | Current setup. No Anthropic API key. |
| Messaging | Qhorus (embedded) | Channels, typed messages, instances, ledger. |
| Version control | JGit | Pure Java. Worktree lifecycle. GraalVM compatible. |
| Build | Maven (CaseHub parent POM) | Inherits shared config, CI, formatting. |
| Testing | Playwright (E2E) + JUnit (server) | Existing infrastructure. |
| Native image | GraalVM (deferred) | Post-MVP. |
| Java version | 21 (on Java 26 JVM) | CaseHub convention. |

---

## 12. Key Design Insights from Conversation

### 12.1 "The LLM decides"
The most important design principle. The MCP surface provides tools; the LLM orchestrates the workflow. There are no modes, no features to select, no hardcoded flows. The same tools serve "show me your rewrite", "critique this paragraph", and "iterate on this section while I watch".

### 12.2 "Features merged into one"
The original separation of "before/after pipeline" and "inline critique" was artificial. The user realised mid-conversation: "Actually thinking aloud, we can merge this. The only difference really between two is Claude may make the change at the start, even without back and forth. I think we can almost just keep this free flow."

### 12.3 "Cursor as grounding"
The cursor/selection is the user's way of pointing and saying "talk to me about *this*." It replaces quoting, copy-pasting, and describing. The LLM receives rich context: selected text, position, diff state, corresponding text on the other side.

### 12.4 "Channels not features"
Selection-scoped conversations map naturally to Qhorus channels. Each selection is a channel. The channel infrastructure (typed messages, correlation IDs, ledger) comes free. The conversation style (how multiple LLMs interact in a channel) is an SPI, not a hardcoded policy.

### 12.5 "Every version preserved"
Git worktrees solve version history without polluting the user's main branch. The tool manages the worktree lifecycle mechanically. No user interaction needed for version management. Full history available for comparison between any two versions.

### 12.6 "CaseHub application, not standalone tool"
Promoting to CaseHub gives the app awareness of the platform's infrastructure. It follows established conventions, gets CI for free, and can leverage any foundation component. The application tier rule applies: domain logic (document review, prose refinement) stays in this repo; foundation primitives are used as-is.

---

## 13. Infrastructure Changes Required

### 13.1 New repository
- Create `casehubio/drafthouse` in the casehubio GitHub organisation
- Module structure TBD (likely simpler than a full Quarkus extension — this is an app, not a library)

### 13.2 Parent POM integration
- Add to `casehub-parent` build order (leaf node, after casehub-qhorus)
- Add to dependency map in PLATFORM.md
- Add to APPLICATIONS.md
- Create `docs/repos/casehub-drafthouse.md` deep-dive

### 13.3 Migration from mdproctor/md-compare
- Existing code, tests, and git history migrate to the new repo
- `mdproctor/md-compare` becomes archived or a redirect
- Electron + Quarkus architecture stays the same
- Sparge symlink dependency may need revisiting (Electron binary, node_modules)

### 13.4 CI workflows
- Standard CaseHub CI: build, test, format check
- Playwright tests need Electron binary available in CI
- Opt-in in full-stack build (application tier convention)

---

## 14. Naming — Decided

**Name: DraftHouse**

| Element | Value |
|---|---|
| GitHub repo | `casehubio/drafthouse` |
| groupId | `io.casehub` |
| artifactId | `casehub-drafthouse` |
| Root Java package | `io.casehub.drafthouse` |
| Config prefix | `casehub.drafthouse` |
| Feature name | `drafthouse` |

**Naming journey:** Scriptorium (taken — [cgueret/Scriptorium](https://github.com/cgueret/Scriptorium)), salon (hair salon confusion), redpen (not collaborative enough), copydesk (too narrow — excludes authoring), inkwell, pencraft, marginalia, scholia — all considered. DraftHouse selected because it mirrors DevTown (place where agents work), covers the full draft→critique→revise→refine lifecycle, and doesn't constrain the tool to only editing or only authoring.

**Names rejected and why:**
- Scriptorium — existing software project in the same domain (e-book authoring tool)
- Salon — too easily confused with hair salon
- Redpen / CopyDesk — implies only editing, not authoring. If the tool later generates and revises content, the name doesn't fit.
- Pencraft — leans toward authoring more than review
- Marginalia / Scholia — evocative but obscure

---

## 15. Open Questions Summary

| # | Question | Context |
|---|----------|---------|
| O1 | Where does document-level conversation live? | CLI vs in-tool overview channel |
| O2 | How do multiple LLM opinions appear in the UI? | Per-LLM channels vs shared channel with grouping |
| O3 | How is the personality library stored and managed? | Config files, user-creatable, shareable? |
| O4 | Which Qhorus features are dormant vs active? | Commitments, watchdogs, cases — needed or not? |
| O5 | What module structure within the CaseHub repo? | api/runtime/app vs simpler layout |
| O6 | How does the app relate to Claudony long-term? | Embed Qhorus directly vs Claudony orchestration |
| O7 | How are structural changes handled? | Restructuring doesn't anchor to selections |
| O8 | What happens to the Sparge symlink dependency? | Electron binary and node_modules currently from Sparge |
| O9 | What Quarkus version? | md-compare is 3.34; CaseHub parent is 3.32.2 |
| ~~O10~~ | ~~How does the app name?~~ | Resolved — DraftHouse (see §14) |
