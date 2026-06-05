# XS/S Cleanup (#34 + #28) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unnecessary `@QuarkusTest` from `DebateRoundTripTest` and make `ReviewSessionService`'s storage path configurable via SmallRye Config.

**Architecture:** #34 is a one-line annotation removal. #28 restructures `DraftHouseConfig` from a flat `@ConfigMapping(prefix = "casehub.drafthouse.reviewer")` to a nested design with prefix `casehub.drafthouse`, adds a `Storage` sub-interface with `root()` backed by a `@WithDefault` expression, injects it into `ReviewSessionService`, and updates the two callers of the now-nested `Reviewer` methods.

**Tech Stack:** Quarkus 3.34.3, SmallRye Config (`@ConfigMapping`, `@WithDefault`, expression `${user.home}`), Mockito (two-level mocking pattern), JUnit 5, Maven multi-module (`server/pom.xml`, `runtime` module).

---

## File Map

| File | Action |
|------|--------|
| `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateRoundTripTest.java` | Modify — remove `@QuarkusTest` + import |
| `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java` | Modify — restructure to nested `Reviewer` + `Storage` |
| `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java` | Modify — update call sites to `config.reviewer().*` |
| `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java` | Modify — `config.reviewer().maxDocChars()` |
| `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionService.java` | Modify — inject config, drop static constant, call `config.storage().root()` |
| `server/runtime/src/main/resources/application.properties` | Modify — add storage.root key comment + `%test` override |
| `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java` | Modify — two-level mock for `Reviewer` sub-interface |

---

## Task 1: Remove `@QuarkusTest` from `DebateRoundTripTest` (#34)

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateRoundTripTest.java`

- [ ] **Step 1: Verify the test currently passes under Quarkus**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateRoundTripTest
```

Expected: `BUILD SUCCESS`, 3 tests pass.

- [ ] **Step 2: Remove `@QuarkusTest` and its import**

In `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateRoundTripTest.java`:

Remove line 3:
```java
import io.quarkus.test.junit.QuarkusTest;
```

Remove line 14:
```java
@QuarkusTest
```

The file should now open with:
```java
package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DebateRoundTripTest {
```

- [ ] **Step 3: Run test to verify it still passes without Quarkus**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateRoundTripTest
```

Expected: `BUILD SUCCESS`, 3 tests pass. Quarkus startup log should NOT appear.

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add \
  server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateRoundTripTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m \
  "test: remove @QuarkusTest from DebateRoundTripTest — pure domain test needs no CDI

Closes #34"
```

---

## Task 2: Restructure `DraftHouseConfig` to nested `Reviewer` + `Storage` (#28, foundation)

This task changes the config interface shape. The existing config keys in
`application.properties` are **unchanged** — `casehub.drafthouse.reviewer.personality`
and `casehub.drafthouse.reviewer.max-doc-chars` still work under the nested structure.
The new key `casehub.drafthouse.storage.root` is added.

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java`

- [ ] **Step 1: Rewrite `DraftHouseConfig.java`**

Replace the entire file content:

```java
package io.casehub.drafthouse;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.nio.file.Path;

@ConfigMapping(prefix = "casehub.drafthouse")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DraftHouseConfig {

    Reviewer reviewer();

    Storage storage();

    interface Reviewer {

        String personality();

        @WithDefault("100000")
        int maxDocChars();
    }

    interface Storage {

        /** Storage root for review session files. Defaults to ~/.drafthouse/reviews. */
        @WithDefault("${user.home}/.drafthouse/reviews")
        Path root();
    }
}
```

- [ ] **Step 2: Update call sites in `DraftHouseMcpTools.java`**

In `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java`,
replace every occurrence of `config.maxDocChars()` with `config.reviewer().maxDocChars()`
and every occurrence of `config.personality()` with `config.reviewer().personality()`.

There are four occurrences total (lines 72, 73, 75, 76, 96 in the original file):

```java
        if (docAContent.length() > config.reviewer().maxDocChars()) {
            return "error: document A exceeds maximum size of " + config.reviewer().maxDocChars() + " characters";
        }
        if (docBContent.length() > config.reviewer().maxDocChars()) {
            return "error: document B exceeds maximum size of " + config.reviewer().maxDocChars() + " characters";
        }
```

And the `ReviewSession` construction (line 96):
```java
                    docAContent, docBContent, null, null, config.reviewer().personality());
