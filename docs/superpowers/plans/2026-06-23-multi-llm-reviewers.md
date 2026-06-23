# Multi-LLM Reviewers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable DraftHouse debate sessions to use distinct reviewer personalities resolved from Eidos's `AgentRegistry`, with rendered instructions returned to the MCP caller.

**Architecture:** DraftHouse depends on `casehub-eidos-api` for `AgentDescriptor`, `AgentRegistry`, and `SystemPromptRenderer`. A `@DefaultBean` in-memory registry and simple renderer ship for standalone use. A `@Startup` seeder registers 4 reviewer descriptors into whatever registry is active. `start_debate` resolves a reviewer, renders instructions, and returns them to the MCP caller. Session stores `agentId` for provenance only.

**Tech Stack:** Java 17, Quarkus 3.34.3, casehub-eidos-api 0.2-SNAPSHOT, Flyway, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-23-multi-llm-reviewers-design.md`

---

### Task 1: Add casehub-eidos-api dependency

**Files:**
- Modify: `server/pom.xml` (add version property)
- Modify: `server/api/pom.xml` (add dependency)

- [ ] **Step 1: Add eidos version property to parent pom**

In `server/pom.xml`, add inside `<properties>`:

```xml
<casehub.eidos.version>0.2-SNAPSHOT</casehub.eidos.version>
```

- [ ] **Step 2: Add dependency to api module**

In `server/api/pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-eidos-api</artifactId>
  <version>${casehub.eidos.version}</version>
</dependency>
```

- [ ] **Step 3: Verify compilation**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml compile -DskipTests`
Expected: BUILD SUCCESS — eidos-api resolves from GitHub Packages.

- [ ] **Step 4: Commit**

```bash
git add server/pom.xml server/api/pom.xml
git commit -m "chore: add casehub-eidos-api dependency to api module

Refs #62"
```

---

