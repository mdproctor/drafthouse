# Encapsulate Document Operations + Debate Session Persistence — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encapsulate document operations inside `DebateSession` (#68) and add pluggable persistence for debate sessions across server restarts (#64).

**Architecture:** `DebateSession` absorbs all compound document operations, hiding `DocumentSet` as a package-private implementation detail. `DocumentEntry` and `ComparisonPair` become top-level domain records. A `DebateSessionStore` SPI with snapshot-based serialization provides pluggable persistence, gated by `@IfBuildProperty`. The registry becomes a write-through cache backed by the store.

**Tech Stack:** Java 21, Quarkus 3.34.3, JPA/Hibernate, Flyway, H2 (test) / PostgreSQL (prod), AssertJ, Mockito

**Spec:** `docs/superpowers/specs/2026-06-18-encapsulate-and-persist-debate-sessions-design.md`

---

## Part 1 — Encapsulate Document Operations (#68)

### Task 1: Extract DocumentEntry and ComparisonPair as top-level records

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/DocumentEntry.java`
- Create: `server/api/src/main/java/io/casehub/drafthouse/ComparisonPair.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DocumentSet.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DocumentSetTest.java`

- [ ] **Step 1: Create top-level DocumentEntry record**

```java
// server/api/src/main/java/io/casehub/drafthouse/DocumentEntry.java
package io.casehub.drafthouse;

import java.util.Objects;

public record DocumentEntry(String path, String label) {
    public DocumentEntry {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
        Objects.requireNonNull(label, "label");
    }
}
```

- [ ] **Step 2: Create top-level ComparisonPair record**

```java
// server/api/src/main/java/io/casehub/drafthouse/ComparisonPair.java
package io.casehub.drafthouse;

public record ComparisonPair(String pathA, String pathB) {}
```

- [ ] **Step 3: Update DocumentSet to use top-level records**

Remove the nested `DocumentEntry` and `ComparisonPair` records from `DocumentSet`. Replace all internal references with the new top-level types. Change `DocumentSet` class visibility to package-private.

In `DocumentSet.java`:
- Remove `public record DocumentEntry(...)` inner record
- Remove `public record ComparisonPair(...)` inner record
- Change `public class DocumentSet` → `class DocumentSet`
- Change `private final ArrayList<DocumentEntry>` → use the top-level `DocumentEntry`
- Change `private ComparisonPair currentComparison` → use the top-level `ComparisonPair`
- All method signatures already return the right names; they just resolve to the top-level records now

- [ ] **Step 4: Update DocumentSetTest to use top-level records**

Replace all `DocumentSet.DocumentEntry` references with `DocumentEntry` and `DocumentSet.ComparisonPair` with `ComparisonPair`. The test class is in the same package so it can still access the package-private `DocumentSet`.

- [ ] **Step 5: Build to verify compilation**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: Run api/ tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api`
Expected: All DocumentSetTest tests pass

- [ ] **Step 7: Commit**

```
feat: extract DocumentEntry and ComparisonPair as top-level records

DocumentSet becomes package-private. DocumentEntry and ComparisonPair
are domain vocabulary and must be publicly accessible since
DebateSession returns them from its public API.

Refs #68
```

---

### Task 2: Add document domain methods to DebateSession

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java`

- [ ] **Step 1: Write failing tests for addDocument()**

In `DebateSessionTest.java`, add:

```java
// ── addDocument() ─────────────────────────────────────────────────────
@Test
void addDocument_newPath_returnsTrue() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    assertThat(session.addDocument("/a.md", "spec")).isTrue();
    assertThat(session.documents()).hasSize(1);
    assertThat(session.documents().get(0).path()).isEqualTo("/a.md");
}

@Test
void addDocument_duplicatePath_returnsFalse() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    assertThat(session.addDocument("/a.md", "other")).isFalse();
    assertThat(session.documents()).hasSize(1);
}
```

- [ ] **Step 2: Write failing tests for removeDocument()**

```java
// ── removeDocument() ──────────────────────────────────────────────────
@Test
void removeDocument_existingNonPrimary_returnsComparisonCleared() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    session.addDocument("/b.md", "impl");
    session.setComparison("/a.md", "/b.md");

    boolean comparisonCleared = session.removeDocument("/b.md");
    assertThat(comparisonCleared).isTrue();
    assertThat(session.documents()).hasSize(1);
    assertThat(session.currentComparison()).isNull();
}

@Test
void removeDocument_noComparisonAffected_returnsFalse() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    session.addDocument("/b.md", "impl");
    session.addDocument("/c.md", "test");
    session.setComparison("/a.md", "/b.md");

    boolean comparisonCleared = session.removeDocument("/c.md");
    assertThat(comparisonCleared).isFalse();
    assertThat(session.currentComparison()).isNotNull();
}

@Test
void removeDocument_primary_throwsIllegalArgument() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    session.addDocument("/b.md", "impl");

    assertThatThrownBy(() -> session.removeDocument("/a.md"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("primary");
}

@Test
void removeDocument_notFound_throwsIllegalArgument() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");

    assertThatThrownBy(() -> session.removeDocument("/no-such.md"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not in document set");
}
```

- [ ] **Step 3: Write failing tests for setComparison() and accessors**

```java
// ── setComparison() ───────────────────────────────────────────────────
@Test
void setComparison_validPaths_sets() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    session.addDocument("/b.md", "impl");
    session.setComparison("/a.md", "/b.md");
    assertThat(session.currentComparison().pathA()).isEqualTo("/a.md");
    assertThat(session.currentComparison().pathB()).isEqualTo("/b.md");
}

@Test
void setComparison_pathNotInSet_throwsIllegalArgument() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");

    assertThatThrownBy(() -> session.setComparison("/a.md", "/missing.md"))
            .isInstanceOf(IllegalArgumentException.class);
}

