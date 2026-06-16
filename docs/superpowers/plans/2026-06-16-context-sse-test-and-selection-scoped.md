# Context-Usage SSE Test & Selection-Scoped Conversations — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add integration tests for the context-usage SSE delivery path (#56), then implement selection-scoped conversations that wire the diff panel's selection-changed event to the debate channel (#54).

**Architecture:** SelectionScope becomes the unified selection type for both review and debate paths. The browser diff panel extracts selected text and POSTs it to a new REST endpoint on DebateEventResource. The live SSE tick is refactored from conditional branches to a collect-then-emit pattern. Selection context is appended to debate summaries in DebateMcpTools.

**Tech Stack:** Java 21, Quarkus 3.34.3, Qhorus, Playwright (E2E), AssertJ, Mockito, SmallRye Mutiny

**Build command:** `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

**Single test:** `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=<TestClass>`

---

## Task 1: Context-usage SSE integration tests (#56)

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java`

- [ ] **Step 1: Write the failing test — `initialContextSnapshot_emittedOnConnect`**

Add to `DebateEventResourceTest.java`:

```java
@Test
void initialContextSnapshot_emittedOnConnect() throws Exception {
    String startResult = tools.startDebate("test-spec.md");
    String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
    activeDebateSessionId = sessionId;

    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<String> received =
            new java.util.concurrent.atomic.AtomicReference<>();

    String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

    Thread sseThread = Thread.ofVirtual().start(() -> {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.connect();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String line;
                StringBuilder accumulated = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (data.contains("\"type\":\"heartbeat\"")) continue;
                        if (data.contains("\"type\":\"context-usage\"")) {
                            received.set(data);
                            latch.countDown();
                            return;
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            // expected when connection closes
        }
    });

    assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("Initial context-usage event was not received within 5 seconds")
            .isTrue();

    String body = received.get();
    assertThat(body).contains("\"type\":\"context-usage\"");
    assertThat(body).contains("\"windowSizeChars\":");
    assertThat(body).contains("\"serverContributionChars\":");
    assertThat(body).contains("\"messageCount\":");
    assertThat(body).contains("\"effectivePercent\":");
    assertThat(body).contains("\"thresholdExceeded\":");

    sseThread.interrupt();
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest#initialContextSnapshot_emittedOnConnect`

This test should pass immediately — the initial context stream already emits context-usage events. If it fails, investigate the SSE data framing — Quarkus SSE may wrap data differently.

- [ ] **Step 3: Write the test — `pushedContextSnapshot_deliveredViaSse`**

Add to `DebateEventResourceTest.java`. Also add `@Inject MessageService messageService;` and `@Inject DebateSessionRegistry registry;` fields if not already present:

```java
@Inject MessageService messageService;
@Inject DebateSessionRegistry registry;

@Test
void pushedContextSnapshot_deliveredViaSse() throws Exception {
    String startResult = tools.startDebate("test-spec.md");
    String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
    activeDebateSessionId = sessionId;

    // Raise a point to create message activity — needs frontier exception wrapper
    io.casehub.drafthouse.e2e.DebateE2EFixtures.dispatchRaise(
            tools, messageService, sessionId, "REV", 1,
            "Test point.", "P1", "ISOLATED", null);

    // Track how many context-usage events we've seen
    java.util.concurrent.CountDownLatch initialLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch pushedLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<String> pushedEvent =
            new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger contextCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

    Thread sseThread = Thread.ofVirtual().start(() -> {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.connect();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.contains("\"type\":\"heartbeat\"")) continue;
                    if (data.contains("\"type\":\"context-usage\"")) {
                        int count = contextCount.incrementAndGet();
                        if (count == 1) {
                            // First context-usage is the initial snapshot — drain it
                            initialLatch.countDown();
                        } else {
                            // Second context-usage is the pushed snapshot
                            pushedEvent.set(data);
                            pushedLatch.countDown();
                            return;
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            // expected when connection closes
        }
    });

    // Wait for initial context to drain
    assertThat(initialLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("Initial context-usage event was not received")
            .isTrue();

    // Push a context snapshot via reportContext — called directly, no wrapper needed
    tools.reportContext(sessionId, 42.0);

    // Wait for the pushed snapshot to appear
    assertThat(pushedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("Pushed context-usage event was not received within 5 seconds")
            .isTrue();

    String body = pushedEvent.get();
    assertThat(body).contains("\"type\":\"context-usage\"");
    assertThat(body).contains("\"agentReportedPercent\":42.0");
    assertThat(body).contains("\"effectivePercent\":42.0");

    sseThread.interrupt();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest#pushedContextSnapshot_deliveredViaSse`

