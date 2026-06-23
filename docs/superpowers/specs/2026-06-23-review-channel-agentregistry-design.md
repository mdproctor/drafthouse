# Review Channel AgentRegistry Migration — Design Spec

**Issue:** casehubio/drafthouse#73
**Date:** 2026-06-23
**Scale:** S | **Complexity:** Low–Med (wider than personality swap due to tool reorganisation)

## Problem

Review channels (`ReviewerChannelBackend` → `DocumentReviewer` → `config.reviewer().personality()`) and debate channels solve the same problem — an LLM reviews a document — with different interaction models. After #62, debate channels resolve personality via `AgentRegistry` + `SystemPromptRenderer` (Eidos). Review channels still use a flat config string from `application.properties`. Two resolution mechanisms for the same domain concern is architectural debt.

## Design

### 1. ResolvedReviewer (api module)

New record in `server/api` — the resolved identity triple for a reviewer agent at a point in time.

```java
package io.casehub.drafthouse;

public record ResolvedReviewer(String agentId, String name, String instructions) {}
```

- `agentId` — registry reference for metadata/display lookups
- `name` — human-readable agent name, captured at resolution time (no re-resolution needed)
- `instructions` — rendered system prompt from `SystemPromptRenderer`, used as the LLM system message

Pure value type. No dependencies. Lives in `api` because `ReviewSession` (also in `api`) references it.

### 2. ReviewerResolver (runtime module)

New `@ApplicationScoped` service — single entry point for all reviewer agent operations.

```java
package io.casehub.drafthouse;

@ApplicationScoped
public class ReviewerResolver {

    @Inject AgentRegistry agentRegistry;
    @Inject SystemPromptRenderer systemPromptRenderer;

    public ResolvedReviewer resolve(String agentId, Resource... resources) {
        AgentDescriptor descriptor;
        String resolvedId;
        if (agentId != null && !agentId.isBlank()) {
            descriptor = agentRegistry.findById(agentId, ReviewerDescriptorSeeder.TENANCY_ID)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown reviewer agent: " + agentId));
            resolvedId = agentId;
        } else {
            descriptor = agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                    ReviewerDescriptorSeeder.TENANCY_ID)
                    .orElseThrow(() -> new IllegalStateException(
                            "no reviewer agents registered"));
            resolvedId = descriptor.agentId();
        }
        var context = AgentPromptContext.forFormat(SystemPromptRenderer.RenderFormat.MARKDOWN)
                .withGoal(GoalContext.of("Review document changes from a "
                        + descriptor.name().toLowerCase() + " perspective"))
                .withResources(List.of(resources));
        String instructions = systemPromptRenderer.render(descriptor, context).content();
        return new ResolvedReviewer(resolvedId, descriptor.name(), instructions);
    }

    public List<AgentDescriptor> listAvailable() {
        return agentRegistry.find(
                AgentQuery.bySlot(ReviewerDescriptorSeeder.SLOT,
                        ReviewerDescriptorSeeder.TENANCY_ID));
    }

    public Optional<AgentDescriptor> findDescriptor(String agentId) {
        return agentRegistry.findById(agentId, ReviewerDescriptorSeeder.TENANCY_ID);
    }
}
```

Design choices:
- **Exceptions on failure**, not Optional. Two distinct error messages ("unknown reviewer agent: X" vs "no reviewer agents registered") — exceptions carry the message, callers catch and format as `"error: " + e.getMessage()`.
- **`Resource... resources` varargs** — review calls `resolve(agentId)` (no resources), debate calls `resolve(agentId, new Resource(specPath, "spec", "file"))`. Both clean.
- **Goal hardcoded** — every DraftHouse channel uses "Review document changes from a [name] perspective". Parameterise only if a future channel needs a different pattern.
- **`listAvailable()` filters by slot** — uses `AgentQuery.bySlot("document-reviewer", ...)`, not `all()`. The tenancy might contain non-reviewer agents in the future.
- **`findDescriptor()` centralises tenancy-ID** — lightweight descriptor lookup without rendering. Used by `getDebateSummary` and `exportDebateSummary` for name-only lookups. Eliminates the need for any consumer to reference `ReviewerDescriptorSeeder.TENANCY_ID` directly or inject `AgentRegistry` alongside the resolver.
- **No `@Nullable` on `resolve()`** — the codebase uses explicit null checks (`if (agentId != null && !agentId.isBlank())`), never `@Nullable` annotations. The method body handles null agentId via the established pattern.