// ── documents() and primary() ─────────────────────────────────────────
@Test
void documents_returnsDefensiveCopy() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    assertThatThrownBy(() -> session.documents().add(new DocumentEntry("/b.md", "x")))
            .isInstanceOf(UnsupportedOperationException.class);
}

@Test
void primary_returnsFirstDocument() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    session.addDocument("/b.md", "impl");
    assertThat(session.primary()).isPresent();
    assertThat(session.primary().get().path()).isEqualTo("/a.md");
}

@Test
void primary_empty_returnsEmpty() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    assertThat(session.primary()).isEmpty();
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionTest`
Expected: FAIL — methods don't exist yet

- [ ] **Step 5: Implement domain methods on DebateSession**

Add to `DebateSession.java`:

```java
public boolean addDocument(String path, String label) {
    return documentSet.add(path, label);
}

public boolean removeDocument(String path) {
    synchronized (documentSet) {
        if (documentSet.primary().isPresent()
                && documentSet.primary().get().path().equals(path)) {
            throw new IllegalArgumentException("cannot remove primary document: " + path);
        }
        ComparisonPair before = documentSet.currentComparison();
        boolean removed = documentSet.remove(path);
        if (!removed) {
            throw new IllegalArgumentException("path not in document set: " + path);
        }
        ComparisonPair after = documentSet.currentComparison();
        return before != null && after == null;
    }
}

public void setComparison(String pathA, String pathB) {
    synchronized (documentSet) {
        boolean hasA = documentSet.documents().stream().anyMatch(d -> d.path().equals(pathA));
        boolean hasB = documentSet.documents().stream().anyMatch(d -> d.path().equals(pathB));
        if (!hasA) throw new IllegalArgumentException("pathA not in document set: " + pathA);
        if (!hasB) throw new IllegalArgumentException("pathB not in document set: " + pathB);
        documentSet.setComparison(pathA, pathB);
    }
}

public void clearComparison() {
    documentSet.clearComparison();
}

public ComparisonPair currentComparison() {
    return documentSet.currentComparison();
}

public List<DocumentEntry> documents() {
    return documentSet.documents();
}

public Optional<DocumentEntry> primary() {
    return documentSet.primary();
}
```

Add `import java.util.List;` and `import java.util.Optional;` if not already present.

- [ ] **Step 6: Remove `documentSet()` accessor, rename `specPath()` to `primaryPath()`**

In `DebateSession.java`:
- Remove: `public DocumentSet documentSet() { return documentSet; }`
- Rename: `specPath()` → `primaryPath()` with same body:

```java
public String primaryPath() {
    return documentSet.primary().map(DocumentEntry::path).orElse(null);
}
```

- [ ] **Step 7: Add `branchFrom()` static factory**

```java
public static DebateSession branchFrom(DebateSession source,
        UUID channelId, String sessionId, String channelName) {
    return new DebateSession(channelId, sessionId, channelName,
            DocumentSet.copyOf(source.documentSet));
}
```

- [ ] **Step 8: Write test for branchFrom()**

```java
@Test
void branchFrom_copiesDocumentsAndComparison() {
    DebateSession original = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    original.addDocument("/a.md", "spec");
    original.addDocument("/b.md", "impl");
    original.setComparison("/a.md", "/b.md");

    UUID newId = UUID.randomUUID();
    DebateSession branched = DebateSession.branchFrom(original,
            newId, newId.toString(), "new-channel");

    assertThat(branched.channelId()).isEqualTo(newId);
    assertThat(branched.documents()).hasSize(2);
    assertThat(branched.currentComparison().pathA()).isEqualTo("/a.md");

    // mutations on branch don't affect original
    branched.addDocument("/c.md", "test");
    assertThat(original.documents()).hasSize(2);
}
```

- [ ] **Step 9: Update existing DebateSessionTest helper and tests**

Update `sessionWithSpec()` helper to use `addDocument()` instead of `documentSet().add()`:

```java
private static DebateSession sessionWithSpec(String specPath) {
    var session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    if (specPath != null) session.addDocument(specPath, "spec");
    return session;
}
```

Update `specPath_derivedFromDocumentSetPrimary` test:
- Rename to `primaryPath_derivedFromFirstDocument`
- Change `session.specPath()` → `session.primaryPath()`

Update `specPath_nullWhenNoDocuments` test:
- Rename to `primaryPath_nullWhenNoDocuments`
- Change `session.specPath()` → `session.primaryPath()`

- [ ] **Step 10: Run all api/ tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api`
Expected: All tests pass

- [ ] **Step 11: Commit**

```
feat: encapsulate document operations in DebateSession

- addDocument(), removeDocument(), setComparison(), clearComparison(),
  currentComparison(), documents(), primary() delegate to DocumentSet
- removeDocument() throws for primary guard and not-found
- setComparison() validates paths exist in the set
- branchFrom() factory for session branching (restartFromRound)
- specPath() renamed to primaryPath()
- documentSet() accessor removed

Refs #68
```

---

### Task 3: Migrate DocumentSetJson to top-level types

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DocumentSetJson.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DocumentSetJsonTest.java`

- [ ] **Step 1: Update DocumentSetJson method signatures**

Change `DocumentSetJson` methods to accept the top-level types and `DebateSession` instead of `DocumentSet`:

```java
package io.casehub.drafthouse;

import java.util.List;

class DocumentSetJson {

    private DocumentSetJson() {}

