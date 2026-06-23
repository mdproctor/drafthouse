# Multi-LLM Reviewers with Personality Library — Design Spec

**Issue:** casehubio/drafthouse#62
**Date:** 2026-06-23
**Status:** Approved

## Problem

DraftHouse debate sessions use a single reviewer personality defined in `application.properties`. All debates get the same perspective regardless of what kind of review the user wants. A structural review, a content correctness debate, and a readability pass all use the same system prompt — reducing focus and quality.

## Solution

Use Eidos's existing agent identity model (`AgentDescriptor`, `AgentRegistry`, `SystemPromptRenderer`) to define and resolve distinct reviewer personalities. Each reviewer is an `AgentDescriptor` with a unique disposition (behavioural axes) and briefing, rendered into a system prompt by `SystemPromptRenderer`. DraftHouse ships mock implementations for standalone use; Eidos's real implementations displace them via CDI when on the classpath.

## Philosophy

Focused reviews produce better results than broad ones. Each debate session has one reviewer with one perspective. Multiple perspectives are achieved through sequential sessions (a review pipeline), not multiple reviewers in one channel. Pipeline orchestration is a separate concern (casehubio/drafthouse#72) — this design ensures the session-per-reviewer model supports it naturally.

## Architecture

### Consuming Eidos's agent identity model

DraftHouse depends on `casehub-eidos-api` (`io.casehub:casehub-eidos-api:0.2-SNAPSHOT`). It does NOT define new SPI types for personality. The existing Eidos API provides everything needed:

| Eidos type | DraftHouse usage |
|---|---|
| `AgentDescriptor` | Structured reviewer identity — name, slot, capabilities, disposition axes (socialOrient, ruleFollowing, riskAppetite, autonomy, conflictMode), briefing |
| `AgentDisposition` | Five behavioural axes — composable reviewer behaviour; each axis is vocabulary-grounded via `vocabUriForAxis()` |
| `AgentRegistry` | `findById(agentId, tenancyId)` to resolve by key; `find(AgentQuery.bySlot("document-reviewer", tenancyId))` to list available reviewers |
| `SystemPromptRenderer` | `render(descriptor, context) → RenderedPrompt` — produces the instruction document returned to the MCP caller |
| `AgentPromptContext` | Session-specific context: `GoalContext` (review focus + sub-goals), `Resource` (document paths), situational context, format (MARKDOWN) |

`VocabularyRegistry` is Eidos-internal — used by `EidosRenderPipeline` to resolve axis values to vocabulary-grounded labels. DraftHouse code never references it directly.

**Why not a new SPI?** Eidos already solved agent identity at a structural level. ARC42STORIES.MD §3 explicitly places "Agent identity management" in casehub-eidos scope — DraftHouse consumes the existing API, not duplicates it.

`casehub-eidos-api` is pure Java (no Quarkus compile-scope deps — only Mutiny as `provided` for reactive SPI variants). Adding it to `server/api/pom.xml` preserves the "pure-Java JAR" invariant stated in ARC42STORIES.MD §5.

### Who consumes the personality — the MCP caller, not the server

The main reviewer agents (REV/IMP) are the MCP callers themselves — Claude Code, Claudony, or any MCP client. They invoke `start_debate`, `raise_point`, `respond_to`. They are NOT server-side LLM invocations. `DebateChannelBackend.post()` only fires for `SUB_TASK_REQUEST` entries — all other message types are no-ops server-side.

The sub-agent handlers (`VerifyHandler`, `ArbitrateHandler`, etc.) run server-side via `ChannelAgentDispatcher` → `DebateAgentProvider.analyse()`. They have their own focused prompts (e.g. "You are a spec verifier...") and do NOT inherit the reviewer personality — they are deliberately fresh-context.

**This means:** the rendered personality instructions are returned to the MCP caller in tool responses. The server stores only the `agentId` on the session for provenance.

### Descriptor seeding — separate from registry implementation

The 4 reviewer `AgentDescriptor` instances must be registered regardless of which `AgentRegistry` implementation is active. This is solved by separating the seeding concern from the registry implementation:

**`ReviewerDescriptorSeeder`** — `@Startup @ApplicationScoped` bean in `server/runtime/`. Injects `AgentRegistry` (whatever implementation CDI resolves) and calls `register()` for each reviewer descriptor at startup.

This works for both deployment modes:
- **Standalone (mock registry):** `DraftHouseReviewerRegistry` is active. The seeder registers the 4 descriptors into the in-memory map.
- **With Eidos runtime:** `JpaAgentRegistry` is active. The seeder registers the 4 descriptors into the JPA store.

`AgentRegistry.register()` is idempotent in both implementations — `JpaAgentRegistry` does delete-then-insert; the in-memory mock does `ConcurrentHashMap.put()`. Re-registration on restart is safe.

The 4 reviewer descriptors:

| agentId | Name | Key disposition axes | Briefing focus |
|---|---|---|---|
| `drafthouse-structural-reviewer` | Structural Reviewer | conflictMode=collaborative, ruleFollowing=strict | Gaps, contradictions, missing sections, logical flow, internal consistency |
| `drafthouse-content-reviewer` | Content Reviewer | conflictMode=competing, riskAppetite=cautious | Factual correctness, technical accuracy, claim validity, evidence quality |
| `drafthouse-readability-reviewer` | Readability Reviewer | conflictMode=accommodating, autonomy=directed | Clarity, tone, audience fit, jargon, sentence structure, cross-referencing |
| `drafthouse-completeness-reviewer` | Completeness Reviewer | conflictMode=collaborative, ruleFollowing=strict | Coverage against stated goals, missing edge cases, unaddressed requirements |

Each descriptor includes:
- `slot: "document-reviewer"` — queryable via `AgentQuery.bySlot()`
- `capabilities: [{name: "document-review", tags: ["structural"|"content"|"readability"|"completeness"]}]`
- `briefing` — concise instruction text defining identity, focus areas, and approach. Constrained by `AgentDescriptorValidator.MAX_BRIEFING` (currently 500 characters in eidos-api). 500 characters is insufficient for substantive behavioural guidance — casehubio/eidos#64 includes increasing this limit to 2000 to accommodate reviewer-class agents that need multi-sentence identity descriptions.
- `tenancyId: "drafthouse"` — DraftHouse's default tenancy. Referenced via `ReviewerDescriptorSeeder.TENANCY_ID` constant — a single definition point for all registry queries and descriptor construction.

### Mock implementations (ship with DraftHouse runtime)

**`DraftHouseReviewerRegistry implements AgentRegistry`** — `@DefaultBean @ApplicationScoped`. A pure in-memory `ConcurrentHashMap`-backed registry. Implements `register()`, `findById()`, `find()`. Contains no seeding logic — seeding is handled by `ReviewerDescriptorSeeder`.

CDI displacement: `JpaAgentRegistry` (in `casehub-eidos-runtime`) is plain `@ApplicationScoped` with `@IfBuildProperty` gating — it automatically displaces a `@DefaultBean`. When Eidos runtime is on the classpath, the JPA registry handles persistence; the seeder still runs and registers into it.

**`SimplePromptRenderer implements SystemPromptRenderer`** — `@DefaultBean @ApplicationScoped`. Renders `AgentDescriptor` + `AgentPromptContext` into MARKDOWN format using template assembly. Reproduces the structural (non-enriched) output shape of `EidosRenderPipeline.assembleMarkdown()`:

```
# {name}
**Agent ID:** {agentId}

## Role
{slot}

## Capabilities
- **{capability.name}**: accepts {inputs} → {outputs}

## How You Operate
- Social orientation: {socialOrient}
- Rule following: {ruleFollowing}
- Risk appetite: {riskAppetite}
- Autonomy: {autonomy}
- Conflict mode: {conflictMode}
- Can delegate: {delegation}

## Operating Principles
{briefing}

## Data Handling
Jurisdiction: {jurisdiction}
Policy: {dataHandlingPolicy}

## Current Goal
{goal.description}
- {subGoal1}
- {subGoal2}

## Resources
- **{label}**: {uri} ({type})

## Context
{situationalContext}
```

Sections with null/empty fields are omitted entirely (e.g. Data Handling omitted when both jurisdiction and dataHandlingPolicy are null). No LLM enrichment, no vocabulary resolution (raw axis values — not vocabulary-resolved labels). Only non-null disposition axes are rendered.

Returns `new RenderedPrompt(content, MARKDOWN, null, null, false)` — null hashes (no fingerprinting), not enriched. Null hashes are valid per the `RenderedPrompt` record contract (used by `RenderCacheEntry.toRenderedPrompt()` in eidos-eval).

CDI displacement: `EidosSystemPromptRenderer` is currently `@DefaultBean` — same level as `SimplePromptRenderer`, which creates CDI ambiguity. The Eidos issue (casehubio/eidos#64) includes changing `EidosSystemPromptRenderer` to plain `@ApplicationScoped` so it displaces DraftHouse's mock. The displacement trigger is `casehub-eidos-runtime` on the classpath (not just `eidos-api`) — `eidos-runtime` provides `EidosSystemPromptRenderer` along with its transitive dependencies (`EidosRenderPipeline`, `VocabularyRegistry`, `ReactiveRenderedPromptCache`). DraftHouse's classpath already has LangChain4j (for `LangChain4jDebateAgentProvider`), so `ChatModel` resolves.

### DebateSession changes

`DebateSession` gains one field: `agentId` (nullable `String`). Set once at construction, never changed — provenance only.

Both constructors gain the `agentId` parameter:

```java
public DebateSession(UUID channelId, String debateSessionId,
                     String channelName, String agentId)

public DebateSession(UUID channelId, String debateSessionId,
                     String channelName, DocumentSet documentSet, String agentId)
```

All call sites update:
- `start_debate` → passes resolved `agentId` at construction
- `branchFrom()` → passes `source.agentId()` (same reviewer continues in branched session)
- `DebateSession.fromSnapshot()` → passes `snapshot.agentId()`
- `DebateSessionEntity` → new `@Column(name = "agent_id") String agentId` field; `toSnapshot()` passes it to `DebateSessionSnapshot` constructor; `fromSnapshot()` reads `snap.agentId()` into the entity field

`DebateSessionSnapshot` gains `agentId` as a new record component. This changes the constructor signature — all existing `DebateSessionStoreContractTest` invocations and `fromSnapshot()` call sites need updating (mechanical migration).

No `personalityInstructions` field. The rendered instructions are ephemeral — returned to the caller at tool response time. If audit needs the exact rendered text later, it can be re-rendered from the descriptor in the registry.

### Flyway migration

V102 adds one nullable column to `debate_session`:
```sql
ALTER TABLE debate_session ADD COLUMN agent_id VARCHAR(255);
```

### MCP tool changes

**`start_debate`** — gains one optional parameter:

```
@ToolArg(description = "Eidos agent ID for the reviewer (e.g. 'drafthouse-structural-reviewer'). "
    + "Omit for default reviewer. Use list_reviewers to see available agents.")
String agentId
```

Resolution flow (agentId is set on the session BEFORE `registry.put()`):
1. `agentId` provided → `agentRegistry.findById(agentId, TENANCY_ID)` → resolve `AgentDescriptor`
2. `agentId` null → resolve canonical default: `agentRegistry.findById(DEFAULT_REVIEWER_ID, TENANCY_ID)` where `DEFAULT_REVIEWER_ID = "drafthouse-structural-reviewer"`. This is deterministic — the structural reviewer is the default perspective. No reliance on collection ordering from `ConcurrentHashMap` or unordered JPA queries.
3. Default not found → return error: `"error: no reviewer agents registered"`
4. Build `AgentPromptContext`: goal = `GoalContext.of("Review document changes from a " + descriptor.name().toLowerCase() + " perspective")`, resources = `[Resource(specPath, "spec", "file")]`, format = `MARKDOWN`
5. `systemPromptRenderer.render(descriptor, context)` → `RenderedPrompt`
6. Construct `DebateSession` with `agentId` (set before `registry.put()` — no persist race)
7. Return rendered instructions to caller in the response

**Constants:** `ReviewerDescriptorSeeder` defines `TENANCY_ID = "drafthouse"` and `DEFAULT_REVIEWER_ID = "drafthouse-structural-reviewer"` — single definition points referenced by all registry queries and descriptor construction across `start_debate`, `list_reviewers`, `get_reviewer_instructions`, and the seeder itself.

**No fallback to `config.reviewer().personality()`.** The debate path uses one code path — the registry. The config property remains for `ReviewerChannelBackend` (review channel) until casehubio/drafthouse#73 migrates it.

**`start_debate` response:**

```json
{
  "debateSessionId": "...",
  "channel": "...",
  "specPath": "...",
  "reviewer": {
    "agentId": "drafthouse-structural-reviewer",
    "name": "Structural Reviewer",
    "instructions": "<rendered system prompt — the caller uses this as its persona>"
  }
}
```

The `reviewer` object is always present (even for default resolution). The caller uses `reviewer.instructions` as its system message for this debate session.

**`restart_from_round`** — re-resolves the source session's `agentId` from the registry, re-renders with the new session's context (spec path from documents), includes `reviewer` object in the response. The caller reconfigures with the same personality for the branched session.

**`get_debate_summary`** — **breaking format change:** currently returns raw markdown. Restructured to JSON to carry reviewer metadata alongside the summary:

```json
{
  "summary": "<rendered markdown from SummaryRenderer>",
  "reviewer": {
    "agentId": "drafthouse-structural-reviewer",
    "name": "Structural Reviewer"
  }
}
```

The `summary` field contains exactly the markdown that `getDebateSummary` returned before — including working set and active selection appendages. The `reviewer` field is present when the session has an `agentId`, absent otherwise (legacy sessions). This is a breaking change: every existing MCP caller that consumes the raw markdown must now parse JSON and extract `summary`. Breaking changes cost nothing externally — the migration is mechanical and the structured format is the right design.

Does NOT include rendered instructions in the reviewer object (too large for a summary response). The caller uses `get_reviewer_instructions` for the full render.

**New tool: `get_reviewer_instructions`**

```
@Tool(name = "get_reviewer_instructions",
      description = "Get the rendered system prompt for a reviewer agent. Use after reconnecting "
          + "to an existing debate session to re-obtain the reviewer persona. Pass debateSessionId "
          + "to render with session-specific context (spec path, goal). Without it, renders with "
          + "format only.")
```

Parameters:
- `agentId` (required) — the reviewer's Eidos agent ID
- `debateSessionId` (optional) — when provided, resolves the session from the registry and builds `AgentPromptContext` with the session's spec path and goal (same context `start_debate` would have built). Without it, renders with `AgentPromptContext.forFormat(MARKDOWN)` — no goal, no resources.

Session recovery flow:
1. Server restarts. Session restored from persistence (snapshot has `agentId`).
2. MCP caller discovers sessions via `/api/debate/sessions` or `get_debate_summary` — both include `agentId`.
3. Caller calls `get_reviewer_instructions(agentId, debateSessionId)` to re-obtain the rendered instructions with session-specific context.
4. Caller adopts the instructions as its system message and resumes the debate.

**New tool: `list_reviewers`**

```
@Tool(name = "list_reviewers",
      description = "List available reviewer agents. Each agent has a distinct review perspective "
          + "defined by its disposition and briefing. Use the agentId with start_debate.")
```

Returns structured data via `agentRegistry.find(AgentQuery.bySlot("document-reviewer", tenancyId))`:

```json
[
  {
    "agentId": "drafthouse-structural-reviewer",
    "name": "Structural Reviewer",
    "slot": "document-reviewer",
    "disposition": {
      "conflictMode": "collaborative",
      "ruleFollowing": "strict"
    },
    "capabilities": [{"name": "document-review", "tags": ["structural"]}],
    "briefingSummary": "Focuses on gaps, contradictions, missing..."
  }
]
```

`disposition` includes only non-null axes — null axes are omitted from the JSON response. `briefingSummary` is the briefing truncated to 200 characters with `"..."` appended if truncated.

**Session list (`/api/debate/sessions`)** — `SessionInfo` gains `agentId`:

```java
new SessionInfo(s.debateSessionId(), s.channelName(), s.primaryPath(), s.agentId())
```

**`export_debate_summary`** — the exported markdown file prepends a reviewer provenance line when the session has an `agentId`: `**Reviewer:** Structural Reviewer (drafthouse-structural-reviewer)`. This preserves which reviewer conducted the debate when the file is read months later.

**Prompt injection safety (PP-20260604-b88833):** `agentId` is a lookup key into a server-side registry, not a raw prompt string. The MCP caller selects from the available set — the protocol is satisfied.

### SPI separation

`AgentRegistry` + `SystemPromptRenderer` (personality resolution) and `DebateAgentProvider` (LLM invocation for sub-agents) remain separate concerns:
- Personality resolution resolves what the reviewer should be — an instruction document returned to the MCP caller.
- `DebateAgentProvider` executes server-side sub-agent analysis — a different concern with different prompts.
- They do not interact. The personality never flows through `AgentTask.systemPrompt()` — that path is for sub-agent handlers only.

### Review channel unification (follow-on)

Review channels (`ReviewerChannelBackend` → `DocumentReviewer` → `config.reviewer().personality()`) and debate channels solve the same problem — an LLM reviews a document — with different interaction models (single-turn Q&A vs multi-turn debate). Both need reviewer personalities resolved from the same source.

This issue delivers the debate channel path. casehubio/drafthouse#73 migrates `ReviewerChannelBackend` to resolve personality via `AgentRegistry` + `SystemPromptRenderer`. After that migration, `config.reviewer().personality()` is dead code and can be removed. Until then, the config property remains for the review channel only — the debate path does NOT fall back to it.

## Pipeline evolution support

This design does not build pipeline orchestration but ensures it's not blocked:

- **One reviewer per session** — a pipeline is N sequential `start_debate` calls with different agentIds.
- **Document versioning via working set** — a stage's output becomes the next stage's input via `add_document` / `set_comparison`.
- **Session branching** — `branchFrom` carries agentId and documents forward.
- **`list_reviewers` returns structured data** — disposition axes, capabilities, and tags enable a future orchestrator to select reviewers programmatically.
- **`AgentPromptContext` carries session-specific goal** — each pipeline stage can set a different `GoalContext` without changing the descriptor.

**Future pipeline issue (casehubio/drafthouse#72) will add:** pipeline definition model, orchestration endpoint, version tracking across stages. None require changes to what this issue delivers.

## Eidos issue scope

casehubio/eidos#64:

**Delivers:**
- Increase `AgentDescriptorValidator.MAX_BRIEFING` from 500 to 2000 — the current limit is too restrictive for agents that need substantive behavioural guidance
- Register DraftHouse reviewer agent descriptors (structural, content, readability, completeness) with appropriate dispositions in the Eidos agent graph — matching the 4 descriptors seeded by `ReviewerDescriptorSeeder`
- Validate `EidosSystemPromptRenderer` MARKDOWN output for `slot="document-reviewer"` descriptors — must read as a complete persona document
- Change `EidosSystemPromptRenderer` from `@DefaultBean` to plain `@ApplicationScoped` — enables CDI displacement of DraftHouse's `SimplePromptRenderer` mock (same pattern as `JpaAgentRegistry`)

**Not in scope:** new SPI types, intent-to-personality mapping, pipeline orchestration.

## Dependencies

- `casehub-eidos-api` added as dependency to `server/api/pom.xml` (`io.casehub:casehub-eidos-api:0.2-SNAPSHOT`)
- Flyway V102 migration for `debate_session` table (`agent_id VARCHAR(255)` — one nullable column)
- No new LangChain4j or Quarkus dependencies
- CDI displacement of `SimplePromptRenderer` requires `casehub-eidos-runtime` on the classpath (not just `eidos-api`)

## Testing

- `ReviewerDescriptorSeeder` unit test — verify 4 descriptors registered into a mock `AgentRegistry` at startup
- `DraftHouseReviewerRegistry` unit test — `register()` stores, `findById()` resolves, `find(AgentQuery.bySlot(...))` filters by slot, unknown agentId returns empty
- `SimplePromptRenderer` unit test — renders each descriptor to non-empty MARKDOWN; output includes `# Name`, `## Role`, `## Capabilities`, `## How You Operate`, `## Operating Principles` sections; omits `## Data Handling` when both fields null; includes `## Current Goal` and `## Resources` when context provided; skips them when context is format-only; only non-null disposition axes rendered
- `DebateSession` unit test — agentId set at construction via new constructor, carried through `branchFrom`, included in `snapshot()`, restored by `fromSnapshot()`
- `DebateSessionSnapshot` — **update all existing contract test invocations** and `fromSnapshot()` call sites for the new `agentId` record component (constructor signature change)
- `DebateMcpTools` integration test — `start_debate` with agentId (response includes reviewer with instructions), `start_debate` without agentId (default `drafthouse-structural-reviewer` from registry, response still includes reviewer), `start_debate` with unknown agentId (error), `list_reviewers` returns 4 entries with structured data and non-null axes only, `get_reviewer_instructions` with debateSessionId (full context render), `get_reviewer_instructions` without debateSessionId (format-only render), `get_debate_summary` returns JSON with `summary` and `reviewer` fields (**breaking format change from raw markdown — update existing test assertions**)
- `SessionInfo` — verify `agentId` included in `/api/debate/sessions` response
- Flyway V102 — session persistence round-trip with `agent_id`
- E2E test — start a debate with an agentId, verify the reviewer name and instructions appear in the response

## Protocols

| Protocol | Relevance |
|----------|-----------|
| PP-20260604-b88833 (prompt injection) | agentId is a lookup key, not raw prompt — satisfied |
| PP-20260607-508f7b (actor type classification) | No change — AgentType enum unchanged |
| PP-20260610-a47ef5 (apply must not throw) | No change — projection unchanged |
| PP-20260608-d94c7d (sentinel encoding) | No change — message encoding unchanged |
| PP-20260608-21c69f (session cleanup) | No change — instance lifecycle unchanged |

## Follow-on issues

| Issue | Description | Dependency |
|---|---|---|
| casehubio/eidos#64 | Register reviewer descriptors, validate renderer output, CDI fix | In parallel — DraftHouse mock works standalone |
| casehubio/drafthouse#72 | Pipeline orchestration — sequential multi-perspective review sessions | After #62 |
| casehubio/drafthouse#73 | Review channel personality migration — unify with AgentRegistry | After #62 |
