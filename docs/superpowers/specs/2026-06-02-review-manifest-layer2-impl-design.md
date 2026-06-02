# Review Manifest — Layer 2 Implementation Design
**Date:** 2026-06-02
**Status:** Approved
**Issue:** casehubio/drafthouse#29
**Depends on:** `docs/superpowers/specs/2026-06-01-review-manifest-design.md` (Layer 1 format)

---

## Scope

Layer 2 of the review manifest system: the Java implementation that drives agent
review rounds and projects `debate.md` into `summary.md`. Layer 3 (DraftHouse UI,
Next/Back/Run Until buttons) and Qhorus DebateChannel (#27) are explicitly out of scope.

---

## Module Structure

Four Maven modules under `server/`:

```
server/
  api/           — SPI + domain model. Pure Java, no Quarkus, no JPA.
  runtime/       — Quarkus 3.34.3 app. LangChain4jReviewAgentProvider @DefaultBean.
  parser/        — Quarkus Picocli CLI. Builds to debate-parser-runner.jar.
  claude-agent/  — Optional module. ClaudeAgentSdkReviewAgentProvider @Alternative @Priority(1).
                   Activates by classpath presence. Requires Claude Code CLI installed.
```

`server/claude-agent/` follows the optional module pattern
(`spi-adapter-module-placement.md`): starts here, extract to standalone repo only
on confirmed cross-project adoption.

CDI priority ladder (`persistence-backend-cdi-priority.md`):
- `@DefaultBean` → `LangChain4jReviewAgentProvider` (always present in runtime)
- `@Alternative @Priority(1)` → `ClaudeAgentSdkReviewAgentProvider` (activates when
  `server/claude-agent/` is added as a compile dependency to `server/runtime/`)

---

## server/api/ — Domain Model and SPIs

Package root: `io.casehub.drafthouse`

### Debate domain — `io.casehub.drafthouse.debate`

**`DebateEvent`** — sealed interface, Java 17 records, exhaustive at compile time:

```java
public sealed interface DebateEvent
    permits DebateEvent.RaiseEvent, DebateEvent.ResponseEvent,
            DebateEvent.FlagHumanEvent, DebateEvent.AgentMemo {

    record RaiseEvent(String id, int round, AgentType agent,
                      Priority priority, Scope scope,
                      String location, String content) implements DebateEvent {}

    record ResponseEvent(String id, int round, AgentType agent,
                         String targetId, EntryType type,
                         String content, ReviewStatus statusDirective) implements DebateEvent {}

    record FlagHumanEvent(String id, int round, AgentType agent,
                          String content, String targetId,
                          ReviewStatus statusDirective) implements DebateEvent {}

    record AgentMemo(int round, AgentType agent, String content) implements DebateEvent {}
}
```

Supporting enums: `AgentType` (REV, IMP), `EntryType` (RAISE, AGREE, DISPUTE, QUALIFY,
FLAG_HUMAN), `Priority` (P1, P2, P3), `Scope` (SYSTEMIC, ISOLATED),
`ReviewStatus` (OPEN, ACTIVE, AGREED, PENDING_HUMAN).

**Fold state:**

```java
public record PointClassification(Priority priority, Scope scope, String location) {}
public record ThreadEntry(String entryId, AgentType agent, int round,
                          EntryType type, String content) {}
public record ReviewPoint(String id, PointClassification classification,
                          List<ThreadEntry> thread, ReviewStatus currentStatus) {}
public record FlagEntry(String entryId, int round, AgentType agent, String content) {}
public record ReviewState(LinkedHashMap<String, ReviewPoint> points,
                          List<FlagEntry> humanFlags) {}
```

**Processing classes** — pure functions, no CDI:

- `DebateParser` — `List<DebateEvent> parse(String debateMarkdown)`: line-by-line
  parser detecting round comment boundaries, HTML anchors, entry type headers, and
  `→ [ID] Status:` directives.

- `SummaryProjector` — `ReviewState project(List<DebateEvent> events)`: pure left-fold
  (`PP-20260602-b748c9`). Switch on `DebateEvent` type; `AgentMemo` is a no-op.
  Status authority: last `→ [ID] Status:` directive for each point ID wins.

- `SummaryRenderer` — `String render(ReviewState state)`: deterministic template
  rendering. Agreed points rendered with `~~strikethrough~~` headers. `FlagEntry`
  list rendered at bottom as `⚑` section. Memos never appear in output.

### Review agent SPI — `io.casehub.drafthouse.review`

```java
public interface ReviewAgentProvider {
    List<DebateEntry> executeReviewerRound(ReviewRoundContext context);
    List<DebateEntry> executeImplementerRound(ReviewRoundContext context);
}

public record ReviewRoundContext(
    String specContent,
    String debateContent,
    int roundNumber,
    String sessionId
) {}

public record DebateEntry(
    String id,
    EntryType type,
    String targetId,          // null for raise entries
    String content,
    ReviewStatus statusDirective,  // null when no status update
    String location           // null for non-raise entries
) {}
```

The SPI returns `List<DebateEntry>` — structured, typed. DraftHouse appends them to
`debate.md` in the correct format, runs the parser, commits. Agents never touch
the filesystem directly.

---

## server/runtime/ — LangChain4j Default Provider

**`LangChain4jReviewAgentProvider @DefaultBean`**

Uses `@RegisterAiService` with structured output. Single API call per round — spec
and full `debate.md` passed in context. Returns `List<DebateEntry>` as structured
JSON. No tool-call loop; no accumulating conversation history. Works with any
`ChatModel` configured via `quarkus.langchain4j.*` properties.

```java
@RegisterAiService
public interface SpecReviewerService {
    @SystemMessage("...")  // reviewer system prompt from classpath resource
    @UserMessage("Spec:\n{spec}\n\nDebate so far:\n{debate}\n\nRound: {round}")
    List<DebateEntry> review(String spec, String debate, int round);
}
```

Prompt content loaded from `src/main/resources/prompts/spec-reviewer.txt` and
`spec-implementer.txt` — reviewer and implementer system prompts are classpath
resources, not hardcoded.

**Session management** — also in `server/runtime/`:

- `ReviewSessionService` — creates `~/.drafthouse/reviews/<session-id>/` git repo,
  initialises `debate.md` header and empty `summary.md`, makes initial commit.
  Appends `DebateEntry` list returned by `ReviewAgentProvider` to `debate.md`,
  invokes the parser CLI, commits both files.
- Session ID: `drafthouse-<YYYYMMDD>-<6-char-hex>` from spec path + timestamp.
- Parser invocation: `java -jar ~/.drafthouse/debate-parser-runner.jar <session-path>`
  (assumes jar is pre-installed at that path; path hardcoded v1; configurability
  tracked in #28). The jar is built from `server/parser/` and must be placed there
  manually or by an install script — not auto-deployed by the server.

---

## server/parser/ — Deterministic Summary CLI

Quarkus Picocli module. Thin entry point over `server/api/` processing classes.

```java
@CommandLine.Command(name = "debate-parser", mixinStandardHelpOptions = true)
@QuarkusMain
public class ParseCommand implements Runnable {

    @CommandLine.Parameters(index = "0", description = "Session directory path")
    Path sessionPath;

    @Override
    public void run() {
        String debateText = Files.readString(sessionPath.resolve("debate.md"));
        List<DebateEvent> events = new DebateParser().parse(debateText);
        ReviewState state = new SummaryProjector().project(events);
        Files.writeString(sessionPath.resolve("summary.md"),
                          new SummaryRenderer().render(state));
    }
}
```

Builds to `debate-parser-runner.jar` (uber-jar). Native profile present but not
required to pass CI.

---

## server/claude-agent/ — Claude Agent SDK Provider

**`ClaudeAgentSdkReviewAgentProvider @Alternative @Priority(1)`**

Depends on `com.github.spring-ai-community:claude-agent-sdk-java:1.0.0`.

Uses `ClaudeAsyncClient` → converted to Mutiny `Multi` via
`Multi.createFrom().publisher(flux)`. Launches a genuine Claude Code CLI session
per round. DraftHouse's MCP tools (`submit_debate_entry`, `read_spec_section`,
`finish_round`) are configured as an in-process MCP server — the agent calls them
natively via the MCP protocol rather than returning structured JSON.

Requires Claude Code CLI installed and authenticated on the host. Off by default.
Activates when `server/claude-agent/` is added as a compile dependency to
`server/runtime/pom.xml`.

---

## Testing

### Contract test — `ReviewAgentProviderContractTest` (in `server/api/` test sources)

Abstract base class. Both implementations must pass all cases:

```java
public abstract class ReviewAgentProviderContractTest {
    protected abstract ReviewAgentProvider provider();

    @Test void reviewerRaisesPointsOnFreshDebate();
    @Test void implementerRespondsToAllOpenPoints();
    @Test void entryIdsFollowScheme();          // [RN-REV-NNN] / [RN-IMP-NNN]
    @Test void statusDirectivePresentForEveryResponse();
    @Test void flagHumanProducedWhenAmbiguous();
    @Test void memoNotIncludedInReturnedEntries();
    @Test void agreeTransitionsStatusToAgreed();
    @Test void disputeTransitionsStatusToActive();
    @Test void qualifyTransitionsStatusToActive();
    @Test void newRaiseInRound3HasCorrectRoundNumber();
}
```

Fixtures in `server/api/src/test/resources/fixtures/review/` — shared, not
duplicated per implementation.

### Concrete implementations

```java
// Always runs in CI
class LangChain4jReviewAgentProviderTest extends ReviewAgentProviderContractTest {
    protected ReviewAgentProvider provider() {
        return new LangChain4jReviewAgentProvider(mockChatModel());
    }
}

// Off by default — requires Claude Code CLI
@IfBuildProperty(name = "drafthouse.claude-agent.test.enabled", stringValue = "true")
class ClaudeAgentSdkReviewAgentProviderTest extends ReviewAgentProviderContractTest {
    protected ReviewAgentProvider provider() {
        return new ClaudeAgentSdkReviewAgentProvider(realClaudeClient());
    }
}
```

### Unit tests — processing classes

`DebateParserTest`, `SummaryProjectorTest`, `SummaryRendererTest` — pure Java,
no Quarkus, no mocking. Fast. Cover all entry types, all status transitions, all
edge cases (empty debate, flag-human at round 1, agreed point strikethrough).

### E2E — parser CLI

`ParseCommandIT` — drives `ParseCommand` against fixture pairs
(`debate-N.md` → expected `summary-N.md`). Fixtures cover: round-1-only,
round-2-with-mixed-responses, round-3-with-qualify-and-flag, multi-point-mixed-states.

---

## Platform Alignment

| Protocol | Applied where |
|---|---|
| `persistence-backend-cdi-priority.md` | `@DefaultBean` / `@Alternative @Priority(1)` for providers |
| `spi-adapter-module-placement.md` | `server/claude-agent/` starts in-repo; extract when adopted cross-project |
| `optional-module-pattern.md` | `server/claude-agent/` activates by classpath presence |
| `event-log-left-fold-projection.md` (PP-20260602-b748c9) | `SummaryProjector` left-fold design |
| `auth-retrofit-readiness.md` | No auth types in SPI signatures |

---

## Out of Scope

- Layer 3 DraftHouse UI (Next/Back/Run Until) — separate spec
- Qhorus DebateChannel (#27) — `ReviewAgentProvider` designed to migrate cleanly
- Session path configurability (#28)
- Sub-agent architecture / context management (#26)
- qhorus#230 `ChannelProjection<S>` SPI — implement locally first