Expected: PASS — the push path already works, this test just verifies SSE delivery.

- [ ] **Step 5: Run the full `DebateEventResourceTest` suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest`

Expected: all tests pass (existing + 2 new).

- [ ] **Step 6: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java
git commit -m "test: context-usage SSE delivery integration tests

Verify the initial context-usage snapshot is emitted on SSE connect and
that pushed context snapshots via reportContext() are delivered on the
next SSE tick.

Closes #56"
```

---

## Task 2: Create `SelectionScope` record and unify review path

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/SelectionScope.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/ReviewSessionRegistry.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewSessionRegistryImpl.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionRegistryTest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java`

- [ ] **Step 1: Write `SelectionScope` unit tests**

Create a test or add to an existing test. Since `SelectionScope` is a record with validation, test the compact constructor:

Add a new test class `server/runtime/src/test/java/io/casehub/drafthouse/SelectionScopeTest.java`:

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SelectionScopeTest {

    @Test
    void validConstruction() {
        var scope = new SelectionScope(DocumentSide.A, 5, 12, "selected text");
        assertThat(scope.side()).isEqualTo(DocumentSide.A);
        assertThat(scope.startLine()).isEqualTo(5);
        assertThat(scope.endLine()).isEqualTo(12);
        assertThat(scope.selectedText()).isEqualTo("selected text");
    }

    @Test
    void zeroLines_validForReviewPath() {
        var scope = new SelectionScope(DocumentSide.B, 0, 0, "text only");
        assertThat(scope.startLine()).isEqualTo(0);
        assertThat(scope.endLine()).isEqualTo(0);
    }

    @Test
    void nullSide_throws() {
        assertThatThrownBy(() -> new SelectionScope(null, 0, 0, "text"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("side");
    }

    @Test
    void nullSelectedText_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSelectedText_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, 0, 0, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeStartLine_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, -1, 0, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endLineBeforeStartLine_throws() {
        assertThatThrownBy(() -> new SelectionScope(DocumentSide.A, 5, 3, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=SelectionScopeTest`

Expected: FAIL — `SelectionScope` class does not exist.

- [ ] **Step 3: Create `SelectionScope.java`**

Create `server/api/src/main/java/io/casehub/drafthouse/SelectionScope.java`:

```java
package io.casehub.drafthouse;

import java.util.Objects;

public record SelectionScope(DocumentSide side, int startLine, int endLine, String selectedText) {
    public SelectionScope {
        Objects.requireNonNull(side, "side");
        if (selectedText == null || selectedText.isBlank()) {
            throw new IllegalArgumentException("selectedText must be non-null and non-blank");
        }
        if (startLine < 0) throw new IllegalArgumentException("startLine must be >= 0");
        if (endLine < startLine) throw new IllegalArgumentException("endLine must be >= startLine");
    }
}
```

- [ ] **Step 4: Run `SelectionScopeTest` to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=SelectionScopeTest`

Expected: PASS

- [ ] **Step 5: Migrate `ReviewSession` to use `SelectionScope`**

Replace the two selection fields with one nullable `SelectionScope`:

In `server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java`, replace the entire record:

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
        String personality
) {
    public ReviewSession withSelection(final SelectionScope selection) {
        return new ReviewSession(
                channelId, sessionId, channelName, instanceId,
                docAContent, docBContent, selection, personality
        );
    }
}
```

- [ ] **Step 6: Update `ReviewSessionRegistry` interface**

