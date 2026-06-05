# XS/S Cleanup — #34 and #28

**Branch:** `issue-34-xs-s-cleanup`  
**Covers:** #34 (XS), #28 (S)  
**Date:** 2026-06-05

---

## Issue #34 — Remove `@QuarkusTest` from `DebateRoundTripTest`

### Problem

`DebateRoundTripTest` carries `@QuarkusTest` despite having no CDI dependencies. All
five objects under test (`RoundParser`, `DebateEntryFormatter`, `DebateParser`,
`SummaryProjector`, `SummaryRenderer`) are instantiated with `new`. Classpath resource
loading (`getClassLoader().getResource()`) works in plain JUnit without Quarkus. The
annotation adds ~3s Quarkus startup overhead to every test run and misleads readers
about the test's nature.

### Change

Remove `@QuarkusTest` annotation and the `io.quarkus.test.junit.QuarkusTest` import
from `DebateRoundTripTest`. No other changes. The test continues to run under
`mvn test -pl runtime`.

---

## Issue #28 — Configurable session storage path

### Problem

`ReviewSessionService` hardcodes its session storage root as a static constant:

```java
private static final Path SESSIONS_BASE =
        Path.of(System.getProperty("user.home"), ".drafthouse", "reviews");
```

This makes the path impossible to override without code changes — problematic for
alternative deployments and test isolation.

### Design

#### `DraftHouseConfig` restructure

Change from a flat `@ConfigMapping(prefix = "casehub.drafthouse.reviewer")` to a
nested design with `@ConfigMapping(prefix = "casehub.drafthouse")`. The config keys
in `application.properties` are **unchanged** — `casehub.drafthouse.reviewer.personality`
and `casehub.drafthouse.reviewer.max-doc-chars` resolve identically under the nested
structure.

```java
@ConfigMapping(prefix = "casehub.drafthouse")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DraftHouseConfig {

    Reviewer reviewer();
    Session session();

    interface Reviewer {
        String personality();
        @WithDefault("100000") int maxDocChars();
    }

    interface Session {
        /** Storage root for review session files. Defaults to ~/.drafthouse/reviews. */
        Optional<String> storage();
    }
}
```

`Optional<String>` is used instead of `@WithDefault` because the default involves
`System.getProperty("user.home")`, which must be resolved at runtime in code rather
than relying on SmallRye expression expansion in annotation strings.

#### Caller migration (mechanical)

Two production callers of the flat `DraftHouseConfig` methods must update:

| File | Before | After |
|------|--------|-------|
| `DraftHouseMcpTools` | `config.personality()` | `config.reviewer().personality()` |
| `DraftHouseMcpTools` | `config.maxDocChars()` | `config.reviewer().maxDocChars()` |
| `ReviewerChannelBackendFactory` | `config.maxDocChars()` | `config.reviewer().maxDocChars()` |

`DraftHouseMcpToolsTest` mock setup updates from `when(config.personality())` to
`when(config.reviewer().personality())` and similarly for `maxDocChars()`.

#### `ReviewSessionService` update

Replace the static constant with a CDI-injected config and instance field:

```java
@Inject DraftHouseConfig config;
private Path sessionsBase;

@PostConstruct
void init() {
    sessionsBase = config.session().storage()
        .map(Path::of)
        .orElseGet(() -> Path.of(System.getProperty("user.home"), ".drafthouse", "reviews"));
}
```

All internal references to `SESSIONS_BASE` become `sessionsBase`.

#### `application.properties` addition

```properties
# Review session storage path (defaults to ~/.drafthouse/reviews if not set)
# casehub.drafthouse.session.storage=/custom/path
```

No test-profile override is added because `ReviewSessionService` is currently a dead
bean (no callers) — its `@PostConstruct` never fires in tests. When #27 is implemented
and sessions are exercised in integration tests, a `%test.casehub.drafthouse.session.storage`
pointing to a temp directory should be added at that time.

### Testing

A new plain-JUnit test `ReviewSessionServiceStorageTest` (no `@QuarkusTest`) verifies
the two path-resolution cases:

1. `Optional.empty()` → resolves to `~/.drafthouse/reviews`
2. `Optional.of("/custom/path")` → resolves to `/custom/path`

This requires `sessionsBase` to be package-private (not `private`) in
`ReviewSessionService` so the test can assert it directly after calling `init()`
with a mocked `DraftHouseConfig`.

---

## Files changed

| File | Change |
|------|--------|
| `server/runtime/.../debate/DebateRoundTripTest.java` | Remove `@QuarkusTest` + import |
| `server/runtime/.../DraftHouseConfig.java` | Restructure to nested `Reviewer` + `Session` |
| `server/runtime/.../DraftHouseMcpTools.java` | `config.reviewer().*` call sites |
| `server/runtime/.../ReviewerChannelBackendFactory.java` | `config.reviewer().maxDocChars()` |
| `server/runtime/.../debate/ReviewSessionService.java` | Inject config, drop static constant, `@PostConstruct init()` |
| `server/runtime/src/main/resources/application.properties` | Document session storage key |
| `server/runtime/.../DraftHouseMcpToolsTest.java` | Mock setup for nested config |
| `server/runtime/.../debate/ReviewSessionServiceStorageTest.java` | New test for path resolution |