    static String documentsToJson(List<DocumentEntry> docs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"path\":").append(escapeAndQuote(docs.get(i).path()))
              .append(",\"label\":").append(escapeAndQuote(docs.get(i).label())).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    static String comparisonToJson(ComparisonPair cp) {
        if (cp == null) return "null";
        return "{\"pathA\":" + escapeAndQuote(cp.pathA())
                + ",\"pathB\":" + escapeAndQuote(cp.pathB()) + "}";
    }

    static String documentsAndComparisonToJson(DebateSession session) {
        return "{\"documents\":" + documentsToJson(session.documents())
                + ",\"currentComparison\":" + comparisonToJson(session.currentComparison()) + "}";
    }

    private static String escapeAndQuote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
```

- [ ] **Step 2: Update DocumentSetJsonTest**

Replace `DocumentSet.ComparisonPair` with `ComparisonPair`. Replace `DocumentSet` construction + manual adds with `DebateSession` construction where `documentsAndComparisonToJson` is used. For `documentsToJson` tests, construct `List<DocumentEntry>` directly.

Key changes:
- `new DocumentSet.ComparisonPair(...)` → `new ComparisonPair(...)`
- `documentsToJson(set.documents())` → `documentsToJson(List.of(new DocumentEntry(...)))`
- `documentsAndComparisonToJson(set)` → create a `DebateSession`, add documents, set comparison, pass session

- [ ] **Step 3: Build and run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DocumentSetJsonTest`
Expected: All tests pass

- [ ] **Step 4: Commit**

```
refactor: DocumentSetJson accepts top-level types and DebateSession

documentsAndComparisonToJson() now takes DebateSession instead of
DocumentSet, since DocumentSet is no longer publicly accessible.

Refs #68
```

---

### Task 4: Migrate DebateMcpTools to new DebateSession API

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Migrate startDebate()**

Replace `session.documentSet().add(specPath, "spec")` with `session.addDocument(specPath, "spec")`.
Replace `session.specPath()` in the JSON return with `session.primaryPath()`.

- [ ] **Step 2: Migrate addDocument()**

Replace `session.documentSet().add(path, label)` with `session.addDocument(path, label)`.
Replace `session.documentSet().documents().size()` with `session.documents().size()`.

- [ ] **Step 3: Migrate removeDocument()**

Replace the multi-step compound operation with:

```java
@Tool(name = "remove_document", ...)
public String removeDocument(...) {
    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    try {
        boolean comparisonCleared = session.removeDocument(path);
        debateEventResource.pushDocumentsChanged(session.channelId(), session);
        if (comparisonCleared) {
            debateEventResource.pushComparisonChanged(session.channelId(), null);
        }
        int count = session.documents().size();
        return "{\"status\":\"removed\",\"documentCount\":" + count + "}";
    } catch (IllegalArgumentException e) {
        return "error: " + e.getMessage();
    }
}
```

- [ ] **Step 4: Migrate setComparison()**

Replace the manual path validation + `session.documentSet().setComparison()` with:

```java
@Tool(name = "set_comparison", ...)
public String setComparison(...) {
    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    try {
        session.setComparison(pathA, pathB);
        debateEventResource.pushComparisonChanged(session.channelId(), session.currentComparison());
        return "{\"status\":\"set\",\"pathA\":" + jsonString(pathA)
                + ",\"pathB\":" + jsonString(pathB) + "}";
    } catch (IllegalArgumentException e) {
        return "error: " + e.getMessage();
    }
}
```

- [ ] **Step 5: Migrate listDocuments()**

Replace `DocumentSetJson.documentsAndComparisonToJson(session.documentSet())` with `DocumentSetJson.documentsAndComparisonToJson(session)`.

- [ ] **Step 6: Migrate getDebateSummary() and exportDebateSummary()**

Replace `session.specPath()` → `session.primaryPath()`.

- [ ] **Step 7: Migrate appendWorkingSet()**

Replace `session.documentSet().documents()` → `session.documents()`.
Replace `session.documentSet().currentComparison()` → `session.currentComparison()`.

- [ ] **Step 8: Migrate restartFromRound()**

Replace `DocumentSet.copyOf(original.documentSet())` construction with `DebateSession.branchFrom(original, ...)`:

```java
newSession = DebateSession.branchFrom(original, newChannel.id, newSessionId, newChannel.name);
```

Replace `original.specPath()` → `original.primaryPath()`.
Replace `original.documentSet()` access in cleanup with `original.documents()` / `original.currentComparison()` as needed.

- [ ] **Step 9: Update pushDocumentsChanged() signature in DebateEventResource**

Change `pushDocumentsChanged(UUID channelId, DocumentSet documentSet)` to `pushDocumentsChanged(UUID channelId, DebateSession session)`:

```java
public void pushDocumentsChanged(UUID channelId, DebateSession session) {
    try {
        String json = "{\"type\":\"documents-changed\",\"documents\":"
                + DocumentSetJson.documentsToJson(session.documents()) + "}";
        pendingDocuments.put(channelId, json);
    } catch (Exception e) {
        LOG.warning("Failed to build documents-changed JSON: " + e.getMessage());
    }
}
```

Update the `addDocument` call in DebateMcpTools accordingly:
`debateEventResource.pushDocumentsChanged(session.channelId(), session);`

- [ ] **Step 10: Update DebateMcpToolsTest for API changes**

Update test methods that reference `session.documentSet()` to use `session.addDocument()` etc.
Update assertions that check `specPath` to check `primaryPath`.
Update mock setup for `pushDocumentsChanged` to accept `DebateSession` instead of `DocumentSet`.

- [ ] **Step 11: Build and run all runtime tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 12: Commit**

```
refactor: migrate DebateMcpTools and DebateEventResource to encapsulated API

All compound document operations now go through DebateSession domain
methods. DocumentSet is no longer accessed outside of DebateSession.

Refs #68
```

---

### Task 5: Migrate DebateEventResource caller sites

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`

- [ ] **Step 1: Migrate getDocuments()**

Replace `DocumentSetJson.documentsAndComparisonToJson(session.documentSet())` with `DocumentSetJson.documentsAndComparisonToJson(session)`.

- [ ] **Step 2: Migrate postComparison()**

Replace the manual path validation + `session.documentSet().setComparison()` with:

```java
try {
    session.setComparison(request.pathA(), request.pathB());
    pushComparisonChanged(channelId, session.currentComparison());
    return jakarta.ws.rs.core.Response.ok("{\"status\":\"ok\"}").build();
} catch (IllegalArgumentException e) {
    return jakarta.ws.rs.core.Response.status(400)
            .entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}").build();
}
```

- [ ] **Step 3: Migrate activeSessions()**

Replace `s.specPath()` → `s.primaryPath()`.

- [ ] **Step 4: Update DebateSessionRegistryTest**

Replace `session.documentSet().add(...)` with `session.addDocument(...)` in test setup.

- [ ] **Step 5: Build and run all tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 6: Commit**

```
refactor: migrate DebateEventResource and registry tests to encapsulated API