### 3. ReviewerDescriptorSeeder — new constant

```java
public static final String SLOT = "document-reviewer";
```

Eliminates the magic string repeated across `list_reviewers` and the seeder's agent descriptor builders.

### 4. ReviewSession (api module)

`personality` replaced by `ResolvedReviewer reviewer`.

```java
public record ReviewSession(
        UUID channelId, String sessionId, String channelName, String instanceId,
        String docAContent, String docBContent, SelectionScope selection,
        ResolvedReviewer reviewer
) {
    public ReviewSession withSelection(final SelectionScope selection) {
        return new ReviewSession(channelId, sessionId, channelName, instanceId,
                docAContent, docBContent, selection, reviewer);
    }
}
```

Why both `agentId` and `instructions` (via `ResolvedReviewer`):
- `agentId` alone would require the backend or factory to re-resolve — wasteful redundancy.
- `instructions` alone loses the agent reference — no way to look up metadata later.
- `name` stored at resolution time — avoids re-resolution from registry (which could fail if agent is removed mid-session).

The session captures the resolved identity at creation time. The backend reads `session.reviewer().instructions()` on every `post()` call. Consistency within a session — same instructions for every query.

### 5. DocumentReviewer (runtime module)

Rename `personality` → `instructions`. Move response protocol from `@UserMessage` to `@SystemMessage`.

```java
@RegisterAiService
public interface DocumentReviewer {

    @SystemMessage("""
            {{instructions}}

            ## Response Protocol
            outcome=DECLINE if the query is outside document review scope — explain why.
            outcome=AGREE if you agree and consider this point resolved.
            outcome=QUALIFY if you have more to say — discussion continues.
            Always provide your analysis in the content field.
            """)
    @UserMessage("""
            Document A (original):
            {{documentA}}

            Document B (revised):
            {{documentB}}

            {{selectionContext}}

            Review history (prior turns in this session):
            {{reviewHistory}}

            Current query: {{query}}
            """)
    ReviewResult review(String instructions, String documentA, String documentB,
                        String selectionContext, String reviewHistory, String query);
}
```

Why move the response protocol:
- System message = behavioural instructions (who you are + how you respond). The AGREE/QUALIFY/DECLINE semantics are constant across all calls — they define agent behaviour, not task content.
- User message = task content (what you're reviewing + what you're asked). After the move, `@UserMessage` is purely documents, selection, history, query.
- The protocol is tighter — 4 concise lines vs the current verbose paragraph.

### 6. ReviewerChannelBackend (runtime module)

One-line change:

```java
// Before
result = llm.review(session.personality(), session.docAContent(), ...);
// After
result = llm.review(session.reviewer().instructions(), session.docAContent(), ...);
```

No constructor changes. No new dependencies. The factory (`ReviewerChannelBackendFactory`) is unchanged — it never touched personality.

### 7. DraftHouseMcpTools (runtime module)

**New injection:** `@Inject ReviewerResolver resolver`

**`start_review` — gains `agentId` parameter:**

```java
@Tool(name = "start_review",
      description = "Start a document review session. Returns JSON with sessionId "
          + "(use for all subsequent calls), channel, and reviewer (agentId, name, "
          + "instructions). Use list_reviewers to see available agents.")
public String startReview(
        @ToolArg(description = "Absolute path to document A (the 'before' version)")
        String docAPath,
        @ToolArg(description = "Absolute path to document B (the 'after' version)")
        String docBPath,
        @ToolArg(description = "Eidos agent ID for the reviewer. Omit for default "
                + "reviewer. Use list_reviewers to see available agents.")
        String agentId)
```

