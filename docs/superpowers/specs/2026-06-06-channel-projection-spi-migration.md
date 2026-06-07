# Channel Projection SPI Migration — #31

**Branch:** `issue-31-channel-projection-spi`  
**Covers:** #31 (ChannelProjection SPI), #35 (UUID slug fix), #37 (CritiqueResourceTest API key)

---

## Context

DraftHouse migrated from file-backed sessions to Qhorus channels but left the old file-based
pipeline in place as dead code. The old `ReviewSessionService` used `DebateParser` to parse
`debate.md` into `DebateEvent` objects, then folded them through `SummaryProjector`. None of
this is reachable from the live path.

Two deficiencies in the current live system:
1. **No conversation memory.** `ReviewerChannelBackend.post()` passes only `(personality, docA,
   docB, selectionContext, question)` to the LLM — no history of prior turns. The old system
   passed the accumulated `debateContent` string on every call. The migration regressed this.
2. **`DECLINE → DISPUTE` mismatch.** When the AI sends `MessageType.DECLINE` (query out of
   scope), the existing projection maps it to `EntryType.DISPUTE / ReviewStatus.ACTIVE` — an
   ongoing debate — rather than a closed refusal. Semantically wrong.

This migration:
1. Deletes all dead file-based pipeline code — no shims, no bridges.
2. Adds `EntryType.DECLINED` and `ReviewStatus.DECLINED` to the domain model.
3. Adds `ReviewConversationRenderer` — a lightweight plain-text renderer for LLM context.
4. Creates `DebateChannelProjection` — single `@ApplicationScoped RenderableProjection<ReviewState>`.
5. Wires `ProjectionService` into `ReviewerChannelBackend`, restoring conversation memory.
6. Deletes the `claude-agent` module — its only class implements `DebateAgentProvider`, which
   is being deleted.

---

## What Gets Deleted