Closes #68
```

---

## Part 2 — Debate Session Persistence (#64)

### Task 6: Add DebateSessionSnapshot and snapshot/fromSnapshot

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/DebateSessionSnapshot.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java`

- [ ] **Step 1: Write failing tests for snapshot() and fromSnapshot()**

```java
// ── snapshot() / fromSnapshot() ───────────────────────────────────────
@Test
void snapshot_capturesAllDurableState() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    session.addDocument("/b.md", "impl");
    session.setComparison("/a.md", "/b.md");
    session.registerIfAbsent(AgentType.REV, () -> "rev-id");

    DebateSessionSnapshot snap = session.snapshot();
    assertThat(snap.channelId()).isEqualTo(CHANNEL_ID);
    assertThat(snap.debateSessionId()).isEqualTo(SESSION_ID);
    assertThat(snap.channelName()).isEqualTo(NAME);
    assertThat(snap.documents()).hasSize(2);
    assertThat(snap.documents().get(0).path()).isEqualTo("/a.md");
    assertThat(snap.comparison()).isNotNull();
    assertThat(snap.comparison().pathA()).isEqualTo("/a.md");
    assertThat(snap.participants()).containsEntry(AgentType.REV, "rev-id");
}

@Test
void fromSnapshot_reconstitutesLiveSession() {
    var snap = new DebateSessionSnapshot(
            CHANNEL_ID, SESSION_ID, NAME,
            List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
            new ComparisonPair("/a.md", "/b.md"),
            Map.of(AgentType.REV, "rev-id", AgentType.IMP, "imp-id"));

    DebateSession session = DebateSession.fromSnapshot(snap);
    assertThat(session.channelId()).isEqualTo(CHANNEL_ID);
    assertThat(session.documents()).hasSize(2);
    assertThat(session.currentComparison().pathA()).isEqualTo("/a.md");
    assertThat(session.instanceIdFor(AgentType.REV)).isEqualTo("rev-id");
    assertThat(session.instanceIdFor(AgentType.IMP)).isEqualTo("imp-id");
    assertThat(session.contextTracker()).isNotNull();
    assertThat(session.currentSelection()).isNull();
}

@Test
void snapshot_documentsAreImmutableCopy() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    session.addDocument("/a.md", "spec");
    DebateSessionSnapshot snap = session.snapshot();

    session.addDocument("/b.md", "impl");
    assertThat(snap.documents()).hasSize(1);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionTest`
Expected: FAIL — DebateSessionSnapshot, snapshot(), fromSnapshot() don't exist yet

- [ ] **Step 3: Create DebateSessionSnapshot record**

```java
// server/api/src/main/java/io/casehub/drafthouse/DebateSessionSnapshot.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DebateSessionSnapshot(
        UUID channelId,
        String debateSessionId,
        String channelName,
        List<DocumentEntry> documents,
        ComparisonPair comparison,
        Map<AgentType, String> participants) {}
```

- [ ] **Step 4: Implement snapshot() on DebateSession**

```java
public DebateSessionSnapshot snapshot() {
    List<DocumentEntry> docs;
    ComparisonPair comp;
    synchronized (documentSet) {
        docs = documentSet.documents();
        comp = documentSet.currentComparison();
    }
    return new DebateSessionSnapshot(
            channelId, debateSessionId, channelName,
            docs, comp, Map.copyOf(participants));
}
```

- [ ] **Step 5: Implement fromSnapshot() on DebateSession**

```java
public static DebateSession fromSnapshot(DebateSessionSnapshot snapshot) {
    DocumentSet ds = new DocumentSet();
    for (DocumentEntry e : snapshot.documents()) {
        ds.add(e.path(), e.label());
    }
    if (snapshot.comparison() != null) {
        ds.setComparison(snapshot.comparison().pathA(), snapshot.comparison().pathB());
    }
    DebateSession session = new DebateSession(
            snapshot.channelId(), snapshot.debateSessionId(),
            snapshot.channelName(), ds);
    for (var entry : snapshot.participants().entrySet()) {
        session.registerIfAbsent(entry.getKey(), entry::getValue);
    }
    return session;
}
```

