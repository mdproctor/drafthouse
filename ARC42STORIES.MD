# DraftHouse — ARC42STORIES.MD

**Spec:** Arc42Stories v0.1
**Profile:** CaseHub — Application tier
**Profile ref:** `../parent/docs/arc42stories-casehub-profile.md` · fallback: `https://raw.githubusercontent.com/casehubio/parent/main/docs/arc42stories-casehub-profile.md`
**Prefix:** DH

---

## §1 Introduction and Goals

DraftHouse is an MCP-driven document review tool. An LLM client opens a document, loads before/after versions, initiates reviewer agents, and conducts selection-scoped conversations grounded in specific document regions. It runs as a Quarkus application with a browser-based diff UI and a Qhorus-backed review pipeline, designed for eventual embedding in Claudony as a channel view type.

**Stakeholders**

| Stakeholder | Interest |
|---|---|
| LLM clients (Claude Code, Claudony, any MCP client) | MCP tool surface for session control |
| Document authors | Comparison rendering, change navigation |
| Document reviewers | Structured critique scoped to selections |
| CaseHub platform team | Alignment with Qhorus channel model and Claudony plugin API |

**Top quality goals**

| # | Goal | Scenario |
|---|---|---|
| 1 | Portability | Domain logic imports into Claudony without framework changes |
| 2 | MCP-first API | All review session control available as MCP tools |
| 3 | Provider replaceability | Platform AgentProvider replaces LangChain4j without domain changes |

**Artifact schema**

| Artifact type | Format | Example | Where it lives |
|---|---|---|---|
| Improvement log entry | `DH-NNN` | `DH-042` | `docs/PROGRESS.md` |
| Issue | `#NNN` or `casehubio/drafthouse#NNN` | `#24` | GitHub Issues |
| Garden entry | `GE-YYYYMMDD-XXXXXX` | `GE-20260601-85afd0` | `~/.hortora/garden/` |
| Protocol | `PP-YYYYMMDD-XXXXXX` | `PP-20260601-abc123` | `casehub-parent/docs/protocols/` |
| ADR | `ADR-NNNN` | `ADR-0001` | `docs/adr/` |
| Blog entry | `YYYY-MM-DD-[initials]NN-title` | `2026-05-25-mdp01-bug-that-count-was-hiding` | `blog/` |
| Design spec | `YYYY-MM-DD-topic-design` | `2026-06-01-review-manifest-design` | `docs/superpowers/specs/` |

---

## §2 Constraints

