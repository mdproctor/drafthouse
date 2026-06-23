# Review Channel AgentRegistry Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify review channel personality resolution with AgentRegistry + SystemPromptRenderer, matching the debate channel pattern established in #62.

**Architecture:** Extract `ReviewerResolver` as the single entry point for all reviewer agent operations (resolve, list, find). Migrate `ReviewSession` from a flat `personality` string to `ResolvedReviewer` record. Move cross-channel agent tools (`list_reviewers`, `get_reviewer_instructions`) from `DebateMcpTools` to `DraftHouseMcpTools`. Remove `config.reviewer().personality()`.

**Tech Stack:** Java 17, Quarkus 3.34.3, Eidos API 0.2-SNAPSHOT (`AgentRegistry`, `SystemPromptRenderer`, `AgentPromptContext`), JUnit 5, Mockito, AssertJ

## Global Constraints

- **Issue:** casehubio/drafthouse#73 — every commit footer: `Refs #73` (or `Closes #73` on the final commit)
- **Module boundary:** `ResolvedReviewer` in `server/api/` (pure Java, no Quarkus). `ReviewerResolver` in `server/runtime/`.
- **Build command:** `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
- **No `@Nullable` annotations** — use explicit null checks (`if (x != null && !x.isBlank())`)
- **`jsonString` visibility:** define as `static String jsonString(String s)` with package-private access on `DraftHouseMcpTools`; `DebateMcpTools` calls it directly (same package)

---

### Task 1: ResolvedReviewer record + ReviewerResolver service + ReviewerDescriptorSeeder.SLOT

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/ResolvedReviewer.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerResolver.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerDescriptorSeeder.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerResolverTest.java`

**Interfaces:**
- Consumes: `AgentRegistry.findById(String, String)`, `AgentRegistry.find(AgentQuery)`, `SystemPromptRenderer.render(AgentDescriptor, AgentPromptContext)`, `ReviewerDescriptorSeeder.TENANCY_ID`, `ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID`
- Produces: `ResolvedReviewer(String agentId, String name, String instructions)`, `ReviewerResolver.resolve(String agentId, Resource... resources)`, `ReviewerResolver.listAvailable()`, `ReviewerResolver.findDescriptor(String agentId)`, `ReviewerDescriptorSeeder.SLOT`

- [ ] **Step 1: Create `ResolvedReviewer` record**

```java
// server/api/src/main/java/io/casehub/drafthouse/ResolvedReviewer.java
package io.casehub.drafthouse;

public record ResolvedReviewer(String agentId, String name, String instructions) {}
```

- [ ] **Step 2: Add `SLOT` constant to `ReviewerDescriptorSeeder`**

In `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerDescriptorSeeder.java`, add below the existing constants (line 23):

```java
public static final String SLOT = "document-reviewer";
```

Then replace all 4 occurrences of the magic string `"document-reviewer"` in the `seed()` builder calls with `SLOT`:

```java
// In each of structuralReviewer(), contentReviewer(), readabilityReviewer(), completenessReviewer():
.slot(SLOT)
```