In `server/api/src/main/java/io/casehub/drafthouse/ReviewSessionRegistry.java`, change `updateSelection`:

```java
void updateSelection(UUID channelId, SelectionScope selection);
```

Remove the `@param side` and `@param text` Javadoc lines. The full method doc becomes:

```java
/**
 * Atomically replaces the ReviewSession with an updated selection state.
 * No-op if no session exists for the given channelId.
 *
 * @param selection the new selection, or null to clear
 */
void updateSelection(UUID channelId, SelectionScope selection);
```

- [ ] **Step 7: Update `ReviewSessionRegistryImpl`**

In `server/runtime/src/main/java/io/casehub/drafthouse/ReviewSessionRegistryImpl.java`, change:

```java
@Override
public void updateSelection(final UUID channelId, final SelectionScope selection) {
    sessions.computeIfPresent(channelId, (id, s) -> s.withSelection(selection));
}
```

- [ ] **Step 8: Update `ReviewerChannelBackend.buildSelectionContext()`**

In `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java`, change the helper:

```java
private static String buildSelectionContext(ReviewSession session) {
    if (session.selection() == null) return "";
    return "Selected text (Document " + session.selection().side().name() + "): "
            + session.selection().selectedText();
}
```

- [ ] **Step 9: Update `DraftHouseMcpTools.updateSelection()`**

In `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`, change the method to build `SelectionScope` and pass it:

```java
@Tool(name = "update_selection",
      description = "Update the selected text in the review session. Pass null for side and selectedText to clear the selection.")
public String updateSelection(
        @ToolArg(description = "sessionId returned by start_review") String sessionId,
        @ToolArg(description = "Document side: 'A' or 'B'. Null to clear selection.") String side,
        @ToolArg(description = "Selected text. Null to clear selection.") String selectedText) {

    UUID channelId;
    try {
        channelId = UUID.fromString(sessionId);
    } catch (IllegalArgumentException e) {
        return "error: invalid sessionId format: " + sessionId;
    }

    if (registry.find(channelId).isEmpty()) {
        return "error: no active session for sessionId: " + sessionId;
    }

    if (side == null && selectedText == null) {
        registry.updateSelection(channelId, null);
        return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ok\"}";
    }

    DocumentSide docSide;
    try {
        docSide = DocumentSide.valueOf(side);
    } catch (IllegalArgumentException | NullPointerException e) {
        return "error: invalid side value '" + side + "' — must be 'A' or 'B'";
    }

    if (selectedText == null) {
        return "error: side and selectedText must both be provided or both be null";
    }

    registry.updateSelection(channelId, new SelectionScope(docSide, 0, 0, selectedText));
    return "{\"sessionId\":\"" + sessionId + "\",\"status\":\"ok\"}";
}
```

- [ ] **Step 10: Update `DraftHouseMcpTools.startReview()`**

In `startReview()`, the `ReviewSession` constructor call changes from 9 args to 8 (drop `selectionSide` and `selectionText`, replace with `null` for `selection`):

```java
ReviewSession session = new ReviewSession(
        channel.id, sessionId, resolvedChannelName, instanceId,
        docAContent, docBContent, null, config.reviewer().personality());
```

- [ ] **Step 11: Update `ReviewSessionRegistryTest`**

In `server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionRegistryTest.java`:

```java
@Test
void updateSelection_replacesSelectionFields() {
    final UUID channelId = UUID.randomUUID();
    registry.put(minimal(channelId));
    registry.updateSelection(channelId, new SelectionScope(DocumentSide.A, 0, 0, "selected text"));
    final ReviewSession updated = registry.find(channelId).orElseThrow();
    assertThat(updated.selection()).isNotNull();
    assertThat(updated.selection().side()).isEqualTo(DocumentSide.A);
    assertThat(updated.selection().selectedText()).isEqualTo("selected text");
}

private ReviewSession minimal(final UUID channelId) {
    return new ReviewSession(
            channelId, channelId.toString(), "drafthouse/test",
            "iid", "docA", "docB", null, "personality");
}
```