| Constraint | Detail |
|---|---|
| Java 17 | Sealed types, exhaustive switches on `EntryType` |
| Quarkus 3.34.3 | Platform BOM version |
| casehub-qhorus 0.2-SNAPSHOT | Pre-release; `ChannelProjection` SPI (qhorus#230 ✅ shipped) |
| casehub-platform-agent-api 0.2-SNAPSHOT | Platform AgentProvider SPI — provider-agnostic LLM invocation |
| CaseHub parent BOM | `casehubio/parent` — no dependency version overrides outside BOM |
| Browser-only UI | No Electron; npm build via Quinoa at compile time — no Node.js at runtime |
| Filesystem access | Documents read from local filesystem; no upload or remote fetch |
| casehub-pages-push 0.2-SNAPSHOT | Typed push protocol SDK — wire builders, topic registry, event store |

---

## §3 Context and Scope

```mermaid
C4Context
  title DraftHouse — System Context
  Person(llm_client, "LLM Client", "Claude Code, Claudony, or any MCP client")
  System(drafthouse, "DraftHouse", "MCP-driven document review — comparison rendering and structured LLM critique")
  System_Ext(filesystem, "Local Filesystem", "Source documents — Markdown, text")
  System_Ext(qhorus, "Qhorus Channel Mesh", "Agent communication — QUERY/RESPONSE commitment loop")
  System_Ext(agent_api, "Platform AgentProvider", "Reviewer LLM — via casehub-platform-agent-api")

  Rel(llm_client, drafthouse, "MCP tools — start_review, update_selection, query_review, end_review")
  Rel(llm_client, drafthouse, "Browser — ?a=path&b=path")
  Rel(drafthouse, filesystem, "GET /api/file, SSE /api/watch")
  Rel(drafthouse, qhorus, "ChannelProjection<ReviewState>, DebateEvent commits")
  Rel(drafthouse, agent_api, "casehub-platform-agent-api")
```

**System boundaries**

| In scope | Out of scope |
|---|---|
| Document loading and comparison rendering | Document storage and version control |
| Review session lifecycle (start → rounds → close) | Review outcome actioning |
| Reviewer agent dispatch via Qhorus | Agent identity management (casehub-eidos) |
| MCP tool surface | UI hosting and distribution packaging |

**Platform references**

- `../parent/docs/PLATFORM.md` — capability ownership table and boundary rules
- `../parent/docs/repos/drafthouse.md` — what this application owns (parent#145 ✅ closed)

---

## §4 Solution Strategy

**Before:** `mdproctor/md-compare` — a standalone Electron app; IPC-based file loading; npm build pipeline; no review capability.

**After:** Quarkus application serving a browser UI; MCP tools replacing Electron IPC; pure-Java review domain enabling Claudony embedding.

What this strategy establishes:
- **Standalone today, Claudony plugin tomorrow** — diff engine, domain pipeline, and review anchoring are pure-Java with no UI framework coupling; when Claudony's plugin API stabilises they import as JARs unchanged
- **MCP-first API** — LLM clients control review sessions via tools, not browser UI; the browser is a visualisation surface only
- **Platform AgentProvider** — `PlatformDebateAgentProvider @DefaultBean` backed by `AgentProvider.invoke()`; `ClaudeAgentSdkDebateAgentProvider` in claude-agent displaces it when on classpath
- **Minimal delta sequencing** — infrastructure established first; domain model second; MCP surface and channel integration third; each Chapter adds one concern

**Chapter sequencing rationale:**
- C1 before C2: Quarkus application and module structure required before Qhorus and AgentProvider can be wired
- C2 before C3: Framework dependencies required before `DebateAgentProvider` and `ChannelProjection` can compile and test
- C3 before C4: `ReviewSessionService` and `DebateAgentProvider` required before MCP tools can be implemented
- C4 and C5 independent: MCP surface does not depend on Qhorus `DebateChannel` type; channel type does not depend on MCP surface; either can ship first

---

## §5 Building Block View

**Module structure**

| Module | Type | Responsibilities |
|---|---|---|
| `server/api/` | Pure-Java JAR | Domain model — `DebateEvent`, `ReviewState`, `ReviewPoint`, `DebateAgentProvider` SPI; no Quarkus or Qhorus compile-scope deps |
| `server/runtime/` | Quarkus app | All JAX-RS resources, Qhorus wiring, platform AgentProvider, push protocol; depends on `server/api/` |
| `server/claude-agent/` | Optional JAR | `ClaudeAgentSdkDebateAgentProvider` — displaces `PlatformDebateAgentProvider @DefaultBean` when on classpath |
| `index.html` | Browser UI | Diff engine (LCS), word-level highlights, canvas minimap, scroll sync — no build step |
| `styles.css` | Browser assets | Archive Room CSS tokens, panel/diff/minimap styles |

**Layer architecture**

```mermaid
C4Container
  title DraftHouse — Layer Architecture
  Person(client, "LLM Client", "Claude Code / Claudony / MCP client")

  Container_Boundary(b1, "Layer: Scaffold") {
    Container(quarkus, "Quarkus App", "Java 21 / Quarkus 3.34.3", "Serves UI and file API; SSE file watch")
    Container(ui, "Browser UI", "HTML/JS — index.html", "Diff renderer, minimap, scroll sync")
  }

  Container_Boundary(b2, "Layer: Platform Wiring") {
    Container(api_mod, "server/api", "Pure Java", "Domain model — ports and types only; no framework deps")
    Container(qhorus_dep, "casehub-qhorus", "Quarkus ext", "Channel mesh, ChannelProjection SPI")
    Container(agent_api_dep, "casehub-platform-agent-api", "Platform SPI", "AgentProvider — provider-agnostic LLM invocation")
    Container(push_dep, "casehub-pages-push", "Platform SDK", "Typed push protocol — PushMessage, PushRequest, TopicRegistry")
  }

  Container_Boundary(b3, "Layer: Review Domain") {
    Container(debate_pkg, "debate/ package", "Pure Java (server/api)", "DebateEvent sealed type, ReviewState fold state, DebateAgentProvider SPI")
    Container(projector, "SummaryProjector", "Pure Java SPI (server/api)", "ChannelProjection<ReviewState> — incremental fold; discovered by Qhorus at startup")
    Container(agents, "PlatformDebateAgentProvider", "Quarkus CDI @DefaultBean", "Production reviewer via AgentProvider")
    Container(session_svc, "ReviewSessionService", "Quarkus CDI", "JGit session storage; fold recovery on restart")
  }

  Rel(client, quarkus, "MCP tools — DraftHouseMcpTools (#24)")
  Rel(client, ui, "Browser — ?a=&b=")
  Rel(quarkus, api_mod, "compiles against")
  Rel(quarkus, qhorus_dep, "ChannelProjection, event commits")
  Rel(quarkus, lc4j, "@AiService reviewer")
  Rel(projector, qhorus_dep, "implements ChannelProjection<ReviewState>")
  Rel(session_svc, projector, "fold recovery on startup")
```

---

## §6 Runtime View

**Scenario 1 — File comparison**

Browser loads `?a=<path>&b=<path>`, fetches both files from `/api/file`, computes LCS diff client-side, renders line blocks with word-level highlights and a canvas minimap. An `EventSource` on `/api/watch` reloads either file without a page refresh when a change is detected.

```mermaid
sequenceDiagram
  participant C as Browser
  participant S as Quarkus Server
  participant FS as Filesystem

  C->>S: GET /api/file?path=a.md
  S->>FS: read
  FS-->>S: content
  S-->>C: content A

  C->>S: GET /api/file?path=b.md
  S->>FS: read
  FS-->>S: content
  S-->>C: content B

  C->>C: LCS diff → line blocks → word-level diff → minimap render
  C->>S: EventSource /api/watch?path=a.md
  S-->>C: SSE change event → re-fetch + re-render
```

**Scenario 2 — Review session (DraftHouseMcpTools #24)**

LLM client opens a review session; `ReviewerChannelBackend` handles QUERY messages via `DocumentReviewer @AiService` and dispatches DONE (agree), RESPONSE (qualify), or DECLINE to the Qhorus channel based on `ReviewResult.Outcome`.

```mermaid
sequenceDiagram
  participant L as LLM Client (MCP)
  participant D as DraftHouseMcpTools
  participant Q as Qhorus Channel
  participant A as DocumentReviewer (@AiService)

  L->>D: start_review(docAPath, docBPath) → sessionId
  L->>D: update_selection(sessionId, side, text)
  L->>D: query_review(sessionId, question)
  D->>Q: dispatch(QUERY, question)
  Q->>A: ReviewerChannelBackend.post() → llm.review(...)
  A-->>Q: dispatch(RESPONSE, answer)
  L->>D: end_review(sessionId, deleteChannel)
```

---

## §7 Deployment View

| Component | Value |
|---|---|
| Build output | `server/runtime/target/drafthouse-server-runner.jar` |
| Run command | `java -Dui.dir=<project-root> -jar drafthouse-server-runner.jar` |
| Default port | `9001` — override via `quarkus.http.port` |
| UI resolution | `-Dui.dir` JVM property → `UiResource` reads `index.html` and `styles.css` |
| Session storage | `~/.drafthouse/reviews/` (JGit; override via `casehub.drafthouse.storage.root`) |
| Qhorus datasource | H2 in-memory with `MODE=PostgreSQL` for dev/test; PostgreSQL named `qhorus` for prod |
| Build command | `mvn -f server/pom.xml package -DskipTests` |

DraftHouse deploys as a single JVM process. No container or orchestration configuration exists.

---

## §8 Crosscutting Concepts

**Protocol references**

| Concern | Protocol |
|---|---|
| Module tier structure | `docs/protocols/universal/module-tier-structure.md` |
| CDI displacement (`@DefaultBean` / `@Alternative`) | `docs/protocols/casehub/alternative-extension-patterns.md` |
| Maven coordinate naming | `docs/protocols/universal/maven-coordinate-standard.md` |
| Capability ownership | `../parent/docs/PLATFORM.md` — Capability Ownership table |

**Anti-patterns**

**Symptom:** `ClaudeAgentSdkDebateAgentProvider` activates in tests and the reviewer produces no output.
**Cause:** `claude-agent/` added as a `<dependency>` in `server/runtime/pom.xml` before platform#55 ships; `@Alternative @Priority(1)` promotes the stub above the `@DefaultBean` LangChain4j provider with no warning.
**Fix:** Remove `claude-agent` from `server/runtime/pom.xml`. Re-add only when Maven Central coordinates from platform#55 are verified.

**Symptom:** Quarkus startup fails with `AiServicesProcessor` NPE or `@AiService` parameter names unresolved.
**Cause:** `<maven.compiler.parameters>true</maven.compiler.parameters>` is absent from `server/runtime/pom.xml`; `AiServicesProcessor` requires compiled parameter names at startup.
**Fix:** Add `<maven.compiler.parameters>true</maven.compiler.parameters>` to the `maven-compiler-plugin` configuration in `server/runtime/pom.xml`.

**Symptom:** `mvn test -pl runtime` fails with `Could not find artifact io.casehub.drafthouse:api`.
**Cause:** Selective `-pl` runs bypass the reactor install phase; `runtime` cannot resolve `api` from the local Maven repository.
**Fix:** Run `mvn install -DskipTests` on the full reactor before any selective `-pl runtime` test run.

---

## §9 Journeys and Chapters

### §9.1 Journey Overview

| Journey | Description | Chapters | Status |
|---|---|---|---|
| Document Comparison | Load two markdown files, render side-by-side diff with line highlights, word-level annotations, canvas minimap, and scroll sync | C1 | ✅ Complete |
| Document Review | Initiate a structured debate review: reviewer agents critique selection-scoped document regions via a Qhorus QUERY→RESPONSE commitment loop; state accumulated incrementally | C2–C5 | In progress |

### §9.2 Chapter Index

| # | Chapter | Journey | Layers touched | Delta summary | Status |
|---|---|---|---|---|---|
| 1 | CaseHub Migration & Scaffold | Comparison | Scaffold | High | ✅ |
| 2 | Platform Wiring | Review | Platform Wiring | High | ✅ |
| 3 | Review Manifest Domain | Review | Review Domain | High | ✅ |
| 4 | MCP Review Interface | Review | MCP Surface | High | ✅ |
| 5 | Debate Channel | Review | Debate Channel | Medium | ✅ |

**Layer × Chapter matrix**

| Layer | C1 | C2 | C3 | C4 | C5 |
|---|---|---|---|---|---|
| Scaffold | High | — | — | — | — |
| Platform Wiring | — | High | — | — | — |
| Review Domain | — | — | High | — | — |
| MCP Surface | — | — | — | High | — |
| Debate Channel | — | — | — | — | Medium |

**Sequencing rationale:**
- C1 before C2: Quarkus application and Maven module structure must exist before Qhorus and LangChain4j can be wired and tested
- C2 before C3: `casehub-qhorus` and `quarkus-langchain4j` compile-scope dependencies required before `DebateAgentProvider` and `ChannelProjection` implementations can compile
- C3 before C4: `ReviewSessionService` and `DebateAgentProvider` required before MCP tools have a session to control
- C4 and C5 independent: MCP surface does not depend on Qhorus `DebateChannel` type; channel type does not require MCP tools; either can ship first

```mermaid
flowchart LR
  C1["C1: Migration & Scaffold\nScaffold"] --> C2["C2: Platform Wiring\n+ Platform Wiring"]
  C2 --> C3["C3: Review Manifest Domain\n+ Review Domain"]
  C3 --> C4["C4: MCP Review Interface\n+ MCP Surface"]
  C3 --> C5["C5: Debate Channel\n+ Debate Channel"]

  style C1 fill:#90EE90,color:#000
  style C2 fill:#90EE90,color:#000
  style C3 fill:#90EE90,color:#000
  style C4 fill:#90EE90,color:#000
  style C5 fill:#90EE90,color:#000
```

### §9.3 Chapter Entries

---

#### Chapter 1 — CaseHub Migration & Scaffold

**Journey:** Comparison | **Sequence:** 1 of 5 | **Status:** ✅
**Delivered:** 2026-05-26 | **Issues:** #15 | **Blog:** `blog/2026-05-25-mdp01-bug-that-count-was-hiding.md`, `blog/2026-05-25-mdp02-scroll-sync-two-invisible-bugs.md`, `blog/2026-05-25-mdp03-one-jvm-to-rule-them-all.md`

**What this delivers**
DraftHouse runs as a Quarkus application serving a browser-based side-by-side diff viewer. Two markdown files are loaded via URL query params (`?a=` and `?b=`), compared with LCS line diff and word-level highlights, and rendered in a scrollable split pane with a canvas minimap. File changes stream via SSE without a page reload.

**Accountability gaps closed**
- Electron dependency → Browser-only Quarkus architecture

**Layer Impact**
| Layer | Delta |
|---|---|
| Scaffold | High |

---

#### Chapter 2 — Platform Wiring

**Journey:** Review | **Sequence:** 2 of 5 | **Status:** ✅
**Delivered:** 2026-05-31 | **Issues:** #21 (epic #20) | **Blog:** 🔲

**What this delivers**
The server splits into `api/` (pure-Java domain) and `runtime/` (Quarkus), establishing the module boundary that keeps domain logic portable. Qhorus and LangChain4j are wired and validated in test. No reviewer is active yet — this Chapter establishes the prerequisites that C3 requires.

**Accountability gaps closed**
- Single-module structure → `api/` + `runtime/` split

**Layer Impact**
| Layer | Delta |
|---|---|
| Platform Wiring | High |

---

#### Chapter 3 — Review Manifest Domain

**Journey:** Review | **Sequence:** 3 of 5 | **Status:** ✅
**Delivered:** 2026-06-02 | **Issues:** #29 (epic #20) | **Blog:** 🔲

**What this delivers**
The review system can model a structured debate — questions, answers, rejections, and round boundaries — as an incremental event stream committed to a Qhorus channel. A LangChain4j reviewer agent processes document selections and commits `DebateEvent` instances; `SummaryProjector` folds state incrementally, recovering on restart without replay. The domain is pure-Java and embeddable.

**Accountability gaps closed**
- No debate domain model → `DebateEvent` sealed type
- No incremental state → `SummaryProjector` `ChannelProjection<ReviewState>`
- No agent abstraction → `DebateAgentProvider` SPI

**Layer Impact**
| Layer | Delta |
|---|---|
| Review Domain | High |

---

#### Chapter 4 — MCP Review Interface

**Journey:** Review | **Sequence:** 4 of 5 | **Status:** ✅
**Issues:** #24, #25 (epic #20 closed) | **Blog:** ✅ `wksp/blog/2026-06-04-mdp05-mcp-surface-and-a-stale-snapshot.md`

**What this delivers**
✅ `DraftHouseMcpTools`: `start_review`, `update_selection`, `query_review`, `end_review`. Replaces deprecated REST stubs (`ReviewSessionResource` deleted). `ReviewerChannelBackend` fixed to read live session from registry on each `post()` call.

✅ `ReviewSessionRegistryImpl @ApplicationScoped` extracted from `ReviewerChannelBackendFactory` — session storage decoupled from the channel-init observer. `ReviewerChannelBackendFactory` sole responsibility is now the `@Observes ChannelInitialisedEvent` backend registration.

✅ `ReviewSessionLifecycleTest @QuarkusTest` — integration test proving QUERY→RESPONSE→Commitment FULFILLED lifecycle end-to-end through `DraftHouseMcpTools`. Awaitility required: `ChannelGateway.fanOut()` delivers to backends on virtual threads (GE-20260605-494ed0). `*IT.java` naming bug fixed — original class never ran under `mvn test` (GE-20260512-493c90). `DebateRoundTripIT` naming fixed in the same pass.

**Layer Impact**
| Layer | Delta |
|---|---|
| MCP Surface | High |

---

#### Chapter 5 — Debate Channel

**Journey:** Review | **Sequence:** 5 of 5 | **Status:** ✅
**Delivered:** 2026-06-08 | **Issues:** #27 | **Blog:** 🔲

**What this delivers**

Peer-to-peer MCP debate channel for two LLM agents (REV reviewer, IMP implementer). Agents post structured debate entries — raise points, respond with agree/dispute/qualify/counter, escalate to human via flag — into a Qhorus APPEND channel. State is folded by `DebateChannelProjection` into `ReviewState`; `DebateMcpTools.get_debate_summary` renders the current state as markdown.

Key architectural decisions shipped:
- `ReviewChannelProjection` (renamed from `DebateChannelProjection`) — preserves existing review Q&A fold over `message.type()`; implements `ChannelProjection<ReviewState>` only (no `render()`)
- `DebateChannelProjection` (new) — dispatches on `META:` header in message content; uses `content.META.agent` for REV/IMP classification rather than `actorType` (both debate agents are `ActorType.AGENT`)
- `ReviewStatus.DISPUTED` — non-terminal dispute state, distinct from terminal `ReviewStatus.DECLINED`; renders as ⚡ without strikethrough in `SummaryRenderer`
- `EntryType.COUNTER` — new entry type preserving the qualify/counter distinction in the domain model
- Qhorus `artefactRefs` runtime constraint: the field accepts only CSV UUIDs at dispatch time despite its String API type; debate metadata moved to `DebateProtocol.META_SENTINEL + "entryType=...|agent=...|round=...\n\n<body>"` content prefix to avoid this constraint — sentinel is SOH (U+0001) + `DHMETA:` to prevent collision with natural LLM output (#39)

**Layer Impact**
| Layer | Delta |
|---|---|
| Debate Channel | Medium |

---

#### Chapter 6 — Sub-Agent Architecture

**Journey:** Review | **Sequence:** 6 of 5 | **Status:** ✅
**Delivered:** 2026-06-09 | **Issues:** #26 | **Blog:** `wksp/blog/2026-06-09-mdp11-the-handler-that-knew-nothing.md`

**What this delivers**

Focused sub-agent spawning within debate sessions. Main agents (REV/IMP) hold accumulated context across rounds; sub-agents receive deliberately minimal, fresh-context input for one analytical task (VERIFY, ARBITRATE, DEEP_ANALYSIS, CONSISTENCY_CHECK, NEUTRAL_SUMMARY, CUSTOM) and return findings posted back to the debate channel with provenance markers. Reasoning memos per round externalise working hypotheses for cold-session continuity.

Key architectural decisions shipped:
- `ChannelAgentHandler` SPI + `ChannelAgentDispatcher @ObservesAsync` — channel-reactive agent pattern: `DebateChannelBackend.post()` fires `ChannelAgentRequest` CDI event; dispatcher routes to the matching handler bean, invokes `DebateAgentProvider`, posts `SUB_TASK_FINDING` or sanitized `SUB_TASK_ERROR`
- `AbstractDebateSubAgentHandler` — shared `handles()` (SubTaskType match from META) + `buildResponse()` (SUB_TASK_FINDING encoding); six concrete handler beans implement only `taskType()` + `prepareTask()`
- `EntryType.name()` encoding standard — all `entryType` values in META headers use the Java enum name (uppercase); `DebateChannelProjection.apply()` dispatches via `EntryType.valueOf()` switch (type-safe, compiler-exhaustive)
- `ReviewState` extended with `memos` (List<RoundMemo>) and `subTaskFindings` (Map<String,SubTaskFinding>); `SummaryRenderer` renders findings with `⊕` provenance marker (fresh context) and memos in "Agent Memos" section
- Pattern extraction tracked in #42 (channel-reactive agent — devtown confirmed as second consumer)
- E2E test (`SubAgentE2ETest`) shipped in Chapter 7 once #44 CDI fix landed

**Layer Impact**
| Layer | Delta |
|---|---|
| Debate Channel | High |
| Pattern types | Medium |

---

#### Chapter 7 — Restart-from-Round + CDI Fix

**Journey:** Review | **Sequence:** 7 of 5 | **Status:** ✅
**Delivered:** 2026-06-09 | **Issues:** #40 #43 #44 #47 | **Blog:** —

**What this delivers**

Round-bounded session restart and CDI augmentation fix. Agents can branch a debate session from any prior round — either resuming an expired session (pass the last round) or redoing from an earlier point (pass an earlier round). The original session stays live. Sub-agent findings filter correctly by round after a fix to `request_subagent` and `AbstractDebateSubAgentHandler.buildResponse`.

Key architectural decisions shipped:
- `RoundBoundedProjection` static inner class of `DebateChannelProjection` — pure `ChannelProjection<ReviewState>` decorator; constructed by `DebateMcpTools` (which owns `projectionService`); skips messages where `round > maxRound`; no service dependencies added to the fold bean
- `DebateProtocol.parseRound(Map)` extracted as package-public static — replaces the private duplicate in `DebateChannelProjection`; shared by the outer fold and the inner decorator
- RESTART_CONTEXT provenance marker — DHMETA-structured (`entryType=RESTART_CONTEXT`) but NOT an `EntryType` enum constant; `DebateChannelProjection.apply()` intercepts by string check before `EntryType.valueOf()`, avoiding `SummaryRenderer`'s exhaustive switch breakage
- `request_subagent` tool now requires `round` parameter — encoded as `|round=N` in the SUB_TASK_REQUEST header; `AbstractDebateSubAgentHandler.buildResponse` propagates it into SUB_TASK_FINDING so findings filter correctly in bounded projections
- Two new MCP tools: `get_debate_summary_at_round(debateSessionId, round)` and `restart_from_round(debateSessionId, round)` with full failure cleanup, `round ≥ 1` enforcement, and empty-state sentinel handling
- `casehub-platform` added as `runtime` scope to `server/runtime/pom.xml` — fixes pre-existing Quarkus CDI augmentation failure (#44); provides `MockCurrentPrincipal @DefaultBean` for Qhorus JPA store injection points
- `SubAgentE2ETest` — full async dispatch chain E2E test in `@QuarkusTest`; Awaitility covers `@ObservesAsync` gap; `MockDebateAgentProvider` displaces LangChain4j
- `SummaryRendererTest` extended with 7 coverage tests for rendering paths added in Chapter 6 (PENDING/ERROR/COMPLETE findings, point-anchored vs standalone, memo section, null-finding guard)

**Layer Impact**
| Layer | Delta |
|---|---|
| Debate Channel | High |
| CDI wiring | Medium |

---

#### Chapter 8 — N-Participant Debate Sessions

**Journey:** Review | **Sequence:** 8 of 5 | **Status:** ✅
**Delivered:** 2026-06-10 | **Issues:** #41 (sub-project 1 of 4) #49 #48 #46 #45 | **Blog:** —

**What this delivers**

Lifts the two-party (REV/IMP) constraint from the debate session layer. Any number of participants — REV, IMP, SUPERVISOR, MODERATOR, SELECTOR — can now post to a debate channel. Session management and projection folding are both updated. Also delivers a batch of quality improvements from code review (#49, #48, #46, #45).

Key architectural decisions shipped:
- `AgentType` enum extended — REV, IMP, SUPERVISOR, MODERATOR, SELECTOR; migration to configuration file deferred explicitly
- `DebateSession` record → class — a live session with dynamic participants is not a value type; `ConcurrentHashMap<AgentType, String> participants` replaces fixed `revInstanceId`/`impInstanceId` fields; stays in `api/` module (pure Java)
- `DebateSession.registerIfAbsent(AgentType, Supplier<String>)` — encapsulates `computeIfAbsent` inside the class to prevent callers from calling it on the unmodifiable view (which would throw `UnsupportedOperationException`); documented retry semantics on exception path (supplier retry is safe because `InstanceService.register()` is an upsert)
- `DebateSession.instanceId(AgentType, String)` static method — single source of truth for the `"drafthouse-{role}-{sessionId}"` naming convention; shared by production code and tests
- Lazy participant registration via `sender(session, AgentType)` — `start_debate` still registers REV + IMP eagerly; all other roles lazy-register on first use; cleanup blocks iterate `session.participants().values()` (live unmodifiable view) to cover any number of registered participants
- `DebateChannelProjection.agentType()` fold fix — previously a hardcoded switch that threw `IllegalArgumentException` for unknown agents; `ProjectionService.fold()` has no exception handling so any throw terminates the fold and permanently breaks the session; now returns null with ERROR (missing field) or WARNING (unknown value) log; three call sites (handleRaise, appendToPoint, handleFlagHuman) check null and return state
- `findingsIncluded` renamed to `findingsComplete` + `findingsPending` added (#49) — PENDING sub-tasks have no resolved content; the old count conflated requests with actual findings
- `start_debate` specPath JSON escaping fix (#48) — was using manual quote wrapping; now uses the existing `jsonString()` helper consistently

**Layer Impact**
| Layer | Delta |
|---|---|
| Debate Channel | High — `agentType()` fold safety, N-participant projection |
| Session Model | High — `DebateSession` record → class, participants map |

---

### §9.4 Layer Entries

---

#### Layer — Scaffold

**Participates in chapters:** C1
**Architectural patterns:** Browser-served static assets, URL query param initialization, SSE live reload
**Key protocols:** `docs/protocols/universal/module-tier-structure.md`
**Design refs:** `docs/superpowers/specs/2026-05-25-scroll-sync-anchors-design.md`, `docs/superpowers/specs/2026-05-25-shared-jvm-test-infra-design.md`
**Issues:** #15
**Navigation:** `git log --grep="#15" --oneline`
**Blog:** `blog/2026-05-25-mdp01-bug-that-count-was-hiding.md`, `blog/2026-05-25-mdp02-scroll-sync-two-invisible-bugs.md`, `blog/2026-05-25-mdp03-one-jvm-to-rule-them-all.md`
**Improvement refs:** —
**Completed:** 2026-05-26

#### What it adds

**Before:** `mdproctor/md-compare` — Electron app; IPC-based file loading; npm build pipeline; no CaseHub identity.

**After:** `UiResource @ApplicationScoped` — catch-all JAX-RS resource serving `index.html` and `styles.css` from a configurable `-Dui.dir` JVM property.

What this layer adds:
- **Browser-served UI** — `UiResource` reads static assets from `-Dui.dir`; eliminates npm, Sparge, and the Electron process manager; one JVM process serves both the API and the UI
- **URL query param initialization** — `?a=<path>&b=<path>` replaces Electron IPC for initial document loading; relative API URLs (`/api/file`) replace hardcoded `127.0.0.1:${port}` references
- **SSE file watch** — `/api/watch?path=` streams change events; browser re-fetches and re-renders without a page reload
- **CaseHub identity** — registered in `casehubio/parent` BOM; foundation modules (Qhorus, LangChain4j) now resolvable as managed dependencies

Not closed here: Qhorus integration (no channel mesh wiring), review agent API (no reviewer), multi-module Maven split (single module)

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No CaseHub identity | Foundation modules unreferenceable in parent BOM | Parent POM + BOM registration |
| Electron dependency | Requires npm + Sparge for development and test | Quarkus-only, browser-served architecture |

#### Key files

- `server/runtime/src/main/java/io/casehub/drafthouse/UiResource.java` — catch-all JAX-RS resource; serves `index.html` and `styles.css` from `ui.dir`
- `server/runtime/src/main/java/io/casehub/drafthouse/FileResource.java` — `GET /api/file?path=`; reads arbitrary local paths
- `server/runtime/src/main/java/io/casehub/drafthouse/WatchResource.java` — `GET /api/watch?path=`; SSE stream of file-change events
- `index.html` — entire browser UI: LCS diff engine, word-level highlights, canvas minimap, scroll sync, markdown rendering via marked.js
- `styles.css` — Archive Room CSS tokens; panel, diff, and minimap layout styles

#### Key wiring

**`-Dui.dir` JVM property** — required at launch; `UiResource` reads `index.html` and `styles.css` from this path. Omitting it causes 404 for all browser requests; no default is configured.

**`?a=<path>&b=<path>` query params** — paths are passed verbatim to `GET /api/file`; the server applies no sandboxing. Valid for a local-only tool; must be revisited before any networked deployment.

#### Architectural decisions

**Why browser over Electron:** Electron adds a Node.js process manager, npm, and Sparge packaging — three build-time dependencies for a distribution concern. The browser UI served by the Quarkus process reduces deployment to a single JVM invocation. Tradeoff: no native file dialog — replaced with `prompt()` for now; the MCP tool surface is the intended long-term document-loading mechanism.

#### Pattern introduced

Browser-served Quarkus UI with configurable `ui.dir`, relative API URLs, and URL query param initialization.

#### Pattern anchor

`UiResource.java` — catch-all resource serving static assets from the `-Dui.dir` JVM property.

#### Gotchas

**Symptom:** Word-level diff highlights appear on headings only — paragraphs with changed words are silently skipped with no error.
**Cause:** marked.js paragraph tokens have `rawLines = 0` (the trailing newline lives in the following space token, not the paragraph token itself). The position tracker in `annotateRendered` uses `rawLines` to advance; with `rawLines = 0` it stalls, making the overlap check `c.aStart < tokenEnd` fail for chunks starting at the current position.
**Fix:** Use `Math.max(tokenEnd, line + 1)` for the overlap check only — `line` still advances by the actual `rawLines` so subsequent tokens remain correctly positioned.

**Symptom:** Native file picker not available in the browser UI; document loading requires typing a path into a modal text box.
**Cause:** Removing Electron IPC removed access to Node's `dialog.showOpenDialog`. The browser has no equivalent for a locally-served app without `<input type="file">` and a file upload flow. The replacement is `window.prompt()` — synchronous, no picker, no drag-drop.
**Fix (interim):** URL query params (`?a=<path>&b=<path>`) handle initial document loading from the command line. The intended long-term fix is the MCP tool surface (`start_review`), which passes paths programmatically without user interaction.

#### Pattern to replicate

1. Add a catch-all JAX-RS resource (`@GET @Path("{path:.*}")`) that reads files from a configurable JVM system property (e.g. `System.getProperty("ui.dir")`)
2. Pass the property at launch: `java -Dui.dir=/path/to/ui -jar app-runner.jar`
3. Use relative URLs for all API calls in the frontend (`/api/file`, not `http://127.0.0.1:9001/api/file`)
4. Pass initial state via URL query params (`?a=<path>&b=<path>`); read them in the browser on `DOMContentLoaded` and fetch via the relative API
5. Wire an `EventSource` for live reload: `new EventSource('/api/watch?path=' + path)` → re-fetch and re-render on each `message` event

---

#### Layer — Platform Wiring

**Participates in chapters:** C2
**Architectural patterns:** Hexagonal split (api/runtime), named datasource, `@DefaultBean` CDI baseline
**Key protocols:** `docs/protocols/universal/module-tier-structure.md`, `docs/protocols/casehub/alternative-extension-patterns.md`
**Design refs:** `docs/superpowers/specs/2026-05-30-critique-backend-spi-design.md`, `docs/superpowers/specs/2026-05-29-quarkus-playwright-e2e-design.md`
**Issues:** #21 (epic #20)
**Navigation:** `git log --grep="#21" --oneline`
**Blog:** 🔲
**Improvement refs:** —
**Completed:** 2026-05-31

#### What it adds

**Before:** Single-module Quarkus app in `server/` — domain and serving code co-located; no Qhorus or LangChain4j on the classpath.

**After:** `server/api` (pure-Java JAR) + `server/runtime` (Quarkus application) — two-module Maven split; `casehub-qhorus 0.2-SNAPSHOT` and `quarkus-langchain4j-anthropic 1.9.1` wired as runtime dependencies.

What this layer adds:
- **api/runtime module split** — `server/api/` holds domain types and port interfaces with no heavy framework deps; `server/runtime/` holds the Quarkus application; domain logic tests without Quarkus startup and imports into Claudony without dragging the framework
- **Qhorus channel mesh dependency** — `casehub-qhorus 0.2-SNAPSHOT` on the `runtime/` classpath; `ChannelProjection` SPI resolvable; H2 `qhorus` named datasource wired for dev and test
- **LangChain4j dependency** — `quarkus-langchain4j-anthropic 1.9.1` on the `runtime/` classpath; `@AiService` annotation available for `DebateAgentProvider` implementations

Not closed here: Review domain model (no `DebateEvent` or agent)

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Single-module structure | Domain types coupled to Quarkus; can't separate for Claudony embedding | `api/` + `runtime/` Maven split |
| No Qhorus dependency | `ChannelProjection` SPI not available; channel-based projection can't compile | `casehub-qhorus 0.2-SNAPSHOT` |
| No LangChain4j | `@AiService` unavailable; reviewer agent interface can't compile | `quarkus-langchain4j-anthropic 1.9.1` |
| No qhorus datasource | Qhorus extension fails Quarkus startup without its named datasource | H2 `qhorus` named datasource + `MODE=PostgreSQL` |

#### Key files

- `server/api/pom.xml` — pure-Java JAR; no Quarkus or Qhorus compile-scope dependencies; declares domain types only
- `server/runtime/pom.xml` — Quarkus app; depends on `server/api/`; declares `casehub-qhorus` and `quarkus-langchain4j-anthropic`
- `server/runtime/src/main/resources/application.properties` — `qhorus` named datasource config; CORS profile scoping (`%dev`, `%test` only); port 9001

#### Key wiring

**`<maven.compiler.parameters>true</maven.compiler.parameters>` in `server/runtime/pom.xml`** — required by `AiServicesProcessor`; without it, `@AiService` method parameter names are absent at compile time and the processor fails at Quarkus startup. The failure message does not mention the missing compiler flag. (GE-20260525-a8bd9a)

**`quarkus-langchain4j 1.9.1` — not `0.x`** — the `0.26.1` line is incompatible with Quarkus 3.33+. Version `1.9.1` was built against 3.33.1 and is validated on 3.34.3. Any downgrade into the `0.x` line breaks at startup.

**`qhorus` named datasource with `MODE=PostgreSQL`** — the Qhorus extension requires a `javax.sql.DataSource` named `qhorus` at startup; the error when it is absent names the CDI resolution failure, not the datasource. In dev/test, H2 with `MODE=PostgreSQL` in the JDBC URL preserves dialect compatibility.

**CORS scoped to `%dev` and `%test` profiles** — wildcard CORS removed from the default profile; cross-origin requests work in dev/test only.

#### Architectural decisions

**Why api/runtime split before domain model:** Splitting after domain types exist requires migrating every class already referenced by Quarkus injection points — higher blast radius. At the infrastructure stage, no domain types exist yet; the split costs one Maven module with minimal merge risk. Tradeoff: one extra reactor module to maintain.

**Why LangChain4j 1.9.1 specifically:** The `quarkus-langchain4j` groupId and artifact coordinate scheme changed between 0.x and 1.x. The `casehubio/parent` BOM selects `1.9.1`. Overriding the BOM version without verifying Quarkus compatibility produces silent startup failures.

#### Pattern introduced

Two-module hexagonal split: `api/` (pure-Java port interfaces and domain types) + `runtime/` (Quarkus application with all framework dependencies).

#### Pattern anchor

`server/api/pom.xml` and `server/runtime/pom.xml` — the split boundary and dependency ownership are explicit in the Maven declarations.

#### Gotchas

**Symptom:** `mvn test -pl runtime` fails with `Could not find artifact io.casehub.drafthouse:api`.
**Cause:** Selective `-pl` runs bypass the reactor install phase; `api` is not installed to the local Maven repository.
**Fix:** Run `mvn install -DskipTests` on the full reactor before any selective `-pl runtime` test run.

**Symptom:** Quarkus startup fails with a `CDI resolution failed` error that does not mention the datasource.
**Cause:** `qhorus` named datasource missing or misconfigured; the Qhorus extension requires it at startup and reports the failure as a CDI error.
**Fix:** Verify `quarkus.datasource.qhorus.db-kind` and the JDBC URL in `application.properties`. For dev/test, ensure the H2 URL includes `?MODE=PostgreSQL`.

**Symptom:** `AiServicesProcessor` NPE or unresolved `@AiService` method parameter names at Quarkus startup.
**Cause:** `<maven.compiler.parameters>true</maven.compiler.parameters>` absent from `server/runtime/pom.xml`.
**Fix:** Add the property to the `maven-compiler-plugin` configuration. (GE-20260525-a8bd9a)

#### Pattern to replicate

1. Create `server/api/` as `<packaging>jar</packaging>`; declare no Quarkus or Qhorus compile-scope dependencies — domain types and port interfaces only
2. Create `server/runtime/` as the Quarkus application; add `server/api/` as a `<dependency>`; declare `casehub-qhorus` and `quarkus-langchain4j-anthropic` here only
3. Add `<maven.compiler.parameters>true</maven.compiler.parameters>` to `maven-compiler-plugin` in `server/runtime/pom.xml`
4. Configure a `qhorus` named datasource in `application.properties` — H2 with `?MODE=PostgreSQL` for dev/test profiles
5. Run `mvn install -DskipTests` on the full reactor before any selective `-pl runtime` test run

---

#### Layer — Review Domain

**Participates in chapters:** C3
**Architectural patterns:** Sealed algebraic type (Java 17), incremental fold via `ChannelProjection`, `@DefaultBean` / `@Alternative @Priority(N)` provider swap
**Key protocols:** `docs/protocols/casehub/alternative-extension-patterns.md`
**Design refs:** `docs/superpowers/specs/2026-06-01-review-manifest-design.md`, `docs/superpowers/specs/2026-06-02-review-manifest-layer2-impl-design.md`
**Issues:** #29 (epic #20)
**Navigation:** `git log --grep="#29" --oneline`
**Blog:** 🔲
**Improvement refs:** #32 (minor quality improvements from code review — formatter sorting, `Clock` injection in renderer)
**Completed:** 2026-06-02

#### What it adds

**Before:** No review domain — DraftHouse loads and displays files; no model of debate structure, no reviewer, no session.

**After:** `DebateEvent` (sealed Java 17 type) + `SummaryProjector implements ChannelProjection<ReviewState>` + `LangChain4jDebateAgentProvider @DefaultBean` — debate structure modeled, projected incrementally, reviewer invocable.

What this layer adds:
- **Sealed debate event type** — `DebateEvent` with six variants (`RoundStarted`, `QuestionAsked`, `AnswerProvided`, `AgentRejected`, `RoundEnded`, `SessionClosed`); exhaustive `switch` enforced by the compiler; no runtime `MatchException` possible on a complete switch
- **Incremental fold via Qhorus projection** — `SummaryProjector implements ChannelProjection<ReviewState>`; Qhorus calls `fold()` on each new commit; state computation is O(1) per event, not O(n) replay
- **Pure-Java processing pipeline** — `DebateParser`, `RoundParser`, `SummaryRenderer`, `DebateEntryFormatter` in `server/api/`; no Quarkus or UI coupling; importable in Claudony as a plain JAR
- **Provider abstraction** — `DebateAgentProvider` SPI; `LangChain4jDebateAgentProvider @DefaultBean` runs in all environments; `ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1)` displaces it transparently when platform#55 ships
- **JGit session persistence** — `ReviewSessionService` stores sessions under `~/.drafthouse/reviews/` by default (configurable via `casehub.drafthouse.storage.root`); each session is a Git repo; state recovered on restart by replaying commits through `SummaryProjector`

Not closed here: `ProjectionRenderer` SPI (`SummaryRenderer` renders locally; qhorus#232 ✅ shipped — migration to `ChannelProjection` SPI completed in #31 ✅)

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No debate domain model | Can't represent question/answer/rejection structure | `DebateEvent` sealed type |
| No incremental state | Full replay on every state read; O(n) cost for long sessions | `SummaryProjector` `ChannelProjection<ReviewState>` |
| No pure-Java pipeline | Domain coupled to Quarkus; can't embed in Claudony | `debate/` package in `server/api/` |
| No agent abstraction | Can't swap LangChain4j for Claude Agent SDK without domain changes | `DebateAgentProvider` SPI |
| No session persistence | Review state lost on app restart | `ReviewSessionService` JGit storage |

#### Key files

- `server/api/src/main/java/io/casehub/drafthouse/debate/DebateEvent.java` — sealed interface with six `record` variants; exhaustive pattern matching anchor
- `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewState.java` — immutable fold state: `currentReview`, `rounds`, `lastEventId`
- `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewPoint.java` — selection anchor: file path, line range, tokenized text
- `server/api/src/main/java/io/casehub/drafthouse/debate/DebateAgentProvider.java` — SPI interface; one method per debate round invocation
- `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryProjector.java` — plain Java, no CDI; `ChannelProjection<ReviewState>`; incremental fold over `DebateEvent` commits; discovered by Qhorus SPI at startup
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/LangChain4jDebateAgentProvider.java` — `@DefaultBean @ApplicationScoped`; production reviewer via `quarkus-langchain4j-anthropic`
- `server/claude-agent/src/main/java/io/casehub/drafthouse/debate/claude/ClaudeAgentSdkDebateAgentProvider.java` — `@Alternative @Priority(1) @ApplicationScoped` stub; awaits platform#55 coordinates
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionService.java` — JGit session lifecycle; fold recovery on startup

#### Key wiring

**`@Alternative @Priority(1)` on `ClaudeAgentSdkDebateAgentProvider`** — promotes this bean above `@DefaultBean` when the `claude-agent/` module is on the classpath. The module must not appear in `server/runtime/pom.xml` until platform#55 delivers real Maven coordinates; adding it while the class is a stub silently displaces the LangChain4j provider in all environments including CI.

**`SummaryProjector` registered as `ChannelProjection<ReviewState>`** — requires `casehub-qhorus-api` in `server/api/` and `casehub-qhorus` in `server/runtime/`. Qhorus calls the fold function on each new commit, not on read; state is current without polling.

**`CommitId` must be deterministic** — `ReviewSessionService` uses commit IDs derived from round number or content hash. Timestamp-based IDs produce different fold sequences in test vs production; `CommitId.of(roundNumber)` is stable.

#### Architectural decisions

**Why sealed `DebateEvent` over a base interface:** A sealed type forces the compiler to reject any `switch` omitting a variant. Without sealing, a new event variant added to `server/api/` produces no compile error in `server/runtime/` switch expressions — only a runtime `MatchException`. Tradeoff: all variants must reside in `server/api/`; the sealed hierarchy is closed to external extension.

**Why `@DefaultBean` for LangChain4j and `@Alternative @Priority(1)` for Claude Agent SDK:** Two provider implementations coexist. `@DefaultBean` keeps LangChain4j active in all environments unless an alternative is present; `@Alternative @Priority(1)` displaces it transparently when the SDK module is on the classpath. This removes the need for a feature flag or runtime configuration property. Tradeoff: the active provider is determined by dependency graph membership; adding `claude-agent/` prematurely to `runtime/` activates the stub with no explicit signal.

**Why pure-Java pipeline in `server/api/`:** Claudony is the primary integration target. A pipeline coupled to Quarkus CDI or REST would require Claudony to embed a Quarkus runtime, which is not viable as a plugin. A plain-JAR pipeline imports without framework startup. Tradeoff: CDI injection unavailable in `server/api/`; pipeline classes are instantiated directly, not injected.

#### Pattern introduced

1. Domain model as sealed algebraic type (Java 17)
2. Incremental state folding via Qhorus `ChannelProjection<S>`
3. Pure-Java processing pipeline in `api/` for cross-tool embedding
4. Provider abstraction with `@DefaultBean` (production) / `@Alternative @Priority(N)` (displacement)

#### Pattern anchor

- `io.casehub.drafthouse.debate.DebateEvent` — sealed type definition and variant hierarchy
- `io.casehub.drafthouse.debate.SummaryProjector` — `ChannelProjection<ReviewState>` implementation and fold logic

#### Gotchas

**Symptom:** `ClaudeAgentSdkDebateAgentProvider` activates in tests and the reviewer produces no output.
**Cause:** `claude-agent/` added to `server/runtime/pom.xml` before platform#55 ships; `@Alternative @Priority(1)` displaces the `@DefaultBean` LangChain4j provider silently.
**Fix:** Remove `claude-agent` from `server/runtime/pom.xml`. Re-add only once Maven Central coordinates from platform#55 are verified. (GE-20260602-093fea adjacent)

**Symptom:** `SummaryProjector` produces stale or out-of-order state.
**Cause:** Commit IDs derived from `System.currentTimeMillis()` produce different sequences across test runs.
**Fix:** Use `CommitId.of(roundNumber)` or a UUID derived from content hash; never use wall-clock time as a commit ID source.

#### Pattern to replicate

1. Define the event type as `sealed interface EventType permits Variant1, Variant2, ...` in `server/api/`; each variant is a `record` implementing the interface
2. Define the fold state as an immutable `record FoldState(...)`; each fold step returns a new instance
3. Implement `ChannelProjection<FoldState>` in `server/runtime/`: `fold(FoldState current, EventType event)` returns updated state
4. Define the agent SPI in `server/api/`; implement with `@DefaultBean` in `server/runtime/`; place the displacing implementation in a separate module with `@Alternative @Priority(1)`
5. Keep the separate module out of `server/runtime/pom.xml` until its Maven coordinates are production-ready
6. Store session state as JGit commits; recover on startup by replaying commits through `ChannelProjection.fold()`
7. Keep all pipeline classes (`Parser`, `Renderer`, `Formatter`) in `server/api/`; instantiate directly; test with plain JUnit without Quarkus startup

---

#### Layer — Debate Channel

**Participates in chapters:** C5
**Architectural patterns:** No-op `ChannelBackend` as registration fence, sentinel content-header encoding for debate metadata (`DebateProtocol.META_SENTINEL`), two-projection split (`ChannelProjection` vs `RenderableProjection`), `ReviewStatus.DISPUTED` as non-terminal state
**Key protocols:** `docs/protocols/channel-projection-actor-type.md` (PP-20260607-508f7b)
**Design refs:** `docs/superpowers/specs/2026-06-07-debate-channel-design.md`
**Issues:** #27, #33, #38, #39
**Navigation:** `git log --grep="#27" --oneline`
**Blog:** 🔲
**Completed:** 2026-06-08

#### What it adds

**Before:** Two agents (REV, IMP) have no write path into the Qhorus channel. `DebateChannelProjection` (now renamed `ReviewChannelProjection`) folds review Q&A state but cannot distinguish two `ActorType.AGENT` actors.

**After:** `DebateMcpTools` with 6 tools at this layer (`start_debate`, `raise_point`, `respond_to`, `flag_human`, `get_debate_summary`, `end_debate`; extended to 10 tools in Chapters 6–7). `DebateChannelBackend` (no-op) fences debate channels from the LLM backend. `DebateChannelProjection` folds `DebateProtocol.META_SENTINEL` content headers into `ReviewState` with correct REV/IMP attribution and `ReviewStatus.DISPUTED` for active disputes.

What this layer adds:
- **`DebateChannelProjection` — new class** — `RenderableProjection<ReviewState>` dispatching on `DebateProtocol.META_SENTINEL + entryType=...` prefix in message content; `META.agent` for REV/IMP classification; `META.round` populates `ThreadEntry.round` and `FlagEntry.round`
- **`ReviewChannelProjection` — renamed from `DebateChannelProjection`** — retains `message.type()` dispatch and `actorType`-based classification for review Q&A channels; narrows from `RenderableProjection` to `ChannelProjection<ReviewState>` (no dead `render()`)
- **`ReviewStatus.DISPUTED`** — non-terminal status for active debate disputes; `SummaryRenderer` renders as ⚡ without strikethrough
- **`EntryType.COUNTER`** — new entry type preserving qualify/counter distinction in the domain model; `SummaryRenderer.typeLabel` switch updated
- **`DebateChannelBackend @ApplicationScoped`** — stateless no-op backend; registration fence preventing `ReviewerChannelBackendFactory` from attaching the LLM reviewer to debate channels
- **`DebateChannelBackendFactory`** — observes `ChannelInitialisedEvent`; deregister-then-register pattern for `drafthouse/debate/` channels; idempotent on restart
- **`DraftHouseInstances`** — package-private constants class; `HUMAN_INSTANCE_ID` extracted from `DraftHouseMcpTools` for cross-class reference by `DebateMcpTools`

Closed in follow-up session (#33 #38 #39): instance deregistration on session end, `[QUALIFY]` prefix retired (replaced by `ReviewResult.Outcome` enum + DONE/RESPONSE dispatch), `META:` sentinel replaced by `DebateProtocol.META_SENTINEL`.

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No debate write path | Agents cannot post to the debate channel | `DebateMcpTools` (6 tools at this layer; 10 total after Chapters 6–7) |
| `actorType` misclassification | Both REV and IMP are `ActorType.AGENT`; old heuristic produced wrong attribution | `META.agent` field in content header |
| Terminal `DECLINED` for dispute | Disputed points rendered as closed (🚫, strikethrough) while still live | `ReviewStatus.DISPUTED` (⚡, no strikethrough) |
| LLM backend on debate channel | `ReviewerChannelBackendFactory` would trigger LLM for every QUERY (raise_point) | `DebateChannelBackend` registration fence + guard in `ReviewerChannelBackendFactory` |

#### Key files

- `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java` — session record: channelId, debateSessionId, channelName, revInstanceId, impInstanceId
- `server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java` — SPI interface; find/put/remove keyed on UUID
- `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java` — enum with new COUNTER value
- `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewStatus.java` — enum with new DISPUTED value
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateProtocol.java` — sentinel constant `META_SENTINEL` (SOH + `DHMETA:`); shared by `DebateMcpTools` and `DebateChannelProjection`
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java` — `RenderableProjection<ReviewState>`; `DebateProtocol.META_SENTINEL` header dispatch with WARNING log on unknown entryType
- `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewChannelProjection.java` — `ChannelProjection<ReviewState>`; DONE→AGREE, RESPONSE→QUALIFY, DECLINE→DECLINED, HANDOFF→PENDING_HUMAN dispatch (renamed from `DebateChannelProjection`)
- `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackend.java` — no-op backend; `BACKEND_ID="drafthouse-debate"`
- `server/runtime/src/main/java/io/casehub/drafthouse/DebateChannelBackendFactory.java` — observes `ChannelInitialisedEvent` for `drafthouse/debate/` prefix
- `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java` — 6 MCP tools at this layer (10 total after Chapters 6–7); `sender()` switch; session precondition; `DebateProtocol.META_SENTINEL` encoding; instance deregistration on end/partial-failure
- `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseInstances.java` — `HUMAN_INSTANCE_ID` constant

#### Key wiring

**`DebateChannelBackendFactory` and `ReviewerChannelBackendFactory` guard:** Both observe `ChannelInitialisedEvent`. `ReviewerChannelBackendFactory` returns early if channel name starts with `drafthouse/debate/`. `DebateChannelBackendFactory` returns early if channel name does NOT start with `drafthouse/debate/`. The two factories are mutually exclusive by channel prefix.

**`META:` content encoding:** Qhorus `artefactRefs` validates UUID-only content at dispatch time despite its String API type. Debate metadata (entryType, agent, round, priority, scope, location) is encoded as `META:key=value|...\n\n<body>` in the message content field. `DebateChannelProjection.parseMeta()` strips and parses the header; `bodyContent()` returns the human-readable text for thread entries.

**`start_debate` ordering:** `registry.put()` before `channelGateway.initChannel()` — `DebateChannelBackendFactory` reads from the registry during the synchronous `ChannelInitialisedEvent` CDI event. Same constraint as `start_review`.

#### Architectural decisions

**Why `ChannelProjection` (not `RenderableProjection`) for `ReviewChannelProjection`:** `ReviewerChannelBackend` calls `conversationRenderer.render()` directly — `projection.render()` is never invoked on review channels. Narrowing the interface eliminates dead code and makes the contract explicit.

**Why metadata in content rather than `artefactRefs`:** Qhorus `ArtefactRefParser` validates UUID-only content at dispatch time. The `MessageDispatch.Builder.artefactRefs(String)` API is a String, but the runtime rejects non-UUID comma-separated tokens. Using a `META:` header in content is the available escape hatch without requiring a Qhorus API change.

**Why `ReviewStatus.DISPUTED` instead of reusing `DECLINED`:** In the review channel, `DECLINED` is terminal (LLM declined to answer; point closed). In the debate channel, dispute is non-terminal (thread continues with counter/qualify/agree). Using the same status would incorrectly render disputed debate points as closed (strikethrough) while the debate is live.

#### Gotchas

**Symptom:** `MessageService.dispatch()` throws or rolls back when `artefactRefs` contains `key=value` strings.
**Cause:** Qhorus `ArtefactRefParser` validates artefactRefs as CSV UUIDs. Despite the String API type, non-UUID content fails at dispatch time.
**Fix:** Never put free-form metadata in `artefactRefs`. Use `META:` content header encoding for application-layer metadata.

**Symptom:** `DebateChannelProjection` discards a debate entry silently (state unchanged, no exception).
**Cause 1:** Message content does not start with `META:` — no entryType parsed, switch falls through to default.
**Cause 2:** Message content starts with `META:` but `entryType` value is unknown.
**Cause 3:** `META.agent` is present but value is not `REV` or `IMP`.
**Fix:** Verify the `DebateMcpTools` dispatch is encoding the header correctly; check logs for `WARNING` entries from the projection.

---

## §10 Architectural Decisions

Cross-cutting decisions not captured inline in layer or Chapter entries.

---

### ADR-0001 — Standalone today, Claudony plugin tomorrow

**Context:** Claudony's plugin API is not yet stable. Building DraftHouse as a Claudony plugin now would bind it to a pre-release API that is still changing.

**Decision:** Build as a standalone Quarkus tool. Keep the diff engine, review domain, and LLM critique anchoring as pure-Java modules with no UI framework coupling. Design MCP and channel surfaces as patterns compatible with Claudony's channel architecture.

**Consequences:** DraftHouse ships independently of Claudony's release cycle. When Claudony's plugin API stabilises, `server/api/` imports as a plain JAR without modification. The Quarkus serving layer and browser UI become optional wrappers around the same domain. The constraint on `server/api/` is permanent: no Quarkus or Qhorus compile-scope dependencies may be added there.

---

## §11 Quality Requirements

| # | Quality goal | Scenario | Measure |
|---|---|---|---|
| 1 | Portability | Domain logic embedded in Claudony without framework changes | `server/api/` has no Quarkus or Qhorus compile-scope dependencies |
| 2 | MCP-first API | All session control available as MCP tools | `start_review`, `update_selection`, `query_review`, `end_review` exposed (#24 ✅) |
| 3 | Provider replaceability | LangChain4j replaced with Claude Agent SDK transparently | `@DefaultBean` / `@Alternative` pattern; tests pass with either provider |
| 4 | Test coverage | Domain pipeline and agent contract tested without UI | Unit and integration tests in `server/runtime/` pass CI; 51 tests passing at C3 close |

---

## §12 Risks and Technical Debt

| Risk / Debt | Impact | Mitigation | Issue |
|---|---|---|---|
| `claude-agent-sdk-java` not on Maven Central | `ClaudeAgentSdkDebateAgentProvider` is a permanent stub until resolved | `LangChain4jDebateAgentProvider @DefaultBean` runs in all environments; stub is `@Alternative`; no CI impact | platform#55 |
| `SummaryRenderer` not implementing `ChannelProjection` SPI | Projection remains local file-parser; deterministic fold via Qhorus SPI unavailable | qhorus#230 ✅ shipped; #31 ✅ closed — `DebateChannelProjection implements ChannelProjection<ReviewState>` shipped in C5 | #31 ✅ |
| Session storage path hardcoded to `~/.drafthouse/reviews/` | Can't configure for CI isolation or run multiple instances | ✅ Closed #28 — `casehub.drafthouse.storage.root` config key; default via `@WithDefault("${user.home}/.drafthouse/reviews")` | #28 ✅ |
| REST stubs (`POST /api/review/sessions`) in production code | Confusion for any client discovering the REST API | Removed at #24 close — `ReviewSessionResource` deleted, `DraftHouseMcpTools` ships all session control | #24 ✅ |

---

## §13 Glossary

| Term | Definition |
|---|---|
| **DebateEvent** | *(Superseded in C5)* Originally a sealed Java 17 interface. Now replaced by `EntryType` enum + `DebateProtocol.parseMeta()` for flexible debate entry parsing. `DebateStreamEntry` is the transport DTO for SSE delivery. |
| **ReviewState** | Immutable fold state produced by `SummaryProjector` — contains the current review, all debate rounds, and the last committed event ID |
| **ReviewPoint** | Selection anchor identifying the document region under review — file path, line range, and tokenized text |
| **ChannelProjection** | Qhorus SPI (qhorus#230) for incremental fold over a channel's committed events; called once per new commit rather than replaying from the beginning |
| **DebateAgentProvider** | SPI interface implemented by reviewer agent providers; controls which LLM executes debate rounds |
| **SummaryProjector** | DraftHouse implementation of `ChannelProjection<ReviewState>`; folds `DebateEvent` commits into an incrementally updated `ReviewState` |
| **JGit** | Pure-Java Git implementation used by `ReviewSessionService`; review sessions are stored as Git commits in `~/.drafthouse/reviews/` |
| **MCP** | Model Context Protocol — the tool invocation protocol by which LLM clients control DraftHouse review sessions |
| **DH** | DraftHouse artifact prefix — used in `DH-NNN` improvement log entries |
| **@DefaultBean** | Quarkus CDI annotation marking a bean as the default implementation, active when no `@Alternative` with higher priority is present on the classpath |
| **@Alternative @Priority(N)** | Quarkus CDI annotations that promote a bean above `@DefaultBean`; used to displace `LangChain4jDebateAgentProvider` with `ClaudeAgentSdkDebateAgentProvider` without any configuration change |
| **Debate Channel** | Qhorus APPEND channel for peer-to-peer structured debate between two LLM agents (REV and IMP). Agents post via `DebateMcpTools`; state is folded by `DebateChannelProjection`. Delivered in C5 / #27. |
| **ReviewChannelProjection** | `ChannelProjection<ReviewState>` that folds review Q&A channel messages using `message.type()` dispatch and `actorType`-based agent classification. Renamed from the original `DebateChannelProjection` in C5. |
| **DebateChannelProjection** | `RenderableProjection<ReviewState>` that folds debate channel messages using `META:` content header dispatch and explicit `META.agent` classification. Created in C5. |
| **ReviewStatus.DISPUTED** | Non-terminal review point status for active debate disputes; rendered as ⚡ without strikethrough (contrast with terminal `DECLINED` which renders as 🚫 with strikethrough). Added in C5. |

---

## Arc42Stories Self-Assessment

5. **No DESIGN.md baseline** — The §1–§8 content was adequate for system-level framing but required heavy reliance on `CLAUDE.md` and live codebase inspection to fill in class names, module paths, and Quarkus specifics that a `DESIGN.md` would have captured in one place. What was lost: per-class design rationale (why specific names, why specific method signatures), rejected alternatives (LangChain4j model selection, API path decisions), and the narrative thread between layers. These can be reconstructed from `docs/superpowers/specs/` and git log, but not at the speed of a single document read. Recommendation: for any application-tier harness with more than one layer, start a `DESIGN.md` stub early — even a one-paragraph description per section prevents this reconstruction cost at migration time.
