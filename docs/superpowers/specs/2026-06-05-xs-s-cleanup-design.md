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
nested design with `@ConfigMapping(prefix = "casehub.drafthouse")`. The existing
config keys are **unchanged** — `casehub.drafthouse.reviewer.personality` and
`casehub.drafthouse.reviewer.max-doc-chars` resolve identically under the nested
structure.

```java
@ConfigMapping(prefix = "casehub.drafthouse")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface DraftHouseConfig {

    Reviewer reviewer();
    Storage storage();

    interface Reviewer {
        String personality();
        @WithDefault("100000") int maxDocChars();
    }

    interface Storage {
        /** Storage root for review session files. Defaults to ~/.drafthouse/reviews. */
        @WithDefault("${user.home}/.drafthouse/reviews")
        Path storageRoot();
    }
}
```

**Why `@WithDefault` with an expression works here:** SmallRye Config's `@WithDefault`
in a `@ConfigMapping` is processed through the expression interceptor chain, unlike
MicroProfile's `@ConfigProperty#defaultValue` which the MicroProfile spec explicitly
excludes from expression expansion. The expression `${user.home}` resolves from the
System Properties `ConfigSource` (ordinal 400). SmallRye Config also includes a
built-in `Path` converter, so the return type is `Path` directly — no
`Optional<String>` or `@PostConstruct` needed.

**Expression syntax note:** `${sys:user.home}` (single colon) is the *fallback-value*
syntax — it means "expand property `sys`; if not found, use literal `user.home`" — and
would produce the wrong result. The correct explicit system-property handler uses double
colon: `${sys::user.home}`. The plain `${user.home}` is equivalent and preferred.

The config key for the new property is: `casehub.drafthouse.storage.storage-root`
(SmallRye converts `storageRoot()` to kebab-case).

#### Caller migration (mechanical)

Two production callers of the flat `DraftHouseConfig` methods must update:

| File | Before | After |
|------|--------|-------|
| `DraftHouseMcpTools` | `config.personality()` | `config.reviewer().personality()` |
| `DraftHouseMcpTools` | `config.maxDocChars()` | `config.reviewer().maxDocChars()` |
| `ReviewerChannelBackendFactory` | `config.maxDocChars()` | `config.reviewer().maxDocChars()` |

#### `ReviewSessionService` update

Drop the static constant entirely. Inject `DraftHouseConfig` and call
`config.storage().storageRoot()` at the point of use:

```java
@Inject DraftHouseConfig config;

public ReviewSession startSession(String specPath) {
    String sessionId = generateSessionId();
    Path sessionPath = config.storage().storageRoot().resolve(sessionId);
    // ...
}
```

No `@PostConstruct`, no mutable instance field, no package-private visibility leak.
SmallRye resolves the path once at config load time; every call to `storageRoot()`
returns the same cached `Path`.

#### `application.properties` addition

```properties
# Review session storage root (defaults to ~/.drafthouse/reviews)
# casehub.drafthouse.storage.storage-root=/custom/path
%test.casehub.drafthouse.storage.storage-root=${java.io.tmpdir}/drafthouse-test-sessions
```

The `%test` override is a failsafe: `ReviewSessionService` is not yet injected by any
live CDI bean, so its config is validated at Quarkus startup but `startSession()` is
never called in the current test suite. When #27 wires this service into live MCP
tooling, integration tests that exercise `startSession()` will need the temp-dir
override to avoid writing to `~/.drafthouse/reviews`. Adding it now prevents that
oversight.

**Deferred risk:** If any bean or test starts injecting `ReviewSessionService` before
#27 is complete, the `%test` override ensures `startSession()` writes to a temp dir
rather than the real home directory.

#### No dedicated storage test

The `@WithDefault` expression resolution is SmallRye Config's behaviour to test, not
ours. The storage path config is covered by the `%test` override in
`application.properties` (which exercises the real Quarkus config machinery in
`ReviewSessionLifecycleTest`) and will gain behavioral test coverage — via
`startSession()` with a `@TempDir` storage root — when #27 makes
`ReviewSessionService` live.

#### Mock setup for `DraftHouseMcpToolsTest` (two-level, not deep stubs)

`DraftHouseMcpTools` calls `config.reviewer().personality()` and
`config.reviewer().maxDocChars()`. A plain `mock(DraftHouseConfig.class)` returns
`null` from `config.reviewer()`, which causes `NullPointerException` during Mockito
stub setup — not at test execution time. Deep stubs (`RETURNS_DEEP_STUBS`) hide this
but produce over-mocked objects that are hard to reason about. The correct pattern is
explicit two-level mocking:

```java
config = mock(DraftHouseConfig.class);
DraftHouseConfig.Reviewer reviewer = mock(DraftHouseConfig.Reviewer.class);
when(config.reviewer()).thenReturn(reviewer);
when(reviewer.personality()).thenReturn("You are a reviewer.");
when(reviewer.maxDocChars()).thenReturn(100_000);
```

This applies to `setUp()` in `DraftHouseMcpToolsTest`. All 11 existing test methods
use the shared `setUp()` — updating it once is sufficient.

---

## Files changed

| File | Change |
|------|--------|
| `server/runtime/.../debate/DebateRoundTripTest.java` | Remove `@QuarkusTest` + import |
| `server/runtime/.../DraftHouseConfig.java` | Restructure to nested `Reviewer` + `Storage`; add `Storage.storageRoot()` |
| `server/runtime/.../DraftHouseMcpTools.java` | `config.reviewer().*` call sites |
| `server/runtime/.../ReviewerChannelBackendFactory.java` | `config.reviewer().maxDocChars()` |
| `server/runtime/.../debate/ReviewSessionService.java` | `@Inject DraftHouseConfig`; drop static constant; call `config.storage().storageRoot()` inline |
| `server/runtime/src/main/resources/application.properties` | Document `storage-root` key; add `%test` override |
| `server/runtime/.../DraftHouseMcpToolsTest.java` | Two-level mock setup for `Reviewer` sub-interface |

## Files verified unchanged

| File | Why unaffected |
|------|----------------|
| `server/runtime/.../ReviewerChannelBackendTest.java` | Constructs `ReviewerChannelBackend` directly with `maxDocChars` as a primitive int argument — does not touch `DraftHouseConfig` |
| `server/runtime/.../ReviewSessionRegistryTest.java` | Tests the registry in isolation with no config dependency |
| `server/runtime/.../ReviewSessionLifecycleTest.java` | `@QuarkusTest` — Quarkus wires real config from `application.properties`; the `%test` override for `storage-root` means the test profile resolves correctly without any change to the test class itself |