- [ ] **Step 12: Update `DraftHouseMcpToolsTest`**

Update the `minimalSession` helper and all affected tests:

```java
private ReviewSession minimalSession(UUID channelId) {
    return new ReviewSession(
            channelId, channelId.toString(), "drafthouse/test",
            "drafthouse-reviewer-" + channelId,
            "Doc A", "Doc B", null, "You are a reviewer.");
}
```

In `startReview_happyPath_returnsSessionIdAndCreatesSession`, replace:
```java
assertThat(session.selectionSide()).isNull();
assertThat(session.selectionText()).isNull();
```
with:
```java
assertThat(session.selection()).isNull();
```

In `updateSelection_happyPath_updatesRegistry`, replace:
```java
verify(registry).updateSelection(channelId, DocumentSide.A, "selected text");
```
with:
```java
verify(registry).updateSelection(eq(channelId), argThat(sel ->
        sel != null && sel.side() == DocumentSide.A && sel.selectedText().equals("selected text")));
```

In `updateSelection_nullSideAndText_clearsSelection`, replace:
```java
verify(registry).updateSelection(channelId, null, null);
```
with:
```java
verify(registry).updateSelection(channelId, null);
```

In `updateSelection_invalidSide_returnsError_noRegistryUpdate`, replace:
```java
verify(registry, never()).updateSelection(any(), any(), any());
```
with:
```java
verify(registry, never()).updateSelection(any(), any());
```

Apply the same `updateSelection` verify signature change to `updateSelection_halfNullInput_returnsError`.

- [ ] **Step 13: Run all tests to verify the migration is clean**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: all tests pass.

- [ ] **Step 14: Commit**

```
git add server/api/src/main/java/io/casehub/drafthouse/SelectionScope.java \
  server/api/src/main/java/io/casehub/drafthouse/ReviewSession.java \
  server/api/src/main/java/io/casehub/drafthouse/ReviewSessionRegistry.java \
  server/runtime/src/main/java/io/casehub/drafthouse/ReviewSessionRegistryImpl.java \
  server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackend.java \
  server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java \
  server/runtime/src/test/java/io/casehub/drafthouse/SelectionScopeTest.java \
  server/runtime/src/test/java/io/casehub/drafthouse/ReviewSessionRegistryTest.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java
git commit -m "refactor: unify selection model on SelectionScope record

Replace ReviewSession's separate selectionSide + selectionText fields
with a single nullable SelectionScope record. Migrate all callers:
ReviewSessionRegistry, ReviewerChannelBackend, DraftHouseMcpTools,
and their tests.

Refs #54"
```

---

## Task 3: Add selection to `DebateSession` and REST endpoints

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java`

- [ ] **Step 1: Write failing integration tests for POST/DELETE selection**

Add to `DebateEventResourceTest.java`:

```java
@Inject DebateSessionRegistry debateRegistry;

@Test
void selectionPost_storesSelection() {
    String startResult = tools.startDebate("test-spec.md");
    String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
    activeDebateSessionId = sessionId;

    RestAssured.given()
            .contentType("application/json")
            .body("{\"side\":\"A\",\"startLine\":5,\"endLine\":12,\"selectedText\":\"The passage\"}")
            .when()
            .post("/api/debate/" + sessionId + "/selection")
            .then()
            .statusCode(200);

    DebateSession session = debateRegistry.find(java.util.UUID.fromString(sessionId)).orElseThrow();
    assertThat(session.currentSelection()).isNotNull();
    assertThat(session.currentSelection().side()).isEqualTo(DocumentSide.A);
    assertThat(session.currentSelection().startLine()).isEqualTo(5);
    assertThat(session.currentSelection().endLine()).isEqualTo(12);
    assertThat(session.currentSelection().selectedText()).isEqualTo("The passage");
}