### Task 2: DraftHouseReviewerRegistry — in-memory AgentRegistry mock

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseReviewerRegistry.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseReviewerRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DraftHouseReviewerRegistryTest {

    private DraftHouseReviewerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DraftHouseReviewerRegistry();
    }

    @Test
    void register_and_findById_roundTrips() {
        var descriptor = testDescriptor("test-agent");
        registry.register(descriptor);
        assertThat(registry.findById("test-agent", "drafthouse"))
                .isPresent()
                .get().extracting(AgentDescriptor::name).isEqualTo("Test Agent");
    }

    @Test
    void findById_wrongTenancy_returnsEmpty() {
        registry.register(testDescriptor("test-agent"));
        assertThat(registry.findById("test-agent", "other-tenancy")).isEmpty();
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertThat(registry.findById("no-such-agent", "drafthouse")).isEmpty();
    }

    @Test
    void find_bySlot_returnsMatchingDescriptors() {
        registry.register(testDescriptor("a1"));
        registry.register(AgentDescriptor.builder()
                .agentId("a2").name("Other").slot("other-slot").tenancyId("drafthouse").build());

        var results = registry.find(AgentQuery.bySlot("document-reviewer", "drafthouse"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).agentId()).isEqualTo("a1");
    }

    @Test
    void find_byCapability_returnsMatchingDescriptors() {
        registry.register(testDescriptor("a1"));
        var results = registry.find(AgentQuery.byCapability("document-review", "drafthouse"));
        assertThat(results).hasSize(1);
    }

    @Test
    void register_sameId_overwrites() {
        registry.register(testDescriptor("a1"));
        var updated = AgentDescriptor.builder()
                .agentId("a1").name("Updated").slot("document-reviewer").tenancyId("drafthouse").build();
        registry.register(updated);
        assertThat(registry.findById("a1", "drafthouse").get().name()).isEqualTo("Updated");
    }

    private static AgentDescriptor testDescriptor(String agentId) {
        return AgentDescriptor.builder()
                .agentId(agentId)
                .name("Test Agent")
                .slot("document-reviewer")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review").tags(List.of("structural")).build()))
                .disposition(AgentDisposition.builder()
                        .conflictMode("collaborative").ruleFollowing("strict").build())
                .tenancyId("drafthouse")
                .build();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DraftHouseReviewerRegistryTest`
Expected: FAIL — `DraftHouseReviewerRegistry` does not exist.

- [ ] **Step 3: Write implementation**

```java
package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class DraftHouseReviewerRegistry implements AgentRegistry {

    private final ConcurrentHashMap<String, AgentDescriptor> store = new ConcurrentHashMap<>();

    @Override
    public void register(AgentDescriptor descriptor) {
        store.put(descriptor.agentId(), descriptor);
    }

    @Override
    public Optional<AgentDescriptor> findById(String agentId, String tenancyId) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        return Optional.ofNullable(store.get(agentId))
                .filter(d -> d.tenancyId().equals(tenancyId));
    }

    @Override
    public List<AgentDescriptor> find(AgentQuery query) {
        return store.values().stream()
                .filter(d -> d.tenancyId().equals(query.tenancyId()))
                .filter(d -> query.slot() == null || Objects.equals(d.slot(), query.slot()))
                .filter(d -> query.capabilityName() == null
                        || d.capabilities().stream().anyMatch(c -> Objects.equals(c.name(), query.capabilityName())))
                .toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DraftHouseReviewerRegistryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseReviewerRegistry.java server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseReviewerRegistryTest.java
git commit -m "feat: DraftHouseReviewerRegistry — @DefaultBean in-memory AgentRegistry

Refs #62"
```

---

### Task 3: SimplePromptRenderer — mock SystemPromptRenderer

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/SimplePromptRenderer.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/SimplePromptRendererTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.drafthouse;

import io.casehub.eidos.api.*;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.SystemPromptRenderer.RenderedPrompt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SimplePromptRendererTest {

    private final SimplePromptRenderer renderer = new SimplePromptRenderer();

    private static AgentDescriptor reviewerDescriptor() {
        return AgentDescriptor.builder()
                .agentId("drafthouse-structural-reviewer")
                .name("Structural Reviewer")
                .slot("document-reviewer")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review")
                        .inputTypes(List.of("markdown"))
                        .outputTypes(List.of("review"))
                        .tags(List.of("structural")).build()))
                .disposition(AgentDisposition.builder()
                        .conflictMode("collaborative")
                        .ruleFollowing("strict")
                        .build())
                .briefing("You focus on structural integrity.")
                .tenancyId("drafthouse")
                .build();
    }

    @Test
    void render_includesNameAndRole() {
        RenderedPrompt result = renderer.render(reviewerDescriptor(),
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).contains("# Structural Reviewer");
        assertThat(result.content()).contains("## Role");
        assertThat(result.content()).contains("document-reviewer");
    }

    @Test
    void render_includesCapabilities() {
        RenderedPrompt result = renderer.render(reviewerDescriptor(),
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).contains("## Capabilities");
        assertThat(result.content()).contains("document-review");
    }

    @Test
    void render_includesDisposition_onlyNonNullAxes() {
        RenderedPrompt result = renderer.render(reviewerDescriptor(),
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).contains("## How You Operate");
        assertThat(result.content()).contains("Conflict mode: collaborative");
        assertThat(result.content()).contains("Rule following: strict");
        assertThat(result.content()).doesNotContain("Social orientation:");
        assertThat(result.content()).doesNotContain("Risk appetite:");
        assertThat(result.content()).doesNotContain("Autonomy:");
    }

    @Test
    void render_includesBriefing() {
        RenderedPrompt result = renderer.render(reviewerDescriptor(),
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).contains("## Operating Principles");
        assertThat(result.content()).contains("You focus on structural integrity.");
    }

    @Test
    void render_omitsDataHandling_whenBothNull() {
        RenderedPrompt result = renderer.render(reviewerDescriptor(),
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).doesNotContain("## Data Handling");
    }

    @Test
    void render_includesDataHandling_whenPresent() {
        var descriptor = AgentDescriptor.builder()
                .agentId("test").name("Test").slot("reviewer").tenancyId("t")
                .jurisdiction("EU").dataHandlingPolicy("gdpr-compliant").build();

        RenderedPrompt result = renderer.render(descriptor,
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).contains("## Data Handling");
        assertThat(result.content()).contains("Jurisdiction: EU");
        assertThat(result.content()).contains("Policy: gdpr-compliant");
    }

    @Test
    void render_includesGoalAndResources_whenProvided() {
        var context = AgentPromptContext.forFormat(RenderFormat.MARKDOWN)
                .withGoal(new GoalContext("Review for structure", List.of("Find gaps"), null))
                .withResources(List.of(new Resource("/spec.md", "spec", "file")));

        RenderedPrompt result = renderer.render(reviewerDescriptor(), context);

        assertThat(result.content()).contains("## Current Goal");
        assertThat(result.content()).contains("Review for structure");
        assertThat(result.content()).contains("Find gaps");
        assertThat(result.content()).contains("## Resources");
        assertThat(result.content()).contains("/spec.md");
    }

    @Test
    void render_omitsGoalAndResources_whenFormatOnly() {
        RenderedPrompt result = renderer.render(reviewerDescriptor(),
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.content()).doesNotContain("## Current Goal");
        assertThat(result.content()).doesNotContain("## Resources");
    }

    @Test
    void render_returnsNullHashes_notEnriched() {
        RenderedPrompt result = renderer.render(reviewerDescriptor(),
                AgentPromptContext.forFormat(RenderFormat.MARKDOWN));

        assertThat(result.descriptorHash()).isNull();
        assertThat(result.contextHash()).isNull();
        assertThat(result.enriched()).isFalse();
        assertThat(result.format()).isEqualTo(RenderFormat.MARKDOWN);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=SimplePromptRendererTest`
Expected: FAIL — `SimplePromptRenderer` does not exist.

- [ ] **Step 3: Write implementation**

```java
package io.casehub.drafthouse;

import io.casehub.eidos.api.*;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
import io.casehub.eidos.api.SystemPromptRenderer.RenderedPrompt;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class SimplePromptRenderer implements SystemPromptRenderer {

    @Override
    public RenderedPrompt render(AgentDescriptor descriptor, AgentPromptContext context) {
        var sb = new StringBuilder();

        sb.append("# ").append(descriptor.name()).append("\n");
        sb.append("**Agent ID:** ").append(descriptor.agentId()).append("\n");

        if (descriptor.slot() != null) {
            sb.append("\n## Role\n").append(descriptor.slot()).append("\n");
        }

        if (descriptor.capabilities() != null && !descriptor.capabilities().isEmpty()) {
            sb.append("\n## Capabilities\n");
            for (AgentCapability cap : descriptor.capabilities()) {
                sb.append("- **").append(cap.name()).append("**");
                if (cap.inputTypes() != null && !cap.inputTypes().isEmpty())
                    sb.append(": accepts ").append(String.join(", ", cap.inputTypes()));
                if (cap.outputTypes() != null && !cap.outputTypes().isEmpty())
                    sb.append(" → ").append(String.join(", ", cap.outputTypes()));
                sb.append("\n");
            }
        }

        if (descriptor.disposition() != null) {
            AgentDisposition d = descriptor.disposition();
            sb.append("\n## How You Operate\n");
            for (DispositionAxis axis : DispositionAxis.values()) {
                d.get(axis).ifPresent(raw ->
                        sb.append("- ").append(axisLabel(axis)).append(": ").append(raw).append("\n"));
            }
            sb.append("- Can delegate: ").append(d.delegation() ? "yes" : "no").append("\n");
        }

        if (descriptor.briefing() != null) {
            sb.append("\n## Operating Principles\n").append(descriptor.briefing()).append("\n");
        }

        if (descriptor.jurisdiction() != null || descriptor.dataHandlingPolicy() != null) {
            sb.append("\n## Data Handling\n");
            if (descriptor.jurisdiction() != null)
                sb.append("Jurisdiction: ").append(descriptor.jurisdiction()).append("\n");
            if (descriptor.dataHandlingPolicy() != null)
                sb.append("Policy: ").append(descriptor.dataHandlingPolicy()).append("\n");
        }

        context.goal().ifPresent(goal -> {
            sb.append("\n## Current Goal\n").append(goal.description()).append("\n");
            if (!goal.subGoals().isEmpty())
                goal.subGoals().forEach(sub -> sb.append("- ").append(sub).append("\n"));
            if (goal.caseRef() != null)
                sb.append("Case: ").append(goal.caseRef()).append("\n");
        });

        if (!context.resources().isEmpty()) {
            sb.append("\n## Resources\n");
            for (Resource r : context.resources()) {
                sb.append("- **").append(r.label() != null ? r.label() : r.uri()).append("**: ").append(r.uri());
                if (r.type() != null) sb.append(" (").append(r.type()).append(")");
                sb.append("\n");
            }
        }

        if (context.situationalContext() != null) {
            sb.append("\n## Context\n").append(context.situationalContext()).append("\n");
        }

        return new RenderedPrompt(sb.toString().trim(), RenderFormat.MARKDOWN, null, null, false);
    }

    private static String axisLabel(DispositionAxis axis) {
        return switch (axis) {
            case SOCIAL_ORIENTATION -> "Social orientation";
            case RULE_FOLLOWING     -> "Rule following";
            case RISK_APPETITE      -> "Risk appetite";
            case AUTONOMY           -> "Autonomy";
            case CONFLICT_MODE      -> "Conflict mode";
        };
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=SimplePromptRendererTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/SimplePromptRenderer.java server/runtime/src/test/java/io/casehub/drafthouse/SimplePromptRendererTest.java
git commit -m "feat: SimplePromptRenderer — @DefaultBean mock SystemPromptRenderer

Renders AgentDescriptor to MARKDOWN without LLM enrichment or
vocabulary resolution. Null hashes, enriched=false.

Refs #62"
```

---

### Task 4: ReviewerDescriptorSeeder — register 4 reviewer descriptors at startup

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerDescriptorSeeder.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewerDescriptorSeederTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReviewerDescriptorSeederTest {

    @Test
    void seed_registers4Descriptors_intoRegistry() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();

        var reviewers = registry.find(
                AgentQuery.bySlot("document-reviewer", ReviewerDescriptorSeeder.TENANCY_ID));
        assertThat(reviewers).hasSize(4);
        assertThat(reviewers).extracting("agentId").containsExactlyInAnyOrder(
                "drafthouse-structural-reviewer",
                "drafthouse-content-reviewer",
                "drafthouse-readability-reviewer",
                "drafthouse-completeness-reviewer");
    }

    @Test
    void seed_structuralReviewer_hasCorrectDisposition() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();

        var descriptor = registry.findById("drafthouse-structural-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID).orElseThrow();
        assertThat(descriptor.name()).isEqualTo("Structural Reviewer");
        assertThat(descriptor.slot()).isEqualTo("document-reviewer");
        assertThat(descriptor.disposition().conflictMode()).isEqualTo("collaborative");
        assertThat(descriptor.disposition().ruleFollowing()).isEqualTo("strict");
        assertThat(descriptor.briefing()).isNotBlank();
        assertThat(descriptor.capabilities()).hasSize(1);
        assertThat(descriptor.capabilities().get(0).name()).isEqualTo("document-review");
    }

    @Test
    void seed_contentReviewer_hasCompetingConflictMode() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();

        var descriptor = registry.findById("drafthouse-content-reviewer",
                ReviewerDescriptorSeeder.TENANCY_ID).orElseThrow();
        assertThat(descriptor.disposition().conflictMode()).isEqualTo("competing");
        assertThat(descriptor.disposition().riskAppetite()).isEqualTo("cautious");
    }

    @Test
    void seed_isIdempotent() {
        AgentRegistry registry = new DraftHouseReviewerRegistry();
        var seeder = new ReviewerDescriptorSeeder(registry);
        seeder.seed();
        seeder.seed();

        var reviewers = registry.find(
                AgentQuery.bySlot("document-reviewer", ReviewerDescriptorSeeder.TENANCY_ID));
        assertThat(reviewers).hasSize(4);
    }

    @Test
    void defaultReviewerId_isStructural() {
        assertThat(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID)
                .isEqualTo("drafthouse-structural-reviewer");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerDescriptorSeederTest`
Expected: FAIL — `ReviewerDescriptorSeeder` does not exist.

- [ ] **Step 3: Write implementation**

```java
package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.runtime.Startup;

import java.util.List;

@Startup
@ApplicationScoped
public class ReviewerDescriptorSeeder {

    public static final String TENANCY_ID = "drafthouse";
    public static final String DEFAULT_REVIEWER_ID = "drafthouse-structural-reviewer";

    private final AgentRegistry registry;

    @Inject
    ReviewerDescriptorSeeder(AgentRegistry registry) {
        this.registry = registry;
    }

    @Startup
    void seed() {
        registry.register(AgentDescriptor.builder()
                .agentId("drafthouse-structural-reviewer")
                .name("Structural Reviewer")
                .slot("document-reviewer")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review").tags(List.of("structural")).build()))
                .disposition(AgentDisposition.builder()
                        .conflictMode("collaborative").ruleFollowing("strict").build())
                .briefing("You review documents for structural integrity. Focus on gaps, contradictions, missing sections, logical flow, and internal consistency. Flag where the argument breaks down or where sections don't connect.")
                .tenancyId(TENANCY_ID).build());

        registry.register(AgentDescriptor.builder()
                .agentId("drafthouse-content-reviewer")
                .name("Content Reviewer")
                .slot("document-reviewer")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review").tags(List.of("content")).build()))
                .disposition(AgentDisposition.builder()
                        .conflictMode("competing").riskAppetite("cautious").build())
                .briefing("You review documents for content correctness. Challenge factual claims, verify technical accuracy, and demand evidence. Accept nothing on assertion alone — if a claim lacks support, flag it.")
                .tenancyId(TENANCY_ID).build());

        registry.register(AgentDescriptor.builder()
                .agentId("drafthouse-readability-reviewer")
                .name("Readability Reviewer")
                .slot("document-reviewer")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review").tags(List.of("readability")).build()))
                .disposition(AgentDisposition.builder()
                        .conflictMode("accommodating").autonomy("directed").build())
                .briefing("You review documents for readability. Focus on clarity, tone, audience fit, jargon reduction, sentence structure, and cross-referencing. Suggest reordering and restructuring where it improves the reading experience.")
                .tenancyId(TENANCY_ID).build());

        registry.register(AgentDescriptor.builder()
                .agentId("drafthouse-completeness-reviewer")
                .name("Completeness Reviewer")
                .slot("document-reviewer")
                .capabilities(List.of(AgentCapability.builder()
                        .name("document-review").tags(List.of("completeness")).build()))
                .disposition(AgentDisposition.builder()
                        .conflictMode("collaborative").ruleFollowing("strict").build())
                .briefing("You review documents for completeness against stated goals. Check every requirement, edge case, and acceptance criterion. Flag anything omitted, underspecified, or assumed but not stated.")
                .tenancyId(TENANCY_ID).build());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewerDescriptorSeederTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/ReviewerDescriptorSeeder.java server/runtime/src/test/java/io/casehub/drafthouse/ReviewerDescriptorSeederTest.java
git commit -m "feat: ReviewerDescriptorSeeder — register 4 reviewer descriptors at startup

Seeds structural, content, readability, completeness reviewers into
whatever AgentRegistry CDI resolves. Idempotent. Defines TENANCY_ID
and DEFAULT_REVIEWER_ID constants.

Refs #62"
```

---

### Task 5: Add agentId to DebateSession, DebateSessionSnapshot, and DebateSessionEntity

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSessionSnapshot.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionStoreContractTest.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionEntity.java`
- Create: `server/runtime/src/main/resources/db/drafthouse/migration/V102__add_agent_id_to_debate_session.sql`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/JpaDebateSessionStoreTest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateSessionLifecycleTest.java`

This task has many call sites. Apply all changes, then run the full test suite.

- [ ] **Step 1: Add agentId to DebateSessionSnapshot**

In `server/api/src/main/java/io/casehub/drafthouse/DebateSessionSnapshot.java`, add the field:

```java
public record DebateSessionSnapshot(
        UUID channelId,
        String debateSessionId,
        String channelName,
        List<DocumentEntry> documents,
        ComparisonPair comparison,
        Map<AgentType, String> participants,
        String agentId) {}
```

- [ ] **Step 2: Add agentId to DebateSession constructors**

In `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`:

Add field:
```java
private final String agentId;
```

Update both constructors to accept `String agentId` as the last parameter:
```java
public DebateSession(final UUID channelId, final String debateSessionId,
                     final String channelName, final String agentId) {
    this.channelId       = channelId;
    this.debateSessionId = debateSessionId;
    this.channelName     = channelName;
    this.agentId         = agentId;
    this.documentSet     = new DocumentSet();
}

public DebateSession(final UUID channelId, final String debateSessionId,
                     final String channelName, final DocumentSet documentSet,
                     final String agentId) {
    this.channelId       = channelId;
    this.debateSessionId = debateSessionId;
    this.channelName     = channelName;
    this.documentSet     = documentSet;
    this.agentId         = agentId;
}
```

Add getter:
```java
public String agentId() { return agentId; }
```

Update `branchFrom()`:
```java
public static DebateSession branchFrom(final DebateSession source, final UUID channelId,
                                       final String sessionId, final String channelName) {
    return new DebateSession(channelId, sessionId, channelName,
            DocumentSet.copyOf(source.documentSet), source.agentId);
}
```

Update `fromSnapshot()` — add `snapshot.agentId()` to the constructor call:
```java
DebateSession session = new DebateSession(
        snapshot.channelId(), snapshot.debateSessionId(),
        snapshot.channelName(), ds, snapshot.agentId());
```

Update `snapshot()`:
```java
return new DebateSessionSnapshot(
        channelId, debateSessionId, channelName,
        docs, comp, Map.copyOf(participants), agentId);
```

- [ ] **Step 3: Update DebateSessionStoreContractTest**

Every `new DebateSessionSnapshot(...)` call needs an `agentId` parameter appended. In `testSnapshot()`:
```java
private DebateSessionSnapshot testSnapshot() {
    UUID channelId = UUID.randomUUID();
    return new DebateSessionSnapshot(
            channelId, channelId.toString(), "drafthouse/debate/d-test",
            List.of(new DocumentEntry("/a.md", "spec")),
            new ComparisonPair("/a.md", "/b.md"),
            Map.of(AgentType.REV, "rev-id"),
            null);
}
```

In `save_sameId_updatesExisting` test:
```java
var updated = new DebateSessionSnapshot(
        snap.channelId(), snap.debateSessionId(), snap.channelName(),
        List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
        null, snap.participants(), snap.agentId());
```

- [ ] **Step 4: Update DebateSessionTest**

Update `sessionWithSpec()` helper:
```java
private static DebateSession sessionWithSpec(String specPath) {
    var session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, (String) null);
    if (specPath != null) session.addDocument(specPath, "spec");
    return session;
}
```

Update all `new DebateSession(CHANNEL_ID, SESSION_ID, NAME)` calls to `new DebateSession(CHANNEL_ID, SESSION_ID, NAME, (String) null)`.

Update `fromSnapshot_reconstitutesLiveSession` test — add `null` agentId to the snapshot constructor:
```java
var snap = new DebateSessionSnapshot(
        CHANNEL_ID, SESSION_ID, NAME,
        List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
        new ComparisonPair("/a.md", "/b.md"),
        Map.of(AgentType.REV, "rev-id", AgentType.IMP, "imp-id"),
        null);
```

Add new test for agentId:
```java
@Test
void agentId_storedAtConstruction_carriedThroughBranchAndSnapshot() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "drafthouse-structural-reviewer");
    assertThat(session.agentId()).isEqualTo("drafthouse-structural-reviewer");

    UUID newId = UUID.randomUUID();
    DebateSession branched = DebateSession.branchFrom(session, newId, newId.toString(), "new-ch");
    assertThat(branched.agentId()).isEqualTo("drafthouse-structural-reviewer");

    DebateSessionSnapshot snap = session.snapshot();
    assertThat(snap.agentId()).isEqualTo("drafthouse-structural-reviewer");

    DebateSession restored = DebateSession.fromSnapshot(snap);
    assertThat(restored.agentId()).isEqualTo("drafthouse-structural-reviewer");
}

@Test
void agentId_null_isAllowed() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME, (String) null);
    assertThat(session.agentId()).isNull();
}
```

- [ ] **Step 5: Update DebateSessionEntity**

Add field:
```java
@Column(name = "agent_id")
String agentId;
```

Update `toSnapshot()`:
```java
return new DebateSessionSnapshot(channelId, debateSessionId, channelName,
        docs, cp, Map.copyOf(participants), agentId);