Fail-fast ordering: resolve reviewer → read files → validate sizes → create channel. Resolver call outside the channel-cleanup try/catch (no cleanup needed if the agent doesn't exist).

Session creation: `new ReviewSession(..., reviewer)` with `ResolvedReviewer`.

JSON response aligned with `start_debate`:
```json
{
  "sessionId": "uuid",
  "channel": "drafthouse/r-uuid",
  "reviewer": {
    "agentId": "drafthouse-structural-reviewer",
    "name": "Structural Reviewer",
    "instructions": "..."
  }
}
```

Instance registration uses the agent name as the human-readable description:

```java
instanceId = "drafthouse-reviewer-" + sessionId;  // machine identifier — unchanged
instanceService.register(instanceId, reviewer.name() + " " + sessionId,  // human label
        List.of("document-review"));
```

**Tools moved from `DebateMcpTools`:**

`list_reviewers` — moved as-is. Tool description updated: "Use the agentId with start_review or start_debate." Delegates to `resolver.listAvailable()`. JSON formatting stays in the tool (presentation concern).

`get_reviewer_instructions` — generalised. Drops `debateSessionId` parameter, takes optional `resourcePath` directly:

```java
@Tool(name = "get_reviewer_instructions",
      description = "Render full reviewer instructions for an agent, optionally "
          + "in the context of a resource.")
public String getReviewerInstructions(
        @ToolArg(description = "Eidos agent ID") String agentId,
        @ToolArg(description = "Optional resource path for contextual rendering")
        String resourcePath)
```

Delegates to `resolver.resolve(agentId, resources)`. Breaking API change (parameter semantics change from session ID to raw path).

### 8. DebateMcpTools (runtime module)

**Injection changes:**
- Add: `@Inject ReviewerResolver resolver`
- Remove: `@Inject SystemPromptRenderer systemPromptRenderer`
- Remove: `@Inject AgentRegistry agentRegistry` — replaced by `resolver.findDescriptor()` for lightweight name lookups

**Methods removed:** `list_reviewers()`, `get_reviewer_instructions()`, `renderInstructions()` — all moved to `DraftHouseMcpTools` / `ReviewerResolver`.

**`startDebate` — inline resolution replaced:**

```java
// Before: 10+ lines of manual resolution + null checks
// After:
ResolvedReviewer reviewer;
try {
    reviewer = resolver.resolve(agentId, new Resource(specPath, "spec", "file"));
} catch (IllegalArgumentException | IllegalStateException e) {
    return "error: " + e.getMessage();
}
```

Session creation uses `reviewer.agentId()`. JSON response uses `reviewer.agentId()`, `reviewer.name()`, `reviewer.instructions()`.

**`restart_from_round` — full replacement:**
```java
// Before: agentRegistry.findById() + renderInstructions() + manual JSON from desc.get()
// After:
ResolvedReviewer reviewer = resolver.resolve(
        original.agentId(), new Resource(original.primaryPath(), "spec", "file"));
reviewerJson = ",\"reviewer\":{\"agentId\":" + jsonString(reviewer.agentId())
        + ",\"name\":" + jsonString(reviewer.name())
        + ",\"instructions\":" + jsonString(reviewer.instructions()) + "}";
```

**`getDebateSummary` and `exportDebateSummary` — lightweight name lookup:**
```java
// Before: agentRegistry.findById(session.agentId(), ReviewerDescriptorSeeder.TENANCY_ID)
// After:
resolver.findDescriptor(session.agentId()).map(AgentDescriptor::name)
```

No direct `AgentRegistry` access remains on `DebateMcpTools`. `ReviewerResolver` is the single entry point for all reviewer agent operations — the claim in §2 is now delivered.

### 9. Config cleanup

**Remove:** `DraftHouseConfig.Reviewer.personality()` method and `casehub.drafthouse.reviewer.personality` from `application.properties`.

**Retain:** `DraftHouseConfig.Reviewer.maxDocChars()` — operational limit, not agent identity.

**Update protocol:** `drafthouse-config-mock-two-level` — change example from `personality()` to `maxDocChars()`, remove `personality` from violation hint. Core rule unchanged.

### 10. Tool surface after migration

| Class | Tools | Scope |
|-------|-------|-------|
| `DraftHouseMcpTools` | `start_review`, `query_review`, `update_selection`, `end_review`, `list_reviewers`, `get_reviewer_instructions` | General-purpose + cross-channel agent infrastructure |
| `DebateMcpTools` | `start_debate`, `raise_point`, `respond_to`, `flag_human`, `get_debate_summary`, `end_debate`, `report_context`, `export_debate_summary`, document management | Debate-specific session and conversation tools |

### 11. Test strategy

**New: `ReviewerResolverTest`**
- `resolve_withExplicitAgentId` — correct triple returned
- `resolve_withNullAgentId` — default reviewer fallback
- `resolve_withUnknownAgentId` — throws `IllegalArgumentException`
- `resolve_withNoAgentsRegistered` — throws `IllegalStateException`
- `resolve_passesResourcesToRenderer` — verifies resources in `AgentPromptContext`
- `resolve_goalIncludesAgentName` — verifies goal text construction
- `listAvailable_returnsAllReviewerAgents`
- `findDescriptor_knownAgent` — returns descriptor
- `findDescriptor_unknownAgent` — returns empty Optional

**New: agent tool tests on `DraftHouseMcpToolsTest`**
- `listReviewers_returnsFormattedJson` (migrated from `DebateMcpToolsTest`)
- `getReviewerInstructions_withResourcePath` / `_withoutResourcePath` / `_unknownAgent`

**Updated: `DraftHouseMcpToolsTest`**
- setUp: mock `ReviewerResolver` instead of two-level config mocking
- `startReview_happyPath` — asserts `session.reviewer()` instead of `session.personality()`
- New: `startReview_withAgentId` / `_withoutAgentId` / `_withUnknownAgentId`
- New: `startReview_response_includesReviewerBlock`

**Updated: `DebateMcpToolsTest`**
- Mock `ReviewerResolver` for `startDebate_*` tests (replaces `agentRegistry` + `systemPromptRenderer` mocks)
- Remove `list_reviewers` and `get_reviewer_instructions` tests (moved)
- `getDebateSummary` and `exportDebateSummary` tests — mock `resolver.findDescriptor()` instead of `agentRegistry.findById()` directly

**Updated: `ReviewerChannelBackend` tests**
- `session.personality()` → `session.reviewer().instructions()`

**E2E tests:** no changes — `agentId` is optional, existing calls use the default.

## Implementation note

Both `DraftHouseMcpTools` and `DebateMcpTools` need a `jsonString` helper for JSON escaping. Both classes are in the same package. Define `static String jsonString(String s)` with package-private visibility on one class (e.g. `DraftHouseMcpTools`) and call it from the other — avoids duplication without a separate utility class.

## Out of scope

- `DebateSession` adopting `ResolvedReviewer` — not a type incompatibility (identity fields on `DebateSession` are immutable `final` like `ReviewSession`), but a deliberate semantic choice: debate sessions are long-lived and `restart_from_round` intentionally re-resolves via `resolver.resolve()` to pick up descriptor changes since the original session started; storing a `ResolvedReviewer` snapshot would either go stale or require invalidation logic for no gain
- Multi-turn document caching via LangChain4j `ChatMemory` (separate optimisation)
- Unifying `sessionId` / `debateSessionId` field naming
- Per-agent `maxDocChars` limits (future feature)