@Test
void selectionDelete_clearsSelection() {
    String startResult = tools.startDebate("test-spec.md");
    String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
    activeDebateSessionId = sessionId;

    // Set selection first
    RestAssured.given()
            .contentType("application/json")
            .body("{\"side\":\"B\",\"startLine\":1,\"endLine\":3,\"selectedText\":\"Some text\"}")
            .post("/api/debate/" + sessionId + "/selection")
            .then().statusCode(200);

    // Clear it
    RestAssured.given()
            .when()
            .delete("/api/debate/" + sessionId + "/selection")
            .then()
            .statusCode(200);

    DebateSession session = debateRegistry.find(java.util.UUID.fromString(sessionId)).orElseThrow();
    assertThat(session.currentSelection()).isNull();
}

@Test
void selectionPost_invalidSession_returns404() {
    RestAssured.given()
            .contentType("application/json")
            .body("{\"side\":\"A\",\"startLine\":0,\"endLine\":0,\"selectedText\":\"text\"}")
            .when()
            .post("/api/debate/00000000-0000-0000-0000-000000000000/selection")
            .then()
            .statusCode(404);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest#selectionPost_storesSelection`

Expected: FAIL — no endpoint, no `currentSelection` field.

- [ ] **Step 3: Add `currentSelection` to `DebateSession`**

In `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`, add after the `contextTracker` field:

```java
private volatile SelectionScope currentSelection;

public void updateSelection(SelectionScope selection) {
    this.currentSelection = selection;
}

public SelectionScope currentSelection() {
    return currentSelection;
}
```

- [ ] **Step 4: Add POST/DELETE endpoints to `DebateEventResource`**

In `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`, add a pending selections map alongside the existing `pendingContextSnapshots`:

```java
private final ConcurrentHashMap<UUID, String> pendingSelections = new ConcurrentHashMap<>();
```

Add a DTO record for JSON deserialization (inside the class):

```java
record SelectionRequest(String side, int startLine, int endLine, String selectedText) {}
```

Add the POST endpoint:

```java
@POST
@Path("/{debateSessionId}/selection")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public jakarta.ws.rs.core.Response postSelection(
        @PathParam("debateSessionId") String debateSessionId,
        SelectionRequest request) {
    UUID channelId;
    try {
        channelId = UUID.fromString(debateSessionId);
    } catch (IllegalArgumentException e) {
        throw new NotFoundException("Invalid session id: " + debateSessionId);
    }

    DebateSession session = registry.find(channelId).orElse(null);
    if (session == null) {
        throw new NotFoundException("No active debate session: " + debateSessionId);
    }

    DocumentSide side;
    try {
        side = DocumentSide.valueOf(request.side());
    } catch (IllegalArgumentException | NullPointerException e) {
        return jakarta.ws.rs.core.Response.status(400)
                .entity("{\"error\":\"invalid side: " + request.side() + "\"}").build();
    }

    SelectionScope scope = new SelectionScope(side, request.startLine(), request.endLine(),
            request.selectedText());
    session.updateSelection(scope);
    pushSelectionEvent(channelId, scope);
    return jakarta.ws.rs.core.Response.ok("{\"status\":\"ok\"}").build();
}

@DELETE
@Path("/{debateSessionId}/selection")
@Produces(MediaType.APPLICATION_JSON)
public jakarta.ws.rs.core.Response deleteSelection(
        @PathParam("debateSessionId") String debateSessionId) {
    UUID channelId;
    try {
        channelId = UUID.fromString(debateSessionId);
    } catch (IllegalArgumentException e) {
        throw new NotFoundException("Invalid session id: " + debateSessionId);
    }

    DebateSession session = registry.find(channelId).orElse(null);
    if (session == null) {
        throw new NotFoundException("No active debate session: " + debateSessionId);
    }

    session.updateSelection(null);
    pendingSelections.put(channelId, "{\"type\":\"selection-scope\",\"cleared\":true}");
    return jakarta.ws.rs.core.Response.ok("{\"status\":\"ok\"}").build();
}

private void pushSelectionEvent(UUID channelId, SelectionScope scope) {
    try {
        String json = "{\"type\":\"selection-scope\""
                + ",\"side\":\"" + scope.side().name() + "\""
                + ",\"startLine\":" + scope.startLine()
                + ",\"endLine\":" + scope.endLine()
                + ",\"selectedText\":\"" + escapeJson(scope.selectedText()) + "\""
                + "}";
        pendingSelections.put(channelId, json);
    } catch (Exception e) {
        LOG.warning("Failed to build selection event JSON: " + e.getMessage());
    }
}

private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest#selectionPost_storesSelection+selectionDelete_clearsSelection+selectionPost_invalidSession_returns404`

Expected: PASS

- [ ] **Step 6: Commit**

```
git add server/api/src/main/java/io/casehub/drafthouse/DebateSession.java \
  server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java
git commit -m "feat: POST/DELETE selection endpoints on DebateEventResource

Store SelectionScope on DebateSession via REST. Pending selection
events queued for SSE delivery (live tick refactoring in next commit).

Refs #54"
```

---

## Task 4: Refactor live tick to collect-then-emit and deliver selection via SSE

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java`

- [ ] **Step 1: Write the failing SSE selection delivery test**

Add to `DebateEventResourceTest.java`:

```java
@Test
void selectionScope_deliveredViaSse() throws Exception {
    String startResult = tools.startDebate("test-spec.md");
    String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
    activeDebateSessionId = sessionId;

    java.util.concurrent.CountDownLatch initialCtxLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch selectionLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<String> selectionEvent =
            new java.util.concurrent.atomic.AtomicReference<>();

    String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

    Thread sseThread = Thread.ofVirtual().start(() -> {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.connect();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.contains("\"type\":\"heartbeat\"")) continue;
                    if (data.contains("\"type\":\"context-usage\"")) {
                        initialCtxLatch.countDown();
                        continue;
                    }
                    if (data.contains("\"type\":\"selection-scope\"")) {
                        selectionEvent.set(data);
                        selectionLatch.countDown();
                        return;
                    }
                }
            }
        } catch (java.io.IOException e) {
            // expected when connection closes
        }
    });

    // Wait for initial context event to drain
    assertThat(initialCtxLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("Initial context-usage was not received").isTrue();

    // POST a selection
    RestAssured.given()
            .contentType("application/json")
            .body("{\"side\":\"A\",\"startLine\":5,\"endLine\":12,\"selectedText\":\"Hello world\"}")
            .post("/api/debate/" + sessionId + "/selection")
            .then().statusCode(200);

    // Wait for selection-scope SSE event
    assertThat(selectionLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .as("selection-scope event was not received via SSE").isTrue();

    String body = selectionEvent.get();
    assertThat(body).contains("\"type\":\"selection-scope\"");
    assertThat(body).contains("\"side\":\"A\"");
    assertThat(body).contains("\"startLine\":5");
    assertThat(body).contains("\"endLine\":12");
    assertThat(body).contains("Hello world");

    sseThread.interrupt();
}
```

- [ ] **Step 2: Run test to verify it fails (or passes if Task 3 already wired pending delivery)**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest#selectionScope_deliveredViaSse`

The test may already pass if the live tick drains `pendingSelections`. If it fails, the live tick needs refactoring.

- [ ] **Step 3: Refactor the live tick to collect-then-emit**

In `DebateEventResource.java`, replace the `live` Multi block (the `Multi.createFrom().ticks()...` section) with:

```java
Multi<String> live = Multi.createFrom().ticks().every(Duration.ofMillis(500))
        .onItem().transformToMultiAndConcatenate(tick -> {
            List<String> items = new java.util.ArrayList<>();

            String pendingCtx = pendingContextSnapshots.remove(channelId);
            if (pendingCtx != null) items.add(pendingCtx);

            String pendingSel = pendingSelections.remove(channelId);
            if (pendingSel != null) items.add(pendingSel);

            try {
                List<Message> messages = messageService.pollAfter(
                        channelId, lastSentId.get(), 50);
                String entries = messages.isEmpty() ? null
                        : serializeMessages(messages, lastSentId);
                if (entries != null) items.add(entries);
            } catch (Exception e) {
                LOG.warning("SSE tick failed for " + debateSessionId + ": " + e.getMessage());
            }

            if (items.isEmpty()) items.add("{\"type\":\"heartbeat\"}");
            return Multi.createFrom().iterable(items);
        });
```

- [ ] **Step 4: Run full test suite to verify no regressions**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest`

Expected: all tests pass including the new SSE selection test.

- [ ] **Step 5: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java
git commit -m "refactor: live tick collect-then-emit + selection SSE delivery

Replace 4-branch conditional in the SSE live tick with a list-based
drain pattern. Pending context snapshots and selection events are
collected into a list, then emitted as a stream. New metadata types
no longer require touching the emission logic.

Refs #54"
```

---

## Task 5: Selection context in debate summary

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Write failing unit tests for selection in debate summary**

Add to `DebateMcpToolsTest.java`:

```java
@Test
void getDebateSummary_includesSelectionContext() {
    UUID channelId = stubChannel.id;
    DebateSession session = sessionFor(channelId);
    session.updateSelection(new SelectionScope(DocumentSide.A, 5, 12, "The selected passage."));
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    ReviewState emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
    ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
    when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
    when(debateProjection.render(result)).thenReturn("# Summary");

    String summary = tools.getDebateSummary(channelId.toString());

    assertThat(summary).contains("## Active Selection");
    assertThat(summary).contains("**Document A**, lines 5–12:");
    assertThat(summary).contains("The selected passage.");
}

@Test
void getDebateSummary_noSelection_noSelectionSection() {
    UUID channelId = stubChannel.id;
    DebateSession session = sessionFor(channelId);
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    ReviewState emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
    ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
    when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
    when(debateProjection.render(result)).thenReturn("# Summary");

    String summary = tools.getDebateSummary(channelId.toString());

    assertThat(summary).doesNotContain("Active Selection");
}

@Test
void getDebateSummary_selectionWithZeroLines_omitsLineNumbers() {
    UUID channelId = stubChannel.id;
    DebateSession session = sessionFor(channelId);
    session.updateSelection(new SelectionScope(DocumentSide.B, 0, 0, "Review text only."));
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    ReviewState emptyState = new ReviewState(Map.of(), List.of(), List.of(), Map.of());
    ProjectionResult<ReviewState> result = new ProjectionResult<>(emptyState, null);
    when(projectionService.project(eq(channelId), eq(debateProjection))).thenReturn(result);
    when(debateProjection.render(result)).thenReturn("# Summary");

    String summary = tools.getDebateSummary(channelId.toString());

    assertThat(summary).contains("## Active Selection");
    assertThat(summary).contains("**Document B**:");
    assertThat(summary).doesNotContain("lines 0");
    assertThat(summary).contains("Review text only.");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest#getDebateSummary_includesSelectionContext`

Expected: FAIL — `getDebateSummary` does not append selection context.

- [ ] **Step 3: Implement selection context append in `getDebateSummary()`**

In `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`, change `getDebateSummary()`:

```java
public String getDebateSummary(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {

    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    var result = projectionService.project(session.channelId(), debateProjection);
    String summary = debateProjection.render(result);

    SelectionScope sel = session.currentSelection();
    if (sel != null) {
        StringBuilder sb = new StringBuilder(summary);
        sb.append("\n\n## Active Selection\n");
        sb.append("**Document ").append(sel.side().name()).append("**");
        if (sel.startLine() > 0) {
            sb.append(", lines ").append(sel.startLine()).append("–").append(sel.endLine());
        }
        sb.append(":\n> ").append(sel.selectedText()).append("\n");
        return sb.toString();
    }
    return summary;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest#getDebateSummary_includesSelectionContext+getDebateSummary_noSelection_noSelectionSection+getDebateSummary_selectionWithZeroLines_omitsLineNumbers`

Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: all tests pass.

- [ ] **Step 6: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git commit -m "feat: append active selection context to debate summary

getDebateSummary() appends a selection section when the debate session
has a currentSelection. Line numbers are included for the debate path
(startLine > 0) and omitted for the review path (startLine == 0).
getDebateSummaryAtRound() correctly excludes live selection.

Refs #54"
```

---

## Task 6: Browser wiring — diff panel event enrichment + shell listener

**Files:**
- Modify: `panels/drafthouse-diff.js`
- Modify: `index.html`

- [ ] **Step 1: Enrich the diff panel's `selection-changed` event with `selectedText`**

In `panels/drafthouse-diff.js`, replace lines 334-337 (the `CustomEvent` dispatch):

```javascript
        this.dispatchEvent(new CustomEvent('selection-changed', {
          bubbles: true,
          detail: { side: side.toUpperCase(), startLine, endLine, selectedText: sel.toString() },
        }));
```

- [ ] **Step 2: Add the shell `selection-changed` listener to `index.html`**

In `index.html`, add after the existing `point-selected` listener (after line 173):

```javascript
diffPanel.addEventListener('selection-changed', async (e) => {
  if (!debateEventBus.sessionId) return;
  const { side, startLine, endLine, selectedText } = e.detail;
  if (!selectedText) return;
  await fetch(`/api/debate/${debateEventBus.sessionId}/selection`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ side, startLine, endLine, selectedText })
  });
});
```

- [ ] **Step 3: Commit**

```
git add panels/drafthouse-diff.js index.html
git commit -m "feat: wire selection-changed event to debate selection endpoint