```

Update `fromSnapshot()`:
```java
entity.agentId = snap.agentId();
```

- [ ] **Step 6: Create Flyway migration V102**

Create `server/runtime/src/main/resources/db/drafthouse/migration/V102__add_agent_id_to_debate_session.sql`:

```sql
ALTER TABLE debate_session ADD COLUMN agent_id VARCHAR(255);
```

- [ ] **Step 7: Fix remaining compile errors**

Search all files for `new DebateSession(` and `new DebateSessionSnapshot(` calls and add the `agentId` parameter. Key locations:
- `DebateMcpTools.startDebate()` — `new DebateSession(channel.id, debateSessionId, resolvedName)` → add `null` (will be replaced in Task 6)
- `DebateMcpTools.restartFromRound()` — `DebateSession.branchFrom(...)` — already updated via `branchFrom()`
- `DebateSessionRegistryImpl` — any snapshot construction
- `DebateSessionLifecycleTest` — any session/snapshot construction
- `JpaDebateSessionStoreTest` — any snapshot construction

- [ ] **Step 8: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add agentId to DebateSession, snapshot, entity, and V102 migration

Constructor signature change across DebateSession, DebateSessionSnapshot,
and DebateSessionEntity. All call sites updated. Flyway V102 adds
agent_id column. agentId is nullable — set at start_debate, copied by
branchFrom, persisted via snapshot.

Refs #62"
```

---

### Task 6: Wire reviewer resolution into start_debate and get_debate_summary

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`

- [ ] **Step 1: Add injected dependencies**

Add to `DebateMcpTools` fields:

```java
@Inject AgentRegistry agentRegistry;
@Inject SystemPromptRenderer systemPromptRenderer;
```

- [ ] **Step 2: Add reviewer resolution helper**

Add private helper method:

```java
private record ResolvedReviewer(String agentId, String name, String instructions) {}

private ResolvedReviewer resolveReviewer(String agentId) {
    AgentDescriptor descriptor;
    if (agentId != null) {
        descriptor = agentRegistry.findById(agentId, ReviewerDescriptorSeeder.TENANCY_ID).orElse(null);
        if (descriptor == null) return null;
    } else {
        descriptor = agentRegistry.findById(ReviewerDescriptorSeeder.DEFAULT_REVIEWER_ID,
                ReviewerDescriptorSeeder.TENANCY_ID).orElse(null);
        if (descriptor == null) return null;
        agentId = descriptor.agentId();
    }
    return new ResolvedReviewer(agentId, descriptor.name(), null);
}

private String renderInstructions(AgentDescriptor descriptor, String specPath) {
    var context = AgentPromptContext.forFormat(SystemPromptRenderer.RenderFormat.MARKDOWN)
            .withGoal(GoalContext.of("Review document changes from a "
                    + descriptor.name().toLowerCase() + " perspective"))
            .withResources(specPath != null
                    ? java.util.List.of(new Resource(specPath, "spec", "file"))
                    : java.util.List.of());
    return systemPromptRenderer.render(descriptor, context).content();
}
```

- [ ] **Step 3: Update startDebate method**

Add the `personalityKey` parameter (named `agentId` in the tool arg):

```java
@ToolArg(description = "Eidos agent ID for the reviewer (e.g. 'drafthouse-structural-reviewer'). "
        + "Omit for default reviewer. Use list_reviewers to see available agents.")
String agentId
```

Inside `startDebate`, before creating the session:

```java
// Resolve reviewer
String resolvedAgentId;
AgentDescriptor reviewerDescriptor;
if (agentId != null) {
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

Update session construction:
```java
session = new DebateSession(channel.id, debateSessionId, resolvedName, resolvedAgentId);
```

Update the return JSON to include the reviewer object:
```java
return "{\"debateSessionId\":\"" + debateSessionId + "\",\"channel\":\"" + resolvedName
        + "\",\"specPath\":" + jsonString(specPath)
        + ",\"reviewer\":{\"agentId\":" + jsonString(resolvedAgentId)
        + ",\"name\":" + jsonString(reviewerDescriptor.name())
        + ",\"instructions\":" + jsonString(instructions) + "}}";
```

- [ ] **Step 4: Update getDebateSummary — breaking format change to JSON**

Replace the return statement to wrap in JSON:

```java
String reviewerJson = "";
if (session.agentId() != null) {
    var desc = agentRegistry.findById(session.agentId(), ReviewerDescriptorSeeder.TENANCY_ID);
    if (desc.isPresent()) {
        reviewerJson = ",\"reviewer\":{\"agentId\":" + jsonString(session.agentId())
                + ",\"name\":" + jsonString(desc.get().name()) + "}";
    }
}
return "{\"summary\":" + jsonString(summary) + reviewerJson + "}";
```

- [ ] **Step 5: Update restartFromRound — include reviewer in response**

After creating the new session, add reviewer to the response JSON. Re-resolve the source session's agentId and render instructions:

```java
String reviewerJson = "";
if (original.agentId() != null) {
    var desc = agentRegistry.findById(original.agentId(), ReviewerDescriptorSeeder.TENANCY_ID);
    if (desc.isPresent()) {
        String restartInstructions = renderInstructions(desc.get(), original.primaryPath());
        reviewerJson = ",\"reviewer\":{\"agentId\":" + jsonString(original.agentId())
                + ",\"name\":" + jsonString(desc.get().name())
                + ",\"instructions\":" + jsonString(restartInstructions) + "}";
    }
}
```

Append `reviewerJson` to the return string.

- [ ] **Step 6: Update exportDebateSummary — add reviewer provenance**

After rendering the summary, before writing to file:

```java
if (session.agentId() != null) {
    var desc = agentRegistry.findById(session.agentId(), ReviewerDescriptorSeeder.TENANCY_ID);
    if (desc.isPresent()) {
        summary = "**Reviewer:** " + desc.get().name() + " (" + session.agentId() + ")\n\n" + summary;
    }
}
```

- [ ] **Step 7: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: Some existing tests may fail due to the get_debate_summary JSON format change — fix assertions in the next step.

- [ ] **Step 8: Fix broken test assertions**

Update any tests that assert on raw markdown from `getDebateSummary` to parse JSON and extract the `summary` field. Update any tests that assert on `startDebate` response to account for the new `reviewer` field.

- [ ] **Step 9: Run full test suite again**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: ALL PASS

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: wire reviewer resolution into start_debate and get_debate_summary

start_debate resolves AgentDescriptor via AgentRegistry, renders
instructions via SystemPromptRenderer, returns reviewer object.
get_debate_summary restructured from raw markdown to JSON (breaking).
export_debate_summary prepends reviewer provenance. restart_from_round
includes reviewer instructions.

Refs #62"
```

---

### Task 7: New MCP tools — list_reviewers and get_reviewer_instructions

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`

- [ ] **Step 1: Add list_reviewers tool**

```java
@Tool(name = "list_reviewers",
      description = "List available reviewer agents. Each agent has a distinct review perspective "
              + "defined by its disposition and briefing. Use the agentId with start_debate.")
public String listReviewers() {
    var descriptors = agentRegistry.find(
            AgentQuery.bySlot("document-reviewer", ReviewerDescriptorSeeder.TENANCY_ID));
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
```

- [ ] **Step 2: Add get_reviewer_instructions tool**

```java
@Tool(name = "get_reviewer_instructions",
      description = "Get the rendered system prompt for a reviewer agent. Use after reconnecting "
              + "to an existing debate session to re-obtain the reviewer persona. Pass debateSessionId "
              + "to render with session-specific context (spec path, goal). Without it, renders with "
              + "format only.")
public String getReviewerInstructions(
        @ToolArg(description = "Eidos agent ID for the reviewer") String agentId,
        @ToolArg(description = "Optional debate session ID for session-specific context") String debateSessionId) {

    if (agentId == null || agentId.isBlank()) return "error: agentId is required";

    var descriptor = agentRegistry.findById(agentId, ReviewerDescriptorSeeder.TENANCY_ID).orElse(null);
    if (descriptor == null) return "error: unknown reviewer agent: " + agentId;

    String specPath = null;
    if (debateSessionId != null && !debateSessionId.isBlank()) {
        DebateSession session = resolveSession(debateSessionId);
        if (session != null) {
            specPath = session.primaryPath();
        }
    }

    String instructions = renderInstructions(descriptor, specPath);
    return "{\"agentId\":" + jsonString(agentId)
            + ",\"name\":" + jsonString(descriptor.name())
            + ",\"instructions\":" + jsonString(instructions) + "}";
}
```

- [ ] **Step 3: Add imports**

Add to the imports block:
```java
import io.casehub.eidos.api.*;
import io.casehub.eidos.api.SystemPromptRenderer.RenderFormat;
```

- [ ] **Step 4: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: list_reviewers and get_reviewer_instructions MCP tools

list_reviewers returns structured data with disposition axes,
capabilities, and briefing summary (200-char truncation).
get_reviewer_instructions renders system prompt with optional
session-specific context for recovery.

Refs #62"
```

---

### Task 8: Update SessionInfo to include agentId

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`

- [ ] **Step 1: Update SessionInfo record**

```java
record SessionInfo(String debateSessionId, String channelName, String specPath, String agentId) {}
```

- [ ] **Step 2: Update activeSessions()**

```java
.map(s -> new SessionInfo(s.debateSessionId(), s.channelName(), s.primaryPath(), s.agentId()))
```

- [ ] **Step 3: Run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS (update any test assertions on SessionInfo if needed)

- [ ] **Step 4: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java
git commit -m "feat: add agentId to SessionInfo for session discovery

Refs #62"
```

---

### Task 9: Integration tests for reviewer resolution

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateSessionLifecycleTest.java` (or create new test class)

- [ ] **Step 1: Write integration tests**

Add tests covering:
1. `start_debate` with explicit agentId — response contains `reviewer.instructions`
2. `start_debate` without agentId — defaults to structural reviewer
3. `start_debate` with unknown agentId — returns error
4. `list_reviewers` — returns 4 entries with correct structure
5. `get_reviewer_instructions` with debateSessionId — includes goal context
6. `get_reviewer_instructions` without debateSessionId — format-only render
7. `get_debate_summary` — returns JSON with `summary` and `reviewer` fields

These tests should use `@InjectMock` or direct tool method calls against the Quarkus test instance, following the pattern in the existing `DebateSessionLifecycleTest`.

- [ ] **Step 2: Run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: integration tests for reviewer resolution in debate MCP tools

Covers start_debate with/without agentId, list_reviewers, get_reviewer_instructions
with/without session context, get_debate_summary JSON format.

Refs #62"
```

---

### Task 10: Final verification and CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md` (update Architecture section and Key Directories)

- [ ] **Step 1: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: ALL PASS

- [ ] **Step 2: Update CLAUDE.md**

Add to the Architecture section:
- `list_reviewers` MCP tool
- `get_reviewer_instructions` MCP tool

Add to Key Directories:
- `ReviewerDescriptorSeeder` — registers 4 reviewer AgentDescriptors at startup
- `DraftHouseReviewerRegistry` — @DefaultBean in-memory AgentRegistry
- `SimplePromptRenderer` — @DefaultBean mock SystemPromptRenderer

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with reviewer MCP tools and registry classes

Refs #62"
```