### Module: `server/claude-agent/`
Delete the entire module. It contains one stub class
(`ClaudeAgentSdkDebateAgentProvider implements DebateAgentProvider`) that throws
`UnsupportedOperationException`. With `DebateAgentProvider` deleted, the module has no content.
The future Claude Agent SDK integration (#29) will be redesigned when `platform#55` lands.

**Parent pom change:** remove `<module>claude-agent</module>` from `server/pom.xml`.

### `api/debate/`
| File | Reason |
|------|--------|
| `SummaryProjector` | Fold logic moves to `DebateChannelProjection`; bridge methods gone |
| `DebateParser` | File-based markdown parser |
| `RoundParser` | LLM output parser for old round model |
| `DebateEvent` (sealed: `RaiseEvent`, `ResponseEvent`, `FlagHumanEvent`, `AgentMemo`) | Only needed to feed `DebateParser` and `SummaryProjector` bridge |
| `DebateEntry` | Old agent round output type |
| `DebateEntryFormatter` | Formats `DebateEntry` to markdown |
| `DebateRoundContext` | Context record for old agent round calls |
| `DebateAgentProvider` | SPI for autonomous REV/IMP round execution |

### `runtime/debate/`
| File | Reason |
|------|--------|
| `ReviewSession` (6-field record) | Superseded by the 9-field `ReviewSession` in `api/` |
| `ReviewSessionService` | File-based session orchestrator |
| `LangChain4jDebateAgentProvider` | `@DefaultBean` impl of deleted SPI |
| `SpecReviewerAiService` | LangChain4j AI service for old REV agent |
| `SpecImplementerAiService` | LangChain4j AI service for old IMP agent |

### Tests deleted
| Module | Test file |
|--------|-----------|
| `api/test` | `SummaryProjectorTest`, `DebateParserTest`, `RoundParserTest`, `DebateEntryFormatterTest` |
| `runtime/test` | `DebateAgentProviderContractTest`, `LangChain4jDebateAgentProviderTest`, `DebateRoundTripTest` |
| `claude-agent/test` | `ClaudeAgentSdkDebateAgentProviderTest` (module deleted) |

---

## Domain Model Changes

### New: `EntryType.DECLINED`

```java
public enum EntryType { RAISE, AGREE, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED }
```

### New: `ReviewStatus.DECLINED`

```java
public enum ReviewStatus { OPEN, ACTIVE, AGREED, PENDING_HUMAN, DECLINED }
```

`MessageType.DECLINE` from an AI reviewer means "this query is out of scope" — a closed
refusal, not an ongoing dispute. Map it to `EntryType.DECLINED` / `ReviewStatus.DECLINED`.
`EntryType.DISPUTE` / `ReviewStatus.ACTIVE` continues to mean "IMP disputes REV's point" in
the future #27 DebateChannel.

`SummaryRenderer` must handle `ReviewStatus.DECLINED` — add a case (`🚫` marker, strikethrough
header). `EntryType.DECLINED` renders with label `"declined"` in the thread entry.

### `SummaryRenderer` changes — exhaustive switch completeness

Both switch expressions in `SummaryRenderer.render()` are exhaustive with no `default` clause.
Adding `DECLINED` to both enums causes compile failures unless both switches are extended.

**`statusMarker` switch — add DECLINED:**
```java
String statusMarker = switch (point.currentStatus()) {
    case OPEN          -> "🔴";
    case ACTIVE        -> "🟡";
    case AGREED        -> "✅";
    case PENDING_HUMAN -> "🔵";
    case DECLINED      -> "🚫";   // new
};
```

**`typeLabel` switch — add DECLINED:**
```java
String typeLabel = switch (entry.type()) {
    case RAISE      -> "raise";
    case AGREE      -> "agree";
    case DISPUTE    -> "dispute";
    case QUALIFY    -> "qualify";
    case FLAG_HUMAN -> "flag";
    case DECLINED   -> "declined";  // new
};
```

**Header strikethrough — extend to DECLINED:**
```java
// Before:
if (point.currentStatus() == ReviewStatus.AGREED) { ... strikethrough ... }

// After:
boolean strikethrough = point.currentStatus() == ReviewStatus.AGREED
        || point.currentStatus() == ReviewStatus.DECLINED;
if (strikethrough) {
    sb.append("## ").append(statusMarker).append(" ~~").append(header).append("~~\n");
} else {
    sb.append("## ").append(statusMarker).append(" ").append(header).append("\n");
}
```

---

## New: `ReviewConversationRenderer`

**Location:** `server/api/src/main/java/io/casehub/drafthouse/debate/ReviewConversationRenderer.java`

A lightweight, LLM-context renderer. Distinct from `SummaryRenderer` (human-facing, emoji,
timestamps) — these are different consumers with different requirements.

**Rules:**
- Renders only `AGREED` and `DECLINED` points. Excludes `OPEN` (in-flight question, no
  response yet), `ACTIVE`, and `PENDING_HUMAN`. In v1, `ACTIVE` and `PENDING_HUMAN` are
  unreachable from the live path — they require `QUALIFY`/`DISPUTE`/`HANDOFF` messages that
  `DocumentReviewer` never produces. If they occur (e.g. from a manually crafted message),
  they are silently excluded (treated as OPEN). Rendering for ACTIVE/PENDING_HUMAN is #27
  scope.
- Plain text, no emoji, no timestamps, no P-priority/scope metadata.
- Omits metadata (artefactRefs default to P3/ISOLATED in v1 anyway — noise for the LLM).
- Returns `"No prior review activity in this session."` if no renderable exchanges exist.
  This sentinel is intentionally different from `DebateChannelProjection.render()`'s
  `"No review activity yet."` — they serve different consumers (LLM vs human) and there is
  no requirement for consistency.

**Format:**

```
Q: What does this change do?
A: This changes the authentication flow to use JWT tokens with a 15-minute expiry.

Q: Is there a security concern?
A: (Declined — out of scope for document review.)
```

`Q:` = content of the raise entry. `A:` = content of the last response entry.
DECLINED points render `(Declined — <reason>)`. Do **not** append a trailing period —
`ReviewResult.decline(...)` content typically ends with `.` (e.g. `"Out of scope."`), and
appending another produces `(Declined — Out of scope..)`. Strip any trailing period from the
content before interpolation, or omit the trailing period from the format entirely:
```java
String reason = content.endsWith(".") ? content.substring(0, content.length() - 1) : content;
"(Declined — " + reason + ")"
```

This fixes two issues simultaneously:
- The current QUERY (OPEN) is naturally excluded from the rendered history — it does not
  appear twice in the LLM prompt.
- The format is readable by the LLM without explanation of REV/IMP/P3/emoji.

`ReviewConversationRenderer` is a stateless utility — instantiate directly as a field, no CDI.

---

## New: `DebateChannelProjection`

**Location:** `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`

```java
@ApplicationScoped
public class DebateChannelProjection implements RenderableProjection<ReviewState> {

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Override public String projectionName() { return "debate-summary"; }

    @Override
    public ReviewState identity() {
        return new ReviewState(Map.of(), List.of());
    }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        return switch (message.type()) {
            case QUERY             -> handleRaise(state, message);
            case RESPONSE          -> handleResponse(state, message);
            case DECLINE           -> handleDecline(state, message);
            case HANDOFF           -> handleFlagHuman(state, message);
            default                -> state;
        };
    }

    @Override
    public String render(ProjectionResult<ReviewState> result) {
        return result.isEmpty() ? "No review activity yet." : renderer.render(result.state());
    }
}
```

### `handleDecline()` — new handler (no ancestor to copy from)

```java
private ReviewState handleDecline(ReviewState state, MessageView message) {
    String targetId = message.correlationId();
    if (targetId == null) {
        return state;  // silent — null correlationId is expected occasionally
    }
    if (!state.points().containsKey(targetId)) {
        System.Logger logger = System.getLogger(DebateChannelProjection.class.getName());
        logger.log(System.Logger.Level.WARNING,
                "Decline references unknown point ID: {0} — discarded", targetId);
        return state;
    }
    ReviewPoint existing = state.points().get(targetId);
    var thread = new ArrayList<>(existing.thread());
    // round=0: MessageView carries no round field in v1 — see v1 limitations below
    thread.add(new ThreadEntry(null, agentType(message), 0, EntryType.DECLINED,
            Objects.requireNonNullElse(message.content(), "")));
    var updated = new ReviewPoint(
            existing.id(), existing.classification(), thread, ReviewStatus.DECLINED);
    var points = new LinkedHashMap<>(state.points());
    points.put(targetId, updated);
    return new ReviewState(points, new ArrayList<>(state.humanFlags()));
}
```

Mirrors `handleResponse()` structure. Uses `EntryType.DECLINED` / `ReviewStatus.DECLINED`.
Null guard is split in two: null correlationId is silently discarded; non-null correlationId
referencing an unknown point logs a WARNING (matching `handleResponse()` behaviour exactly).
`Objects.requireNonNullElse(message.content(), "")` guards against null content in the
`ThreadEntry`.

### agentType — actorType only, no fallback

```java
private AgentType agentType(MessageView message) {
    if (message.actorType() == null) {
        throw new IllegalArgumentException(
                "MessageView.actorType() must not be null in DebateChannelProjection");
    }
    return switch (message.actorType()) {
        case HUMAN  -> AgentType.REV;
        case AGENT  -> AgentType.IMP;
        default     -> throw new IllegalArgumentException(
                "Unsupported actorType in debate projection: " + message.actorType());
    };
}
```

No sender string matching. No null fallback. Tests must pass real `ActorType` values.

### v1 limitations — explicit acknowledgments

**actorType→AgentType mapping is a v1 heuristic.**
The HUMAN→REV, AGENT→IMP mapping works only because v1 has one human and one AI actor.
For #27 DebateChannel, both REV and IMP will be `ActorType.AGENT`. This mapping will break
and must be replaced — likely via sender conventions or an explicit role field in artefactRefs.
This is not forward-looking infrastructure; it is a deliberate v1 approximation that #27 will
redesign.

**QUALIFY branch is inert in v1.**
`DocumentReviewer` returns `ReviewResult(declined, content)` and never produces
`[QUALIFY]`-prefixed content. The QUALIFY branch in `handleResponse()` will never fire in the
live path. It is retained as #27 infrastructure.

**artefactRefs are null in v1.**
All human QUERYs have null artefactRefs → every review point defaults to P3/ISOLATED/null
location. `ReviewConversationRenderer` omits this metadata (by design). `SummaryRenderer`
shows it for human/MCP display — acceptable v1 noise.

**`ThreadEntry.round` is hardcoded to `0` throughout.**
`MessageView` carries no round field in the v1 path — round tracking is a #27 DebateChannel
concern. Every `ThreadEntry` constructor call in `DebateChannelProjection` passes `0` as the
round argument. This is intentional and must be preserved exactly; the `0` is not a default
but a deliberate v1 placeholder.

### CDI placement

`DebateChannelProjection` is `@ApplicationScoped` and implements `RenderableProjection<S>`.
`ProjectionRegistry` (verified present in `casehub-qhorus 0.2-SNAPSHOT` at
`io.casehub.qhorus.runtime.message.ProjectionRegistry`) collects `RenderableProjection` beans
via `@Any Instance<RenderableProjection<?>>`. Registering here enables
`project_channel(sessionId, "debate-summary")` from any Qhorus MCP client.

`ChannelProjection<S>` needs no CDI (pure function, passed directly to
`ProjectionService.project()`). Since `DebateChannelProjection` also implements
`RenderableProjection` (and must be in `ProjectionRegistry`), it requires `@ApplicationScoped`
and lives in `runtime/`. The fold logic and CDI registration are in one class — no split,
no wrapper.

### projectionName: `"debate-summary"`

Forward-looking toward the #27 DebateChannel. The projection is designed for debate-structured
message flows; `"debate-summary"` names the artifact, not the current Q&A model.

---

## Modified: `DocumentReviewer`

Add `reviewHistory` parameter. No backwards-compatible overload — all callers update.

```java
@RegisterAiService
public interface DocumentReviewer {

    @SystemMessage("{{personality}}")
    @UserMessage("""
            Document A (original):
            {{documentA}}

            Document B (revised):
            {{documentB}}

            {{selectionContext}}

            Review history (prior turns in this session):
            {{reviewHistory}}

            Current query: {{query}}

            If this query is outside the scope of document review, respond with \
            declined=true and explain why. Otherwise respond with declined=false \
            and your review in content.
            """)
    ReviewResult review(String personality, String documentA, String documentB,
                        String selectionContext, String reviewHistory, String query);
}
```

When `reviewHistory` is `"No prior review activity in this session."`, the LLM sees an
explicit sentinel — not a blank gap in the template.

---

## Modified: `ReviewerChannelBackend`

### New fields

```java
private final ProjectionService projectionService;
private final ChannelProjection<ReviewState> projection;
private final ReviewConversationRenderer conversationRenderer = new ReviewConversationRenderer();
```

`projection` is typed as `ChannelProjection<ReviewState>` — the backend needs only the fold
interface. Rendering for LLM context is handled by `ReviewConversationRenderer` (plain
transcript), not `DebateChannelProjection.render()` (human-facing markdown). These are
different rendering concerns, not duplication: `SummaryRenderer` produces human-readable
markdown with emoji and timestamps for the `project_channel` MCP tool; `ReviewConversationRenderer`
produces a plain transcript for the LLM.

### Updated constructor

```java
ReviewerChannelBackend(ReviewSessionRegistry registry, UUID channelId,
                       MessageService messageService, DocumentReviewer llm,
                       int maxDocChars, ProjectionService projectionService,
                       ChannelProjection<ReviewState> projection)
```

### Updated `post()` logic

```java
@Override
public void post(ChannelRef channel, OutboundMessage message) {
    if (message.type() != MessageType.QUERY) return;

    // Registry check before projection — avoids a wasted DB scan on dead sessions.
    ReviewSession session = registry.find(channelId).orElse(null);
    if (session == null) {
        LOG.warning("ReviewerChannelBackend.post() — no session for channel " + channelId);
        return;
    }

    Long inReplyTo = messageService
            .findByCorrelationId(message.correlationId().toString())
            .map(m -> m.id)
            .orElse(null);
    if (inReplyTo == null) {
        LOG.warning("Could not resolve inReplyTo for correlationId " + message.correlationId());
        return;
    }

    // maxDocChars guard — fast-path DECLINE before projection or LLM call.
    // Retained from original implementation; must come before the try block.
    if (session.docAContent().length() > maxDocChars
            || session.docBContent().length() > maxDocChars) {
        dispatch(channel, message, inReplyTo, session,
                ReviewResult.decline("Documents exceed the maximum size for review."));
        return;
    }

    ReviewResult result;
    try {
        // Project channel history for conversation context.
        // Current QUERY is in the channel but will be OPEN → excluded by ReviewConversationRenderer.
        ProjectionResult<ReviewState> historyResult =
                projectionService.project(channelId, projection);
        String reviewHistory = conversationRenderer.render(historyResult.state());

        String selectionContext = buildSelectionContext(session);
        result = llm.review(session.personality(), session.docAContent(),
                session.docBContent(), selectionContext, reviewHistory, message.content());
    } catch (Exception e) {
        LOG.warning("Backend error on channel " + channel.name() + ": " + e.getMessage());
        result = ReviewResult.decline("Reviewer encountered an error.");
    }
    dispatch(channel, message, inReplyTo, session, result);
}
```

`projectionService.project()` and `llm.review()` share one try/catch — any failure (DB
error, projection error, LLM error) results in a graceful DECLINE. `dispatch()` is outside
the catch; a dispatch failure is a Qhorus infrastructure error, not a reviewer error.

Exception message sanitization (no API keys) is preserved — the catch logs `e.getMessage()`
(existing behaviour) and dispatches a fixed string.

Note: `conversationRenderer.render(historyResult.state())` handles the empty case internally
(returns the sentinel). No need to call `historyResult.isEmpty()` separately.

---

## Modified: `ReviewerChannelBackendFactory`

Inject `ProjectionService` and `DebateChannelProjection`. Pass both to backend constructor.

```java
@ApplicationScoped
public class ReviewerChannelBackendFactory {
    @Inject ReviewSessionRegistry registry;
    @Inject ChannelGateway gateway;
    @Inject MessageService messageService;
    @Inject DocumentReviewer llm;
    @Inject DraftHouseConfig config;
    @Inject ProjectionService projectionService;      // new
    @Inject DebateChannelProjection projection;        // new — typed as concrete class for injection
    ...
}
```

---

## Test Strategy

### `DebateChannelProjectionTest` (new, `runtime/test/debate/`)

Pure unit test — no CDI container.

- `identity()` returns empty state; fresh instance each call (not same reference)
- `apply()` — raise (HUMAN→REV), respond (AGENT→IMP), decline (AGENT→IMP/DECLINED),
  qualify, flagHuman, memo no-op; immutability check (input state not mutated)
- `handleDecline()` null-guard cases (no prior coverage — new code):
  - null correlationId → state returned unchanged, no log
  - non-null correlationId referencing unknown point → state unchanged, WARNING logged
  - null `message.content()` → rendered as empty string, not "null"
- `agentType()` — HUMAN→REV, AGENT→IMP, null→IAE, SYSTEM→IAE
- `projectionName()` returns `"debate-summary"`
- `render(empty result)` returns non-null non-empty sentinel string
- `render(non-empty result)` returns non-null non-empty string (no format assertions —
  format is tested in `SummaryRendererTest`; avoids cross-module clock issue)

All `MessageView` test helpers use real `ActorType` — no null actorType anywhere.

### `ReviewConversationRendererTest` (new, `api/test/debate/`)

- Empty state → returns sentinel
- State with only OPEN points → returns sentinel (nothing to render)
- State with AGREED point → appears as `Q: ...\nA: ...`
- State with DECLINED point → appears as `(Declined — ...)`
- OPEN point excluded from output
- ACTIVE point excluded from output (silently treated as OPEN)
- PENDING_HUMAN point excluded from output (silently treated as OPEN)
- Multiple completed exchanges appear in insertion order

### `SummaryRendererTest` (updated, `api/test/debate/`)

`SummaryRendererTest` currently uses `SummaryProjector.project(List<DebateEvent>)` to build
test states. After deletion of `SummaryProjector` and `DebateEvent`, these helpers break.
Replace with direct `ReviewState` construction.

```java
// Before (deleted):
ReviewState state = projector.project(List.of(new DebateEvent.RaiseEvent(...)));

// After (direct construction):
var state = new ReviewState(
    Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
        new PointClassification(Priority.P1, Scope.ISOLATED, "§3.2"),
        List.of(new ThreadEntry(null, AgentType.REV, 0, EntryType.RAISE, "Both variants appear.")),
        ReviewStatus.OPEN)),
    List.of());
```

Add test for `ReviewStatus.DECLINED` rendering (🚫 marker).
`setClockForTest()` remains package-private — still works since test and class share module
and package.

### `ReviewSessionLifecycleTest` (updated, `runtime/test/`)

**All stubs using the 5-arg signature** — update to 6 args. This includes the `@BeforeEach
setUp()` method as well as the four test methods. Exhaustive list:
- `setUp()` `@BeforeEach`: `when(documentReviewer.review(any()×5))` → `any()×6`
- `query_dispatchesResponse_andFulfillsCommitment`: `when(...)` stub and `verify(...)` if present
- `query_dispatchesDecline_andDeclinesCommitment_whenReviewerDeclines`: `when(...)` stub
- `query_dispatchesSanitizedDecline_andDeclinesCommitment_onReviewerException`: `when(...)` stub / `thenThrow()`
- Test 4's implicit re-use of `@BeforeEach` stub is fine once setUp() is updated

**New test case 5 — multi-turn history:**

This tests the core invariant being introduced: the second query receives the first Q&A in
its history.

```java
@Test
void secondQuery_receivesPriorExchangeInHistory() {
    String result = tools.startReview(docA.toString(), docB.toString());
    String sessionId = extractSessionId(result);
    activeSessionId = sessionId;
    UUID channelId = UUID.fromString(sessionId);

    // First query — @BeforeEach stub returns "Good revision." for any 6-arg call
    String corrId1 = UUID.randomUUID().toString();
    messageService.dispatch(MessageDispatch.builder().channelId(channelId)
            .sender(DraftHouseMcpTools.HUMAN_INSTANCE_ID)
            .type(MessageType.QUERY).content("First question.")
            .correlationId(corrId1).actorType(ActorType.HUMAN).build());
    await().atMost(TIMEOUT).until(() ->
            messageService.findResponseByCorrelationId(channelId, corrId1).isPresent());

    // Second query
    String corrId2 = UUID.randomUUID().toString();
    messageService.dispatch(MessageDispatch.builder().channelId(channelId)
            .sender(DraftHouseMcpTools.HUMAN_INSTANCE_ID)
            .type(MessageType.QUERY).content("Second question.")
            .correlationId(corrId2).actorType(ActorType.HUMAN).build());
    await().atMost(TIMEOUT).until(() ->
            messageService.findResponseByCorrelationId(channelId, corrId2).isPresent());

    // Capture reviewHistory from both calls — assert on the second invocation's arg.
    // Do not use reset() mid-test; capture all values and index by call order.
    ArgumentCaptor<String> historyCaptor = ArgumentCaptor.forClass(String.class);
    verify(documentReviewer, times(2))
            .review(any(), any(), any(), any(), historyCaptor.capture(), any());
    String secondHistory = historyCaptor.getAllValues().get(1);

    assertThat(secondHistory).contains("First question.");
    assertThat(secondHistory).contains("Good revision.");           // first answer from @BeforeEach stub
    assertThat(secondHistory).doesNotContain("Second question.");   // current Q is OPEN → excluded
}
```

### `ReviewerChannelBackendTest` (updated/created)

**New mock fields and setUp() — use `mock()` style matching the existing class (no `@Mock`
annotation, no `@ExtendWith(MockitoExtension.class)`):**

```java
// Add two new fields alongside existing registry, messageService, llm:
private ProjectionService projectionService;
private ChannelProjection<ReviewState> projection;

@BeforeEach void setUp() {
    registry       = mock(ReviewSessionRegistry.class);
    messageService = mock(MessageService.class);
    llm            = mock(DocumentReviewer.class);
    projectionService = mock(ProjectionService.class);
    projection        = mock(ChannelProjection.class);

    // stub projectionService — non-null state avoids NPE when conversationRenderer.render() is called
    when(projectionService.project(CHANNEL_ID, projection))
            .thenReturn(new ProjectionResult<>(new ReviewState(Map.of(), List.of()), null));

    // Update constructor call — two new trailing args:
    backend = new ReviewerChannelBackend(
            registry, CHANNEL_ID, messageService, llm, 100_000,
            projectionService, projection);

    // ... existing registry, messageService stubs unchanged ...
}
```

Without the `projectionService.project()` stub, the mock returns `null` by default and
`conversationRenderer.render(null)` NPEs before the LLM call.

All `llm.review(any()×5)` mock matchers → `llm.review(any()×6)`.

Verify ordering — `projectionService.project()` must be called before `llm.review()` on
every QUERY. Use Mockito `InOrder`:

```java
InOrder order = inOrder(projectionService, llm);
order.verify(projectionService).project(channelId, projection);
order.verify(llm).review(any(), any(), any(), any(), any(), any());
```

Also verify that fast-path returns do not reach the projection or LLM:
```java
// Non-QUERY message type
verifyNoInteractions(projectionService);

// maxDocChars exceeded — guard fires before try-block, so both are skipped
verifyNoInteractions(projectionService);  // in documentsExceedingMaxSize_dispatchDecline test
verifyNoInteractions(llm);               // already asserted; add projectionService alongside
```

---

## PLATFORM.md Updates

### Cross-repo dependency table additions

| Artifact consumed | Consuming repo | Module | Nature |
|---|---|---|---|
| `casehub-qhorus-api` | `casehub-drafthouse` | `api` | `ChannelProjection<S>`, `RenderableProjection<S>`, `ProjectionResult<S>`, `MessageView`, `MessageType` — debate projection SPI |
| `casehub-qhorus` (runtime) | `casehub-drafthouse` | `runtime` | `ProjectionService` — channel history fold for LLM context (new) |

Remove the old `casehub-drafthouse` api row that listed only `ChannelProjection<S>`.
Removed types (DebateEvent, DebateAgentProvider) were never in the dep table.

---

## Bundled XS Fixes

### #35 — UUID channel slug starts-with-digit

Qhorus segment validation requires `[a-z][a-z0-9]*(-[a-z0-9]+)*`. A UUID starting with a
digit (e.g. `949e5bf3-...`) fails with "Invalid channel name segment". ~62.5% of UUIDs start
with a digit (0–9), since 10 of the 16 hex characters are digits.

**Fix in `DraftHouseMcpTools.startReview()`:**
```java
// Before:
String channelSlug = UUID.randomUUID().toString();
// After:
String channelSlug = "r-" + UUID.randomUUID();
```

The `"r-"` prefix guarantees the segment starts with a letter. No other callers to update.

### #37 — CritiqueResourceTest startup failure

`CritiqueResourceTest.critiqueReturns501` fails at Quarkus startup with
`SRCFG00014: quarkus.langchain4j.anthropic.api-key is required`. `CritiqueResource` was
deleted in an earlier cleanup pass but the test was not. The test references a resource that
no longer exists.

**Fix:** delete `CritiqueResourceTest.java`.

---

## Out of Scope

- **#27 DebateChannel** — structured multi-agent debate with REV/IMP actors and artefactRefs
  metadata. `DebateChannelProjection` is a placeholder; the actorType→AgentType mapping is
  a v1 heuristic that #27 will replace.
- **Reactive projection** (`ReactiveProjectionService`) — wire in #27 or later.
- **Incremental projection in `ReviewerChannelBackend`** — full channel scan per QUERY. Acceptable
  for local use; optimize if session length becomes a performance concern.
- **`beforeId` scoped projection** — the current QUERY appears in the channel by the time
  `post()` fires, but `ReviewConversationRenderer` excludes it (OPEN status). No `beforeId`
  filter needed.
- **`actorType == SYSTEM` handling** — throws today. Add if a system actor ever posts to a
  review channel.
- **SummaryRenderer format quality for MCP output** — emoji, timestamps, P3/ISOLATED labels
  are human-readable noise in the `project_channel` MCP tool response. Acceptable for v1;
  refine when a real consumer appears.