```

- [ ] **Step 3: Update `ReviewerChannelBackendFactory.java`**

In `server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java`,
replace `config.maxDocChars()` with `config.reviewer().maxDocChars()`.

- [ ] **Step 4: Update mock setup in `DraftHouseMcpToolsTest.java`**

In `setUp()`, replace the two flat stubs with two-level mocking:

Before:
```java
        config = mock(DraftHouseConfig.class);
        // ...
        when(config.maxDocChars()).thenReturn(100_000);
        when(config.personality()).thenReturn("You are a reviewer.");
```

After:
```java
        config = mock(DraftHouseConfig.class);
        DraftHouseConfig.Reviewer reviewer = mock(DraftHouseConfig.Reviewer.class);
        when(config.reviewer()).thenReturn(reviewer);
        when(reviewer.maxDocChars()).thenReturn(100_000);
        when(reviewer.personality()).thenReturn("You are a reviewer.");
```

Note: `config.reviewer()` returns `null` on a plain mock — stubbing `when(config.reviewer())` first
is required. Without it, `when(reviewer.personality())` causes a `NullPointerException`
during stub setup.

- [ ] **Step 5: Compile to catch any missed call sites**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
```

Expected: `BUILD SUCCESS`. Any remaining `config.personality()` or `config.maxDocChars()`
call will produce a compile error pointing to the exact location.

- [ ] **Step 6: Run the full test suite**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: `BUILD SUCCESS`. All tests pass including `DraftHouseMcpToolsTest`,
`ReviewSessionLifecycleTest`, and `DebateRoundTripTest`.

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add \
  server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseConfig.java \
  server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseMcpTools.java \
  server/runtime/src/main/java/io/casehub/drafthouse/ReviewerChannelBackendFactory.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DraftHouseMcpToolsTest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m \
  "refactor: restructure DraftHouseConfig — nested Reviewer + Storage sub-interfaces

Prefix changes from casehub.drafthouse.reviewer to casehub.drafthouse.
Existing keys (casehub.drafthouse.reviewer.*) are unchanged.
Adds casehub.drafthouse.storage.root for session storage configurability.

Refs #28"
```

---

## Task 3: Wire storage path into `ReviewSessionService` (#28, completion)

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionService.java`
- Modify: `server/runtime/src/main/resources/application.properties`

- [ ] **Step 1: Update `ReviewSessionService.java`**

Remove the static constant and add CDI injection. The full updated class header and
field section (replacing lines 1–37):

```java
package io.casehub.drafthouse.debate;

import io.casehub.drafthouse.DraftHouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ReviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSessionService.class);

    @Inject DraftHouseConfig config;
    @Inject DebateAgentProvider agentProvider;

    private final DebateParser         parser    = new DebateParser();
    private final SummaryProjector     projector = new SummaryProjector();
    private final SummaryRenderer      renderer  = new SummaryRenderer();
    private final DebateEntryFormatter formatter = new DebateEntryFormatter();

    private final Map<String, ReviewSession> sessions = new ConcurrentHashMap<>();
```

In `startSession()`, replace:
```java
        Path sessionPath  = SESSIONS_BASE.resolve(sessionId);
```
with:
```java
        Path sessionPath  = config.storage().root().resolve(sessionId);
```

- [ ] **Step 2: Add config key to `application.properties`**

Append to `server/runtime/src/main/resources/application.properties`:

```properties

# Review session storage root (defaults to ~/.drafthouse/reviews)
# casehub.drafthouse.storage.root=/custom/path
%test.casehub.drafthouse.storage.root=${java.io.tmpdir}/drafthouse-test-sessions
```

The `%test` override is a failsafe so Quarkus startup validation in `@QuarkusTest`
uses a temp path. Note: the current test suite never calls `startSession()`, so no
files are created at this path yet. When #27 adds live session tests, add a per-run
unique suffix to avoid CI parallelism collisions (see #27 comment).

- [ ] **Step 3: Build and run all tests**

```bash
/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && \
/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime
```

Expected: `BUILD SUCCESS`. All tests pass. `ReviewSessionLifecycleTest` (a `@QuarkusTest`)
will validate Quarkus startup with the new config key resolved from the `%test` profile.

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add \
  server/runtime/src/main/java/io/casehub/drafthouse/debate/ReviewSessionService.java \
  server/runtime/src/main/resources/application.properties
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m \
  "feat: make ReviewSessionService storage path configurable via casehub.drafthouse.storage.root

Replaces hardcoded ~/.drafthouse/reviews static constant with @Inject DraftHouseConfig.
Default preserved via @WithDefault(\"\${user.home}/.drafthouse/reviews\") on Storage.root().
Test profile override added to application.properties as a startup-validation failsafe.

Closes #28"
```