- [ ] **Step 6: Run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionTest`
Expected: All tests pass

- [ ] **Step 7: Commit**

```
feat: add DebateSessionSnapshot with snapshot()/fromSnapshot()

Document state captured atomically under DocumentSet lock; participant
state read separately from ConcurrentHashMap. Snapshot is effectively
consistent because document and participant mutations happen in
different MCP tool methods.

Refs #64
```

---

### Task 7: Add DebateSessionStore SPI and NoOpDebateSessionStore

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/DebateSessionStore.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/NoOpDebateSessionStore.java`
- Create: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionStoreContractTest.java`

- [ ] **Step 1: Write SPI contract test**

```java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DebateSessionStoreContractTest {

    private final DebateSessionStore store = new InMemoryDebateSessionStore();

    private DebateSessionSnapshot testSnapshot() {
        UUID channelId = UUID.randomUUID();
        return new DebateSessionSnapshot(
                channelId, channelId.toString(), "drafthouse/debate/d-test",
                List.of(new DocumentEntry("/a.md", "spec")),
                new ComparisonPair("/a.md", "/b.md"),
                Map.of(AgentType.REV, "rev-id"));
    }

    @Test
    void save_and_load_roundTrips() {
        var snap = testSnapshot();
        store.save(snap);
        var loaded = store.load(snap.channelId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().channelId()).isEqualTo(snap.channelId());
        assertThat(loaded.get().documents()).hasSize(1);
        assertThat(loaded.get().comparison().pathA()).isEqualTo("/a.md");
        assertThat(loaded.get().participants()).containsEntry(AgentType.REV, "rev-id");
    }

    @Test
    void load_nonexistent_returnsEmpty() {
        assertThat(store.load(UUID.randomUUID())).isEmpty();
    }

    @Test
    void remove_existing_makesLoadReturnEmpty() {
        var snap = testSnapshot();
        store.save(snap);
        store.remove(snap.channelId());
        assertThat(store.load(snap.channelId())).isEmpty();
    }

    @Test
    void loadAll_returnsAllSaved() {
        var snap1 = testSnapshot();
        var snap2 = testSnapshot();
        store.save(snap1);
        store.save(snap2);
        assertThat(store.loadAll()).hasSize(2);
    }

    @Test
    void save_sameId_updatesExisting() {
        var snap = testSnapshot();
        store.save(snap);
        var updated = new DebateSessionSnapshot(
                snap.channelId(), snap.debateSessionId(), snap.channelName(),
                List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
                null, snap.participants());
        store.save(updated);
        var loaded = store.load(snap.channelId());
        assertThat(loaded.get().documents()).hasSize(2);
        assertThat(loaded.get().comparison()).isNull();
    }

    /** Trivial in-memory implementation for contract testing only. */
    static class InMemoryDebateSessionStore implements DebateSessionStore {
        private final java.util.concurrent.ConcurrentHashMap<UUID, DebateSessionSnapshot> map
                = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void save(DebateSessionSnapshot snapshot) {
            map.put(snapshot.channelId(), snapshot);
        }
        @Override public java.util.Optional<DebateSessionSnapshot> load(UUID channelId) {
            return java.util.Optional.ofNullable(map.get(channelId));
        }
        @Override public void remove(UUID channelId) { map.remove(channelId); }
        @Override public java.util.Collection<DebateSessionSnapshot> loadAll() {
            return List.copyOf(map.values());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionStoreContractTest`
Expected: FAIL — DebateSessionStore doesn't exist yet

- [ ] **Step 3: Create DebateSessionStore SPI**

```java
// server/api/src/main/java/io/casehub/drafthouse/DebateSessionStore.java
package io.casehub.drafthouse;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DebateSessionStore {
    void save(DebateSessionSnapshot snapshot);
    Optional<DebateSessionSnapshot> load(UUID channelId);
    void remove(UUID channelId);
    Collection<DebateSessionSnapshot> loadAll();
}
```

- [ ] **Step 4: Run contract test**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionStoreContractTest`
Expected: All tests pass

- [ ] **Step 5: Create NoOpDebateSessionStore**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/NoOpDebateSessionStore.java
package io.casehub.drafthouse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpDebateSessionStore implements DebateSessionStore {

    @Override
    public void save(DebateSessionSnapshot snapshot) {}

    @Override
    public Optional<DebateSessionSnapshot> load(UUID channelId) {
        return Optional.empty();
    }

    @Override
    public void remove(UUID channelId) {}

    @Override
    public Collection<DebateSessionSnapshot> loadAll() {
        return List.of();
    }
}
```

- [ ] **Step 6: Build**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```
feat: add DebateSessionStore SPI and NoOpDebateSessionStore

Pure-Java SPI in api/ with contract test. NoOp @DefaultBean in
runtime/ preserves current behavior — sessions are in-memory only.

Refs #64
```

---

### Task 8: Update DebateSessionRegistryImpl with write-through cache

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateSessionRegistryTest.java`

- [ ] **Step 1: Write failing tests for write-through behavior**

In `DebateSessionRegistryTest.java`:

```java
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
```

Replace the `setUp()` to use a mock store:

```java
private DebateSessionRegistryImpl registry;
private DebateSessionStore store;

@BeforeEach
void setUp() {
    store = mock(DebateSessionStore.class);
    when(store.loadAll()).thenReturn(List.of());
    registry = new DebateSessionRegistryImpl();
    registry.store = store;
    registry.init();
}
```

Add tests:

```java
@Test
void put_delegatesToStore() {
    UUID channelId = UUID.randomUUID();
    DebateSession session = new DebateSession(channelId, channelId.toString(),
            "drafthouse/debate/d-test");
    session.addDocument("test-spec.md", "spec");
    registry.put(session);

    var captor = ArgumentCaptor.forClass(DebateSessionSnapshot.class);
    verify(store).save(captor.capture());
    assertThat(captor.getValue().channelId()).isEqualTo(channelId);
}

@Test
void remove_delegatesToStore() {
    UUID channelId = UUID.randomUUID();
    DebateSession session = new DebateSession(channelId, channelId.toString(),
            "drafthouse/debate/d-test");
    session.addDocument("test-spec.md", "spec");
    registry.put(session);
    registry.remove(channelId);

    verify(store).remove(channelId);
}

@Test
void persist_savesSnapshotToStore() {
    UUID channelId = UUID.randomUUID();
    DebateSession session = new DebateSession(channelId, channelId.toString(),
            "drafthouse/debate/d-test");
    session.addDocument("test-spec.md", "spec");
    registry.put(session);
    reset(store);

    session.addDocument("/b.md", "impl");
    registry.persist(session);

    var captor = ArgumentCaptor.forClass(DebateSessionSnapshot.class);
    verify(store).save(captor.capture());
    assertThat(captor.getValue().documents()).hasSize(2);
}

@Test
void init_loadsFromStore() {
    UUID channelId = UUID.randomUUID();
    var snap = new DebateSessionSnapshot(
            channelId, channelId.toString(), "ch-name",
            List.of(new DocumentEntry("/a.md", "spec")),
            null, Map.of());
    when(store.loadAll()).thenReturn(List.of(snap));

    var freshRegistry = new DebateSessionRegistryImpl();
    freshRegistry.store = store;
    freshRegistry.init();

    assertThat(freshRegistry.find(channelId)).isPresent();
    assertThat(freshRegistry.find(channelId).get().documents()).hasSize(1);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateSessionRegistryTest`
Expected: FAIL — no store field, no persist() method, no init()

- [ ] **Step 3: Add persist() to DebateSessionRegistry interface**

```java
// Add to DebateSessionRegistry.java:
/** Persists the current session state to the store without re-registering in the cache. */
void persist(DebateSession session);
```

- [ ] **Step 4: Implement write-through in DebateSessionRegistryImpl**

```java
@ApplicationScoped
public class DebateSessionRegistryImpl implements DebateSessionRegistry {

    private final ConcurrentHashMap<UUID, DebateSession> sessions = new ConcurrentHashMap<>();

    @Inject
    DebateSessionStore store;

    @jakarta.annotation.PostConstruct
    void init() {
        for (DebateSessionSnapshot snap : store.loadAll()) {
            sessions.put(snap.channelId(), DebateSession.fromSnapshot(snap));
        }
    }

    @Override
    public Optional<DebateSession> find(final UUID channelId) {
        return Optional.ofNullable(sessions.get(channelId));
    }

    @Override
    public void put(final DebateSession session) {
        sessions.put(session.channelId(), session);
        store.save(session.snapshot());
    }

    @Override
    public void remove(final UUID channelId) {
        sessions.remove(channelId);
        store.remove(channelId);
    }

    @Override
    public void persist(final DebateSession session) {
        store.save(session.snapshot());
    }

    @Override
    public Collection<DebateSession> activeSessions() {
        return List.copyOf(sessions.values());
    }
}
```

- [ ] **Step 5: Run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateSessionRegistryTest`
Expected: All tests pass

- [ ] **Step 6: Commit**

```
feat: write-through cache in DebateSessionRegistryImpl

- @PostConstruct loads all snapshots from store on first access
- put() and remove() delegate to store
- persist() saves incremental mutations without re-registering
- NoOpDebateSessionStore preserves current in-memory-only behavior

Refs #64
```

---

### Task 9: Add persist() calls in MCP tools and event resource

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`

- [ ] **Step 1: Add persist() after document mutations in DebateMcpTools**

After each mutation that changes persisted state, add `registry.persist(session)`:

In `addDocument()`:
```java
boolean added = session.addDocument(path, label);
if (!added) { return "error: path already in document set: " + path; }
registry.persist(session);
debateEventResource.pushDocumentsChanged(session.channelId(), session);
```

In `removeDocument()`:
```java
boolean comparisonCleared = session.removeDocument(path);
registry.persist(session);
debateEventResource.pushDocumentsChanged(session.channelId(), session);
```

In `setComparison()`:
```java
session.setComparison(pathA, pathB);
registry.persist(session);
debateEventResource.pushComparisonChanged(session.channelId(), session.currentComparison());
```

- [ ] **Step 2: Add persist() after participant registration in sender()**

In the `sender()` helper, persist when a new participant is registered:

```java
private String sender(final DebateSession session, final AgentType role) {
    String existing = session.instanceIdFor(role);
    String instanceId = session.registerIfAbsent(role, () -> {
        final String id = DebateSession.instanceId(role, session.debateSessionId());
        instanceService.register(id,
                "DraftHouse " + role.name().toLowerCase() + " " + session.debateSessionId(),
                List.of("document-debate-" + role.name().toLowerCase()));
        return id;
    });
    if (existing == null) {
        registry.persist(session);
    }
    return instanceId;
}
```

- [ ] **Step 3: Add persist() in DebateEventResource.postComparison()**

After `session.setComparison(...)`:
```java
session.setComparison(request.pathA(), request.pathB());
registry.persist(session);
pushComparisonChanged(channelId, session.currentComparison());
```

This requires injecting `DebateSessionRegistry` into `DebateEventResource` (it already has it — `@Inject DebateSessionRegistry registry`).

- [ ] **Step 4: Update DebateMcpToolsTest**

Add verify calls for `registry.persist(session)` in relevant test methods (addDocument, removeDocument, setComparison tests). Verify that `persist()` is called with the session object.

- [ ] **Step 5: Run all tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 6: Commit**

```
feat: persist session state after document and participant mutations

MCP tools and DebateEventResource call registry.persist() after
addDocument, removeDocument, setComparison, and new participant
registration.

Refs #64
```

---

### Task 10: Add JPA entity, Flyway migration, and JpaDebateSessionStore

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionEntity.java`
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/JpaDebateSessionStore.java`
- Create: `server/runtime/src/main/resources/db/drafthouse/migration/V100__create_debate_session_tables.sql`
- Modify: `server/runtime/src/main/resources/application.properties`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/JpaDebateSessionStoreTest.java`

- [ ] **Step 1: Write the Flyway migration**

```sql
-- V100__create_debate_session_tables.sql
CREATE TABLE debate_session (
    channel_id UUID PRIMARY KEY,
    debate_session_id VARCHAR(255) NOT NULL,
    channel_name VARCHAR(255) NOT NULL,
    comparison_path_a VARCHAR(1024),
    comparison_path_b VARCHAR(1024)
);

CREATE TABLE debate_session_document (
    session_channel_id UUID NOT NULL,
    path VARCHAR(1024) NOT NULL,
    label VARCHAR(255) NOT NULL,
    document_order INT NOT NULL,
    CONSTRAINT fk_doc_session FOREIGN KEY (session_channel_id) REFERENCES debate_session(channel_id) ON DELETE CASCADE
);

CREATE TABLE debate_session_participant (
    session_channel_id UUID NOT NULL,
    agent_type VARCHAR(50) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    CONSTRAINT fk_part_session FOREIGN KEY (session_channel_id) REFERENCES debate_session(channel_id) ON DELETE CASCADE
);
```

- [ ] **Step 2: Add drafthouse migration location to application.properties**

Add `classpath:db/drafthouse/migration` to the existing Flyway locations:

```properties
quarkus.flyway.qhorus.locations=classpath:db/ledger/migration,classpath:db/qhorus/migration,classpath:db/drafthouse/migration
```

- [ ] **Step 3: Write the JPA entity**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionEntity.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "debate_session")
class DebateSessionEntity {

    @Id
    @Column(name = "channel_id")
    UUID channelId;

    @Column(name = "debate_session_id", nullable = false)
    String debateSessionId;

    @Column(name = "channel_name", nullable = false)
    String channelName;

    @Column(name = "comparison_path_a")
    String comparisonPathA;

    @Column(name = "comparison_path_b")
    String comparisonPathB;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "debate_session_document",
            joinColumns = @JoinColumn(name = "session_channel_id"))
    @OrderColumn(name = "document_order")
    List<DocumentEmbeddable> documents = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "debate_session_participant",
            joinColumns = @JoinColumn(name = "session_channel_id"))
    @MapKeyColumn(name = "agent_type")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "instance_id")
    Map<AgentType, String> participants = new HashMap<>();

    @Embeddable
    static class DocumentEmbeddable {
        @Column(name = "path", nullable = false, length = 1024)
        String path;

        @Column(name = "label", nullable = false)
        String label;

        DocumentEmbeddable() {}

        DocumentEmbeddable(String path, String label) {
            this.path = path;
            this.label = label;
        }
    }

    DebateSessionSnapshot toSnapshot() {
        List<DocumentEntry> docs = documents.stream()
                .map(d -> new DocumentEntry(d.path, d.label))
                .toList();
        ComparisonPair cp = (comparisonPathA != null && comparisonPathB != null)
                ? new ComparisonPair(comparisonPathA, comparisonPathB)
                : null;
        return new DebateSessionSnapshot(channelId, debateSessionId, channelName,
                docs, cp, Map.copyOf(participants));
    }

    static DebateSessionEntity fromSnapshot(DebateSessionSnapshot snap) {
        var entity = new DebateSessionEntity();
        entity.channelId = snap.channelId();
        entity.debateSessionId = snap.debateSessionId();
        entity.channelName = snap.channelName();
        entity.documents = snap.documents().stream()
                .map(d -> new DocumentEmbeddable(d.path(), d.label()))
                .toList();
        if (snap.comparison() != null) {
            entity.comparisonPathA = snap.comparison().pathA();
            entity.comparisonPathB = snap.comparison().pathB();
        }
        entity.participants = new HashMap<>(snap.participants());
        return entity;
    }
}
```

- [ ] **Step 4: Write JpaDebateSessionStore**

```java
// server/runtime/src/main/java/io/casehub/drafthouse/JpaDebateSessionStore.java
package io.casehub.drafthouse;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
@IfBuildProperty(name = "casehub.drafthouse.persistence.enabled", stringValue = "true")
public class JpaDebateSessionStore implements DebateSessionStore {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void save(DebateSessionSnapshot snapshot) {
        DebateSessionEntity existing = em.find(DebateSessionEntity.class, snapshot.channelId());
        if (existing != null) {
            existing.debateSessionId = snapshot.debateSessionId();
            existing.channelName = snapshot.channelName();
            existing.documents.clear();
            existing.documents.addAll(snapshot.documents().stream()
                    .map(d -> new DebateSessionEntity.DocumentEmbeddable(d.path(), d.label()))
                    .toList());
            if (snapshot.comparison() != null) {
                existing.comparisonPathA = snapshot.comparison().pathA();
                existing.comparisonPathB = snapshot.comparison().pathB();
            } else {
                existing.comparisonPathA = null;
                existing.comparisonPathB = null;
            }
            existing.participants.clear();
            existing.participants.putAll(snapshot.participants());
            em.merge(existing);
        } else {
            em.persist(DebateSessionEntity.fromSnapshot(snapshot));
        }
    }

    @Override
    public Optional<DebateSessionSnapshot> load(UUID channelId) {
        DebateSessionEntity entity = em.find(DebateSessionEntity.class, channelId);
        return Optional.ofNullable(entity).map(DebateSessionEntity::toSnapshot);
    }

    @Override
    @Transactional
    public void remove(UUID channelId) {
        DebateSessionEntity entity = em.find(DebateSessionEntity.class, channelId);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    public Collection<DebateSessionSnapshot> loadAll() {
        return em.createQuery("SELECT e FROM DebateSessionEntity e", DebateSessionEntity.class)
                .getResultList()
                .stream()
                .map(DebateSessionEntity::toSnapshot)
                .toList();
    }
}
```

- [ ] **Step 5: Write JpaDebateSessionStore integration test**

```java
// server/runtime/src/test/java/io/casehub/drafthouse/JpaDebateSessionStoreTest.java
package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
@io.quarkus.test.junit.QuarkusTestProfile.TestProfile(JpaDebateSessionStoreTest.PersistenceProfile.class)
class JpaDebateSessionStoreTest {

    public static class PersistenceProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.drafthouse.persistence.enabled", "true");
        }
    }

    @Inject
    DebateSessionStore store;

    @Test
    void store_isJpaImplementation() {
        assertThat(store).isInstanceOf(JpaDebateSessionStore.class);
    }

    @Test
    void save_and_load_roundTrips() {
        UUID id = UUID.randomUUID();
        var snap = new DebateSessionSnapshot(
                id, id.toString(), "ch-name",
                List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
                new ComparisonPair("/a.md", "/b.md"),
                Map.of(AgentType.REV, "rev-id", AgentType.IMP, "imp-id"));

        store.save(snap);
        var loaded = store.load(id);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().documents()).hasSize(2);
        assertThat(loaded.get().documents().get(0).path()).isEqualTo("/a.md");
        assertThat(loaded.get().comparison().pathA()).isEqualTo("/a.md");
        assertThat(loaded.get().participants()).hasSize(2);
    }

    @Test
    void save_update_overwritesExisting() {
        UUID id = UUID.randomUUID();
        var snap = new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec")), null, Map.of());
        store.save(snap);

        var updated = new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec"), new DocumentEntry("/b.md", "impl")),
                new ComparisonPair("/a.md", "/b.md"),
                Map.of(AgentType.REV, "rev-id"));
        store.save(updated);

        var loaded = store.load(id);
        assertThat(loaded.get().documents()).hasSize(2);
        assertThat(loaded.get().comparison()).isNotNull();
        assertThat(loaded.get().participants()).hasSize(1);
    }

    @Test
    void remove_makesLoadReturnEmpty() {
        UUID id = UUID.randomUUID();
        store.save(new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec")), null, Map.of()));
        store.remove(id);
        assertThat(store.load(id)).isEmpty();
    }

    @Test
    void save_nullComparison_persistsCorrectly() {
        UUID id = UUID.randomUUID();
        store.save(new DebateSessionSnapshot(id, id.toString(), "ch",
                List.of(new DocumentEntry("/a.md", "spec")), null, Map.of()));
        var loaded = store.load(id);
        assertThat(loaded.get().comparison()).isNull();
    }

    @Test
    void save_documentOrder_preserved() {
        UUID id = UUID.randomUUID();
        var docs = List.of(
                new DocumentEntry("/c.md", "third"),
                new DocumentEntry("/a.md", "first"),
                new DocumentEntry("/b.md", "second"));
        store.save(new DebateSessionSnapshot(id, id.toString(), "ch", docs, null, Map.of()));
        var loaded = store.load(id);
        assertThat(loaded.get().documents().get(0).path()).isEqualTo("/c.md");
        assertThat(loaded.get().documents().get(1).path()).isEqualTo("/a.md");
        assertThat(loaded.get().documents().get(2).path()).isEqualTo("/b.md");
    }
}
```

- [ ] **Step 6: Run JPA test**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=JpaDebateSessionStoreTest`
Expected: All tests pass

- [ ] **Step 7: Write CDI activation test**

```java
// Add to existing test file or create:
// server/runtime/src/test/java/io/casehub/drafthouse/StoreActivationTest.java
package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class StoreActivationTest {

    @Inject
    DebateSessionStore store;

    @Test
    void defaultProfile_usesNoOpStore() {
        assertThat(store).isInstanceOf(NoOpDebateSessionStore.class);
    }
}
```

- [ ] **Step 8: Run CDI activation test and full suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass (including StoreActivationTest confirming NoOp is default)

- [ ] **Step 9: Commit**

```
feat: JPA persistence for debate sessions with @IfBuildProperty gate

- DebateSessionEntity with @ElementCollection for documents and
  participants, @OrderColumn for document ordering
- JpaDebateSessionStore @ApplicationScoped, gated by
  casehub.drafthouse.persistence.enabled=true
- Flyway V100 on shared qhorus datasource at
  classpath:db/drafthouse/migration
- CDI activation test confirms NoOp default, JPA when enabled

Closes #64
```

---

## Final Verification

### Task 11: Full test suite and build verification

- [ ] **Step 1: Run the complete test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests pass

- [ ] **Step 2: Run api/ tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api`
Expected: All tests pass

- [ ] **Step 3: Build the uber-jar**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests`
Expected: BUILD SUCCESS, `server/runtime/target/drafthouse-server-runner.jar` created