Diff panel extracts selectedText at mouseup and emits it in the
selection-changed event detail with uppercase side ('A'/'B').
Shell listens and POSTs to /api/debate/{id}/selection when a debate
session is active. Selection is sticky — persists until the user
selects different text or the session ends.

Refs #54"
```

---

## Task 7: E2E test — browser selection posts to debate session

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/CrossPanelE2ETest.java`

- [ ] **Step 1: Write the E2E test**

Add to `CrossPanelE2ETest.java`. Inject `DebateSessionRegistry` if not already present:

```java
@Inject DebateSessionRegistry debateRegistry;

@Test
void selectionInDiff_postsToDebateSession() {
    String sessionId = DebateE2EFixtures.startDebateSession(tools);
    activeDebateSessionId = sessionId;
    DebateE2EFixtures.loadWithDebate(page, index, sessionId);

    // Select text in the A-side render panel
    page.evaluate("() => {"
            + "const diff = document.querySelector('drafthouse-diff');"
            + "const renderA = diff.shadowRoot.querySelector('#render-a');"
            + "if (!renderA || !renderA.firstChild) return;"
            + "const range = document.createRange();"
            + "range.selectNodeContents(renderA.firstChild);"
            + "const sel = diff.shadowRoot.getSelection"
            + "  ? diff.shadowRoot.getSelection()"
            + "  : window.getSelection();"
            + "sel.removeAllRanges();"
            + "sel.addRange(range);"
            + "renderA.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));"
            + "}");

    // Wait for the POST to reach the server
    page.waitForTimeout(1500);

    DebateSession session = debateRegistry.find(java.util.UUID.fromString(sessionId)).orElseThrow();
    assertThat(session.currentSelection()).isNotNull();
    assertThat(session.currentSelection().side()).isEqualTo(DocumentSide.A);
    assertThat(session.currentSelection().selectedText()).isNotBlank();
}
```

- [ ] **Step 2: Run the E2E test**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=CrossPanelE2ETest#selectionInDiff_postsToDebateSession`

Expected: PASS — the full browser → shell → REST → DebateSession pipeline is exercised.

If the test fails due to Shadow DOM selection API differences, adjust the `page.evaluate` to use `window.getSelection()` instead.

- [ ] **Step 3: Run the full E2E suite to check for regressions**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=CrossPanelE2ETest`

Expected: all tests pass.

- [ ] **Step 4: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/CrossPanelE2ETest.java
git commit -m "test: E2E — browser selection posts to debate session

Programmatically select text in the diff panel via Playwright, verify
the selection POST reaches the server and is stored on the DebateSession.

Closes #54"
```

---

## Task 8: Final full test run

- [ ] **Step 1: Run the complete test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: all tests pass — no regressions.

- [ ] **Step 2: Push the branch**

```
git push -u origin issue-56-context-usage-sse-test-and-selection-scoped
```