- [ ] **Step 3: Write `ReviewerResolverTest` — all 9 tests**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/ReviewerResolverTest.java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.SystemPromptRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewerResolverTest {

    private AgentRegistry agentRegistry;
    private SystemPromptRenderer systemPromptRenderer;
    private ReviewerResolver resolver;

    private AgentDescriptor structuralDescriptor;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        systemPromptRenderer = mock(SystemPromptRenderer.class);

        resolver = new ReviewerResolver();
        resolver.agentRegistry = agentRegistry;
        resolver.systemPromptRenderer = systemPromptRenderer;

        structuralDescriptor = AgentDescriptor.builder()
                .agentId(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID)
                .name("Structural Reviewer")
                .slot(ReviewerDescriptorSeeder.SLOT)
                .tenancyId(ReviewerDescriptorSeeder.TENANCY_ID)
                .build();

        when(agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.of(structuralDescriptor));
        when(systemPromptRenderer.render(any(), any()))
                .thenReturn(new SystemPromptRenderer.RenderedPrompt(
                        "rendered instructions", SystemPromptRenderer.RenderFormat.MARKDOWN,
                        null, null, false));
    }

    @Test
    void resolve_withExplicitAgentId_returnsResolvedReviewer() {
        when(agentRegistry.findById("drafthouse-structural-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.of(structuralDescriptor));

        ResolvedReviewer result = resolver.resolve("drafthouse-structural-reviewer");

        assertThat(result.agentId()).isEqualTo("drafthouse-structural-reviewer");
        assertThat(result.name()).isEqualTo("Structural Reviewer");
        assertThat(result.instructions()).isEqualTo("rendered instructions");
    }

    @Test
    void resolve_withNullAgentId_usesDefaultReviewer() {
        ResolvedReviewer result = resolver.resolve(null);

        assertThat(result.agentId()).isEqualTo(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID);
        assertThat(result.name()).isEqualTo("Structural Reviewer");
    }

    @Test
    void resolve_withBlankAgentId_usesDefaultReviewer() {
        ResolvedReviewer result = resolver.resolve("  ");

        assertThat(result.agentId()).isEqualTo(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID);
    }

    @Test
    void resolve_withUnknownAgentId_throwsIllegalArgument() {
        when(agentRegistry.findById("unknown", ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown reviewer agent: unknown");
    }

    @Test
    void resolve_withNoAgentsRegistered_throwsIllegalState() {
        when(agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no reviewer agents registered");
    }

    @Test
    void resolve_passesResourcesToRenderer() {
        var resource = new io.casehub.eidos.api.Resource("spec.md", "spec", "file");
        resolver.resolve(null, resource);

        ArgumentCaptor<AgentPromptContext> ctxCaptor =
                ArgumentCaptor.forClass(AgentPromptContext.class);
        verify(systemPromptRenderer).render(eq(structuralDescriptor), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().resources()).containsExactly(resource);
    }

    @Test
    void resolve_goalIncludesAgentName() {
        resolver.resolve(null);

        ArgumentCaptor<AgentPromptContext> ctxCaptor =
                ArgumentCaptor.forClass(AgentPromptContext.class);
        verify(systemPromptRenderer).render(any(), ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().goal()).isPresent();
        assertThat(ctxCaptor.getValue().goal().get().description())
                .contains("structural reviewer");
    }

    @Test
    void listAvailable_returnsAllReviewerAgents() {
        when(agentRegistry.find(AgentQuery.bySlot(ReviewerDescriptorSeeder.SLOT,
                ReviewerDescriptorSeeder.TENANCY_ID)))
                .thenReturn(List.of(structuralDescriptor));

        List<AgentDescriptor> result = resolver.listAvailable();

        assertThat(result).containsExactly(structuralDescriptor);
    }

    @Test
    void findDescriptor_knownAgent_returnsDescriptor() {
        when(agentRegistry.findById("drafthouse-structural-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.of(structuralDescriptor));

        Optional<AgentDescriptor> result =
                resolver.findDescriptor("drafthouse-structural-reviewer");

        assertThat(result).contains(structuralDescriptor);
    }

    @Test
    void findDescriptor_unknownAgent_returnsEmpty() {
        when(agentRegistry.findById("nonexistent", ReviewerDescriptorSeeder.TENANCY_ID))
                .thenReturn(Optional.empty());

        Optional<AgentDescriptor> result = resolver.findDescriptor("nonexistent");

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerResolverTest`
Expected: FAIL — `ReviewerResolver` class does not exist yet

- [ ] **Step 5: Implement `ReviewerResolver`**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/ReviewerResolver.java
package io.casehub.drafthouse;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalContext;
import io.casehub.eidos.api.Resource;
import io.casehub.eidos.api.SystemPromptRenderer;

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

- [ ] **Step 6: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerResolverTest`
Expected: ALL PASS (9 tests)

- [ ] **Step 7: Commit**

```
feat: add ResolvedReviewer, ReviewerResolver, and SLOT constant

Refs #73
```

Files: `server/api/src/main/java/io/casehub/drafthouse/ResolvedReviewer.java`, `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerResolver.java`, `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerDescriptorSeeder.java`, `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerResolverTest.java`

---

### Task 2: Migrate ReviewSession + DocumentReviewer + ReviewerChannelBackend

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`

**Interfaces:**
- Consumes: `ResolvedReviewer` (from Task 1)
- Produces: `ReviewSession.reviewer()` returns `ResolvedReviewer`, `ReviewSession.withSelection()` preserves reviewer, `DocumentReviewer.review(String instructions, ...)`, `ReviewerChannelBackend.post()` reads `session.reviewer().instructions()`

- [ ] **Step 1: Modify `ReviewSession`** — replace `String personality` with `ResolvedReviewer reviewer`

Replace the full record in `server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java`:

```java
package io.casehub.drafthouse;

import java.util.UUID;

public record ReviewSession(
        UUID channelId,
        String sessionId,
        String channelName,
        String instanceId,
        String docAContent,
        String docBContent,
        SelectionScope selection,
        ResolvedReviewer reviewer
) {
    public ReviewSession withSelection(final SelectionScope selection) {
        return new ReviewSession(
                channelId, sessionId, channelName, instanceId,
                docAContent, docBContent, selection, reviewer
        );
    }
}
```

- [ ] **Step 2: Modify `DocumentReviewer`** — rename parameter, move response protocol to `@SystemMessage`

Replace the full interface in `server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java`:

```java
package io.casehub.drafthouse;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

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

- [ ] **Step 3: Modify `ReviewerChannelBackend.post()`** — one line change

In `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`, line 87, change:

```java
// Before:
result = llm.review(session.personality(), session.docAContent(),
        session.docBContent(), selectionContext, reviewHistory, message.content());
// After:
result = llm.review(session.reviewer().instructions(), session.docAContent(),
        session.docBContent(), selectionContext, reviewHistory, message.content());
```

- [ ] **Step 4: Verify the project compiles** (tests will fail — callers not yet migrated)

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests`
Expected: BUILD SUCCESS (compile only — the `ReviewSession` constructor signature change breaks test callers but not compilation of the record itself)

Note: this step may fail if `DraftHouseMcpTools.java` or tests reference `session.personality()` — that's expected. The compile check confirms the api module builds; runtime compilation failures are fixed in Task 3.

- [ ] **Step 5: Commit**

```
refactor: migrate ReviewSession, DocumentReviewer, ReviewerChannelBackend to instructions

Refs #73
```

Files: `server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java`, `server/runtime/src/main/java/io/casehub/drafthouse/DocumentReviewer.java`, `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`

---

### Task 3: Migrate DraftHouseMcpTools + move agent tools + config cleanup + update tests

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java`
- Modify: `server/runtime/src/main/resources/application.properties`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`
- Modify: `docs/protocols/drafthouse-config-mock-two-level.md`

**Interfaces:**
- Consumes: `ReviewerResolver.resolve()`, `ReviewerResolver.listAvailable()`, `ReviewerResolver.findDescriptor()`, `ResolvedReviewer`, `ReviewSession(... ResolvedReviewer)`
- Produces: updated MCP tool surface — `start_review` with `agentId`, `list_reviewers` on `DraftHouseMcpTools`, `get_reviewer_instructions` generalised on `DraftHouseMcpTools`

- [ ] **Step 1: Move `jsonString` to `DraftHouseMcpTools` with package-private visibility**

Add to `DraftHouseMcpTools.java` at the end of the class (before the closing brace):

```java
    static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
```

- [ ] **Step 2: Migrate `DraftHouseMcpTools.startReview()`**

Add import and injection:

```java
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.Resource;

// Add field:
@Inject ReviewerResolver resolver;
```

Replace `startReview` method entirely:

```java
    @Tool(name = "start_review",
          description = "Start a document review session. Returns JSON with sessionId "
              + "(use for all subsequent calls), channel, and reviewer (agentId, name, "
              + "instructions). Use list_reviewers to see available agents.")
    public String startReview(
            @ToolArg(description = "Absolute path to document A (the 'before' version)") String docAPath,
            @ToolArg(description = "Absolute path to document B (the 'after' version)") String docBPath,
            @ToolArg(description = "Eidos agent ID for the reviewer. Omit for default "
                    + "reviewer. Use list_reviewers to see available agents.")
            String agentId) {

        ResolvedReviewer reviewer;
        try {
            reviewer = resolver.resolve(agentId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }

        String docAContent = readFile(docAPath);
        if (docAContent == null) return "error: could not read document A: " + docAPath;

        String docBContent = readFile(docBPath);
        if (docBContent == null) return "error: could not read document B: " + docBPath;

        if (docAContent.length() > config.reviewer().maxDocChars()) {
            return "error: document A exceeds maximum size of " + config.reviewer().maxDocChars() + " characters";
        }
        if (docBContent.length() > config.reviewer().maxDocChars()) {
            return "error: document B exceeds maximum size of " + config.reviewer().maxDocChars() + " characters";
        }

        String channelSlug = "r-" + UUID.randomUUID();
        String channelName = "drafthouse/" + channelSlug;

        String instanceId = null;
        Channel channel = null;
        try {
            channel = channelService.create(channelName, "DraftHouse review session",
                    ChannelSemantic.APPEND, null);

            String sessionId = channel.id.toString();
            String resolvedChannelName = channel.name;
            instanceId = "drafthouse-reviewer-" + sessionId;
            instanceService.register(instanceId, reviewer.name() + " " + sessionId,
                    List.of("document-review"));

            ReviewSession session = new ReviewSession(
                    channel.id, sessionId, resolvedChannelName, instanceId,
                    docAContent, docBContent, null, reviewer);

            registry.put(session);
            channelGateway.initChannel(channel.id, new ChannelRef(channel.id, resolvedChannelName));

            return "{\"sessionId\":\"" + sessionId + "\",\"channel\":\"" + resolvedChannelName
                    + "\",\"reviewer\":{\"agentId\":" + jsonString(reviewer.agentId())
                    + ",\"name\":" + jsonString(reviewer.name())
                    + ",\"instructions\":" + jsonString(reviewer.instructions()) + "}}";

        } catch (Exception e) {
            LOG.warning("start_review failed: " + e.getMessage() + " — attempting cleanup");
            if (channel != null) {
                if (instanceId != null) {
                    try { instanceService.deregister(instanceId); } catch (Exception ce) { LOG.warning("cleanup instance: " + ce.getMessage()); }
                }
                try { registry.remove(channel.id); } catch (Exception ce) { LOG.warning("cleanup registry: " + ce.getMessage()); }
                try { channelService.delete(channel.id, true); } catch (Exception ce) { LOG.warning("cleanup channel: " + ce.getMessage()); }
            }
            return "error: " + e.getMessage();
        }
    }
```

- [ ] **Step 3: Add `list_reviewers` and `get_reviewer_instructions` to `DraftHouseMcpTools`**

Add these two methods to `DraftHouseMcpTools.java` after the `readFile` method:

```java
    @Tool(name = "list_reviewers",
          description = "List available reviewer agents. Each agent has a distinct review perspective "
                  + "defined by its disposition and briefing. Use the agentId with start_review or start_debate.")
    public String listReviewers() {
        var descriptors = resolver.listAvailable();
        if (descriptors.isEmpty()) return "[]";

        var sb = new StringBuilder("[");
        for (int i = 0; i < descriptors.size(); i++) {
            if (i > 0) sb.append(",");
            var d = descriptors.get(i);
            sb.append("{\"agentId\":").append(jsonString(d.agentId()));
            sb.append(",\"name\":").append(jsonString(d.name()));
            sb.append(",\"slot\":").append(jsonString(d.slot()));

            if (d.disposition() != null) {
                sb.append(",\"disposition\":{");
                boolean first = true;
                for (DispositionAxis axis : DispositionAxis.values()) {
                    var val = d.disposition().get(axis);
                    if (val.isPresent()) {
                        if (!first) sb.append(",");
                        sb.append("\"").append(axis.jsonKey()).append("\":").append(jsonString(val.get()));
                        first = false;
                    }
                }
                sb.append("}");
            }

            if (!d.capabilities().isEmpty()) {
                sb.append(",\"capabilities\":[");
                for (int j = 0; j < d.capabilities().size(); j++) {
                    if (j > 0) sb.append(",");
                    var cap = d.capabilities().get(j);
                    sb.append("{\"name\":").append(jsonString(cap.name()));
                    if (cap.tags() != null && !cap.tags().isEmpty()) {
                        sb.append(",\"tags\":[");
                        for (int k = 0; k < cap.tags().size(); k++) {
                            if (k > 0) sb.append(",");
                            sb.append(jsonString(cap.tags().get(k)));
                        }
                        sb.append("]");
                    }
                    sb.append("}");
                }
                sb.append("]");
            }

            if (d.briefing() != null) {
                String summary = d.briefing().length() > 200
                        ? d.briefing().substring(0, 200) + "..."
                        : d.briefing();
                sb.append(",\"briefingSummary\":").append(jsonString(summary));
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @Tool(name = "get_reviewer_instructions",
          description = "Render full reviewer instructions for an agent, optionally "
              + "in the context of a resource.")
    public String getReviewerInstructions(
            @ToolArg(description = "Eidos agent ID for the reviewer") String agentId,
            @ToolArg(description = "Optional resource path for contextual rendering")
            String resourcePath) {

        if (agentId == null || agentId.isBlank()) return "error: agentId is required";

        ResolvedReviewer reviewer;
        try {
            Resource[] resources = resourcePath != null && !resourcePath.isBlank()
                    ? new Resource[]{new Resource(resourcePath, "spec", "file")}
                    : new Resource[0];
            reviewer = resolver.resolve(agentId, resources);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        return "{\"agentId\":" + jsonString(reviewer.agentId())
                + ",\"name\":" + jsonString(reviewer.name())
                + ",\"instructions\":" + jsonString(reviewer.instructions()) + "}";
    }
```

- [ ] **Step 4: Migrate `DebateMcpTools`**

In `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`:

**4a.** Add injection, remove old injections:

```java
// Add:
@Inject ReviewerResolver resolver;

// Remove these two fields:
// @Inject AgentRegistry agentRegistry;
// @Inject SystemPromptRenderer systemPromptRenderer;
```

**4b.** Replace the agent resolution block in `startDebate()` (lines 85-98). Replace:

```java
        // Resolve reviewer before creating channel — fail fast on unknown agent
        AgentDescriptor reviewerDescriptor;
        String resolvedAgentId;
        if (agentId != null && !agentId.isBlank()) {
            reviewerDescriptor = agentRegistry.findById(agentId, ReviewerDescriptorSeeder.TENANCY_ID).orElse(null);
            if (reviewerDescriptor == null) return "error: unknown reviewer agent: " + agentId;
            resolvedAgentId = agentId;
        } else {
            reviewerDescriptor = agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                    ReviewerDescriptorSeeder.TENANCY_ID).orElse(null);
            if (reviewerDescriptor == null) return "error: no reviewer agents registered";
            resolvedAgentId = reviewerDescriptor.agentId();
        }
        String instructions = renderInstructions(reviewerDescriptor, specPath);
```

With:

```java
        ResolvedReviewer reviewer;
        try {
            reviewer = resolver.resolve(agentId, new Resource(specPath, "spec", "file"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
```

Then update session creation (line 106):

```java
// Before:
session = new DebateSession(channel.id, debateSessionId, resolvedName, resolvedAgentId);
// After:
session = new DebateSession(channel.id, debateSessionId, resolvedName, reviewer.agentId());
```

And JSON response (lines 123-127):

```java
// Before:
return "{\"debateSessionId\":\"" + debateSessionId + "\",\"channel\":\"" + resolvedName
        + "\",\"specPath\":" + jsonString(specPath)
        + ",\"reviewer\":{\"agentId\":" + jsonString(resolvedAgentId)
        + ",\"name\":" + jsonString(reviewerDescriptor.name())
        + ",\"instructions\":" + jsonString(instructions) + "}}";
// After:
return "{\"debateSessionId\":\"" + debateSessionId + "\",\"channel\":\"" + resolvedName
        + "\",\"specPath\":" + DraftHouseMcpTools.jsonString(specPath)
        + ",\"reviewer\":{\"agentId\":" + DraftHouseMcpTools.jsonString(reviewer.agentId())
        + ",\"name\":" + DraftHouseMcpTools.jsonString(reviewer.name())
        + ",\"instructions\":" + DraftHouseMcpTools.jsonString(reviewer.instructions()) + "}}";
```

**4c.** Replace `restartFromRound()` agent resolution block (lines 506-514):

```java
// Before:
if (original.agentId() != null) {
    var desc = agentRegistry.findById(original.agentId(), ReviewerDescriptorSeeder.TENANCY_ID);
    if (desc.isPresent()) {
        String restartInstructions = renderInstructions(desc.get(), original.primaryPath());
        reviewerJson = ",\"reviewer\":{\"agentId\":" + jsonString(original.agentId())
                + ",\"name\":" + jsonString(desc.get().name())
                + ",\"instructions\":" + jsonString(restartInstructions) + "}";
    }
}
// After:
if (original.agentId() != null) {
    ResolvedReviewer restartReviewer = resolver.resolve(
            original.agentId(), new Resource(original.primaryPath(), "spec", "file"));
    reviewerJson = ",\"reviewer\":{\"agentId\":" + DraftHouseMcpTools.jsonString(restartReviewer.agentId())
            + ",\"name\":" + DraftHouseMcpTools.jsonString(restartReviewer.name())
            + ",\"instructions\":" + DraftHouseMcpTools.jsonString(restartReviewer.instructions()) + "}";
}
```

**4d.** Replace `getDebateSummary()` agent lookup (lines 294-300):

```java
// Before:
if (session.agentId() != null) {
    var desc = agentRegistry.findById(session.agentId(), ReviewerDescriptorSeeder.TENANCY_ID);
    if (desc.isPresent()) {
        reviewerJson = ",\"reviewer\":{\"agentId\":" + jsonString(session.agentId())
                + ",\"name\":" + jsonString(desc.get().name()) + "}";
    }
}
// After:
if (session.agentId() != null) {
    var name = resolver.findDescriptor(session.agentId()).map(AgentDescriptor::name).orElse(null);
    if (name != null) {
        reviewerJson = ",\"reviewer\":{\"agentId\":" + DraftHouseMcpTools.jsonString(session.agentId())
                + ",\"name\":" + DraftHouseMcpTools.jsonString(name) + "}";
    }
}
```

**4e.** Replace `exportDebateSummary()` agent lookup (lines 669-673):

```java
// Before:
if (session.agentId() != null) {
    var desc = agentRegistry.findById(session.agentId(), ReviewerDescriptorSeeder.TENANCY_ID);
    if (desc.isPresent()) {
        summary = "**Reviewer:** " + desc.get().name() + " (" + session.agentId() + ")\n\n" + summary;
    }
}
// After:
if (session.agentId() != null) {
    var name = resolver.findDescriptor(session.agentId()).map(AgentDescriptor::name).orElse(null);
    if (name != null) {
        summary = "**Reviewer:** " + name + " (" + session.agentId() + ")\n\n" + summary;
    }
}
```

**4f.** Remove `listReviewers()`, `getReviewerInstructions()`, and `renderInstructions()` methods from `DebateMcpTools`.

**4g.** Change all remaining `jsonString(...)` calls in `DebateMcpTools` to `DraftHouseMcpTools.jsonString(...)`. Then remove the private `jsonString` method from `DebateMcpTools`.

**4h.** Remove unused imports from `DebateMcpTools`: `AgentRegistry`, `SystemPromptRenderer`, `AgentPromptContext`, `GoalContext`, `DispositionAxis`, `AgentQuery`, `AgentCapability`. Keep `AgentDescriptor` (used by `findDescriptor` result) and `Resource` (used by `resolve` call).

- [ ] **Step 5: Config cleanup**

In `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java`, remove `personality()` from the `Reviewer` interface:

```java
    interface Reviewer {
        @WithDefault("100000")
        int maxDocChars();
    }
```

In `server/runtime/src/main/resources/application.properties`, remove:

```
casehub.drafthouse.reviewer.personality=You review document changes for clarity, accuracy, and coherence. Be specific and constructive.
```

- [ ] **Step 6: Update `DraftHouseMcpToolsTest`**

Replace the full test class. Key changes:
- Add `ReviewerResolver resolver` mock
- Remove two-level config personality mocking
- `minimalSession()` uses `ResolvedReviewer`
- New tests for `startReview_withAgentId`, `_withUnknownAgentId`, `_response_includesReviewerBlock`
- New tests for `listReviewers`, `getReviewerInstructions`

The `setUp` method changes from:

```java
DraftHouseConfig.Reviewer reviewer = mock(DraftHouseConfig.Reviewer.class);
when(config.reviewer()).thenReturn(reviewer);
when(reviewer.maxDocChars()).thenReturn(100_000);
when(reviewer.personality()).thenReturn("You are a reviewer.");
```

To:

```java
DraftHouseConfig.Reviewer reviewerConfig = mock(DraftHouseConfig.Reviewer.class);
when(config.reviewer()).thenReturn(reviewerConfig);
when(reviewerConfig.maxDocChars()).thenReturn(100_000);

resolver = mock(ReviewerResolver.class);
ResolvedReviewer defaultReviewer = new ResolvedReviewer(
        "drafthouse-structural-reviewer", "Structural Reviewer", "mock instructions");
when(resolver.resolve(isNull())).thenReturn(defaultReviewer);
when(resolver.resolve(eq("drafthouse-structural-reviewer"))).thenReturn(defaultReviewer);
```

And field injection adds `tools.resolver = resolver;`.

The `minimalSession` helper changes from:

```java
private ReviewSession minimalSession(UUID channelId) {
    return new ReviewSession(
            channelId, channelId.toString(), "drafthouse/test",
            "drafthouse-reviewer-" + channelId,
            "Doc A", "Doc B", null, "You are a reviewer.");
}
```

To:

```java
private ReviewSession minimalSession(UUID channelId) {
    return new ReviewSession(
            channelId, channelId.toString(), "drafthouse/test",
            "drafthouse-reviewer-" + channelId,
            "Doc A", "Doc B", null,
            new ResolvedReviewer("drafthouse-structural-reviewer",
                    "Structural Reviewer", "mock instructions"));
}
```

The `startReview_happyPath` assertion changes from:

```java
assertThat(session.personality()).isEqualTo("You are a reviewer.");
```

To:

```java
assertThat(session.reviewer().agentId()).isEqualTo("drafthouse-structural-reviewer");
assertThat(session.reviewer().name()).isEqualTo("Structural Reviewer");
assertThat(session.reviewer().instructions()).isEqualTo("mock instructions");
```

Add new tests:

```java
@Test
void startReview_withAgentId_usesSpecifiedAgent() throws IOException {
    ResolvedReviewer custom = new ResolvedReviewer(
            "drafthouse-content-reviewer", "Content Reviewer", "content instructions");
    when(resolver.resolve(eq("drafthouse-content-reviewer"))).thenReturn(custom);
    Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
    Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

    String result = tools.startReview(docA.toString(), docB.toString(),
            "drafthouse-content-reviewer");

    assertThat(result).contains("\"agentId\":\"drafthouse-content-reviewer\"");
    assertThat(result).contains("\"name\":\"Content Reviewer\"");
}

@Test
void startReview_withUnknownAgentId_returnsError() throws IOException {
    when(resolver.resolve(eq("unknown")))
            .thenThrow(new IllegalArgumentException("unknown reviewer agent: unknown"));
    Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
    Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

    String result = tools.startReview(docA.toString(), docB.toString(), "unknown");

    assertThat(result).isEqualTo("error: unknown reviewer agent: unknown");
    verifyNoInteractions(channelService);
}

@Test
void startReview_response_includesReviewerBlock() throws IOException {
    Path docA = Files.writeString(tempDir.resolve("a.md"), "A");
    Path docB = Files.writeString(tempDir.resolve("b.md"), "B");

    String result = tools.startReview(docA.toString(), docB.toString(), null);

    assertThat(result).contains("\"reviewer\":{");
    assertThat(result).contains("\"agentId\":\"drafthouse-structural-reviewer\"");
    assertThat(result).contains("\"name\":\"Structural Reviewer\"");
    assertThat(result).contains("\"instructions\":\"mock instructions\"");
}
```

Update existing tests that call `startReview` to pass the new `agentId` parameter (use `null` for default):

```java
// All existing startReview calls change from:
tools.startReview(docA.toString(), docB.toString())
// To:
tools.startReview(docA.toString(), docB.toString(), null)
```

- [ ] **Step 7: Update `DebateMcpToolsTest`**

In `setUp()`:
- Remove `agentRegistry` and `systemPromptRenderer` mocks
- Add `ReviewerResolver resolver = mock(ReviewerResolver.class);`
- Mock resolver instead of inline registry:

```java
ResolvedReviewer defaultReviewer = new ResolvedReviewer(
        ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID, "Structural Reviewer", "mock instructions");
when(resolver.resolve(isNull(), any(Resource[].class))).thenReturn(defaultReviewer);
when(resolver.resolve(eq(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID), any(Resource[].class)))
        .thenReturn(defaultReviewer);
```

Inject: `tools.resolver = resolver;`

Remove: `tools.agentRegistry = agentRegistry;` and `tools.systemPromptRenderer = systemPromptRenderer;`

For `startDebate_withUnknownAgentId`:

```java
when(resolver.resolve(eq("unknown-agent"), any(Resource[].class)))
        .thenThrow(new IllegalArgumentException("unknown reviewer agent: unknown-agent"));
```

For `getDebateSummary_returnsJsonWithReviewerField`, mock `resolver.findDescriptor()`:

```java
AgentDescriptor descriptor = AgentDescriptor.builder()
        .agentId("drafthouse-structural-reviewer")
        .name("Structural Reviewer")
        .slot("document-review")
        .tenancyId(ReviewerDescriptorSeeder.TENANCY_ID)
        .build();
when(resolver.findDescriptor("drafthouse-structural-reviewer"))
        .thenReturn(Optional.of(descriptor));
```

Remove `listReviewers_returns4Entries`, `getReviewerInstructions_withoutSession_returnsRenderedPrompt`, and `getReviewerInstructions_withUnknownAgent_returnsError` tests (moved to `DraftHouseMcpToolsTest`).

Update all remaining `jsonString(...)` call assertions if any reference the old private method behaviour — they should work identically since the implementation is the same.

- [ ] **Step 8: Update protocol**

In `docs/protocols/drafthouse-config-mock-two-level.md`:

Change the `violation_hint` from:
```
violation_hint: "when(config.reviewer().personality()) throws NullPointerException in setUp() — not in a @Test method"
```
To:
```
violation_hint: "when(config.reviewer().maxDocChars()) throws NullPointerException in setUp() — not in a @Test method"
```

In the body, replace `config.reviewer().personality()` examples with `config.reviewer().maxDocChars()`:

```
`DraftHouseConfig` uses nested sub-interfaces (`Reviewer`, `Storage`). A plain `mock(DraftHouseConfig.class)` returns `null` for all reference-type methods, so chaining `config.reviewer().maxDocChars()` in a stub throws `NullPointerException` during `when()` setup — not at test execution. Always create a separate mock for each sub-interface and stub the intermediate method first: `DraftHouseConfig.Reviewer reviewer = mock(DraftHouseConfig.Reviewer.class); when(config.reviewer()).thenReturn(reviewer);` before stubbing any leaf methods. Do not use `RETURNS_DEEP_STUBS` — it hides unstubbed leaves that silently return null/0.
```

- [ ] **Step 9: Run the full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: ALL PASS

- [ ] **Step 10: Commit**

```
feat: migrate review channel to AgentRegistry, reorganise agent tool surface

Closes #73
```

Files: all modified files listed above

---

## Self-Review Checklist

**Spec coverage:**
- §1 ResolvedReviewer → Task 1 Step 1 ✓
- §2 ReviewerResolver → Task 1 Steps 3-5 ✓
- §3 SLOT constant → Task 1 Step 2 ✓
- §4 ReviewSession migration → Task 2 Step 1 ✓
- §5 DocumentReviewer → Task 2 Step 2 ✓
- §6 ReviewerChannelBackend → Task 2 Step 3 ✓
- §7 DraftHouseMcpTools → Task 3 Steps 1-3 ✓
- §8 DebateMcpTools → Task 3 Step 4 ✓
- §9 Config cleanup → Task 3 Step 5 ✓
- §10 Tool surface → Task 3 Steps 2-4 ✓
- §11 Tests → Task 1 Step 3, Task 3 Steps 6-7 ✓
- Implementation note (jsonString) → Task 3 Step 1 ✓
- Protocol update → Task 3 Step 8 ✓

**Placeholder scan:** No TBD/TODO/placeholder text found.

**Type consistency:**
- `ResolvedReviewer` — same name in Task 1 (creation), Task 2 (ReviewSession), Task 3 (MCP tools) ✓
- `ReviewerResolver.resolve()` signature — consistent across Task 1 (definition) and Task 3 (callers) ✓
- `ReviewerResolver.findDescriptor()` — consistent between Task 1 (definition) and Task 3 Step 4d/4e (callers) ✓
- `ReviewerResolver.listAvailable()` — consistent between Task 1 (definition) and Task 3 Step 3 (caller) ✓
- `session.reviewer().instructions()` — consistent between Task 2 (ReviewSession) and Task 2 Step 3 (backend) ✓
