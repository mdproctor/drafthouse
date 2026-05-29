# Quarkus Playwright E2E Test Infrastructure — Design Spec

**Issue:** #18  
**Branch:** `issue-18-playwright-infrastructure`  
**Date:** 2026-05-29

---

## Context

The Electron-based Playwright suite from `md-compare` was deleted during the DraftHouse migration (#15). DraftHouse has no E2E tests for the browser UI. This spec defines the replacement: a Java Playwright suite running against the Quarkus server directly, integrated into the existing `mvn test` run.

---

## Decision: Java Playwright + `@QuarkusTest`

Use the `quarkus-playwright` quarkiverse extension (`io.quarkiverse.playwright:quarkus-playwright`). Tests live in `server/src/test/java/io/casehub/drafthouse/e2e/`, run via `mvn test` alongside the existing six REST-Assured tests, and require no separate toolchain or process management.

Rejected: Node.js Playwright against the Quarkus jar (would require external process management and a separate test runner; inconsistent with the project's Java-first direction).

---

## Section 1 — Dependencies & Configuration

### `server/pom.xml`

Add to `<properties>`:
```xml
<quarkus-playwright.version><!-- resolved from Maven Central at implementation time --></quarkus-playwright.version>
```

Add to `<dependencies>`:
```xml
<dependency>
    <groupId>io.quarkiverse.playwright</groupId>
    <artifactId>quarkus-playwright</artifactId>
    <version>${quarkus-playwright.version}</version>
    <scope>test</scope>
</dependency>
```

### `server/src/main/resources/application.properties`

Add one line:
```
%test.ui.dir=..
```

This makes the Quarkus test instance (port 9002) serve `index.html` from the project root — one directory above `server/`. Relative path; stable because Maven always runs from `server/`.

No Maven profile separation. All tests — REST-Assured and Playwright — run in one `mvn test`.

---

## Section 2 — Fixture Files

Location: `server/src/test/resources/fixtures/`

Fixtures are test-owned markdown files with controlled, frozen content. They are not the demo files (`sample-a.md`, `sample-b.md`) — demo content can change without breaking tests; fixtures cannot.

| File pair | Purpose |
|-----------|---------|
| `diff-a.md` / `diff-b.md` | Primary pair. Contains: shared h2/h3 headings (scroll sync anchors), a modified paragraph (word-level diff), a block present only in A (del), a block present only in B (ins). Covers happy path, diff rendering, scroll sync anchor mode, word diff, swap, nav, summary. |
| `no-headings-a.md` / `no-headings-b.md` | Minimal pair with no headings. Used exclusively by the scroll sync percentage-fallback test. |
| `legend-a.md` / `legend-b.md` | Minimal pair with at least one diff. Used by `DiffLegendE2ETest` — the legend must render regardless of content. |

Fixture content is defined during TDD: assertions drive what content is needed. File names and roles are fixed here; content is not.

Path resolution:
```java
Path.of("src/test/resources/fixtures/" + name).toAbsolutePath().toString()
```
Stable because Maven always runs from `server/`.

---

## Section 3 — `PlaywrightFixtures` Utility

`server/src/test/java/io/casehub/drafthouse/e2e/PlaywrightFixtures.java`

All static. No inheritance.

```java
public final class PlaywrightFixtures {
    private PlaywrightFixtures() {}

    public static String fixturePath(String name) {
        return Path.of("src/test/resources/fixtures/" + name)
                   .toAbsolutePath().toString();
    }

    public static void loadFilePair(Page page, URL base, String fileA, String fileB) {
        page.navigate(base + "?a=" + fileA + "&b=" + fileB);
        waitForRender(page);
    }

    public static void waitForRender(Page page) {
        // [data-diff-chunk] is set by annotateRendered() inside updateDiffMap(),
        // which runs after marked.js finishes. It is the reliable signal that
        // both rendering and diff annotation are complete.
        page.waitForSelector("[data-diff-chunk]");
    }
}
```

`waitForRender` deliberately waits for `[data-diff-chunk]` rather than a content element (`p`, `h2`). The diff annotation step runs after markdown rendering; asserting before it completes produces flaky results.

---

## Section 4 — Test Class Structure

### Browser lifecycle

`@InjectPlaywright` injects a fresh `BrowserContext` per test class. Each test method opens its own `Page` via `context.newPage()`. The browser process is shared across all test classes for the duration of the Maven test run. Isolation is at the `BrowserContext` level — cookies and localStorage do not bleed between test classes.

### Pattern

```java
@QuarkusTest
class ScrollSyncE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void anchorSyncMatchesHeadings() {
        Page page = context.newPage();
        loadFilePair(page, index,
            fixturePath("diff-a.md"),
            fixturePath("diff-b.md"));
        // assertions
    }
}
```

`@TestHTTPResource("/")` resolves the test server base URL, including the correct port (9002). No hardcoded ports.

### Eight test classes

| Class | Fixture pair | Closes |
|-------|-------------|--------|
| `HappyPathE2ETest` | `diff-a/b` | — |
| `DiffRenderingE2ETest` | `diff-a/b` | — |
| `ScrollSyncE2ETest` | `diff-a/b` (anchor mode) + `no-headings-a/b` (% fallback) | #3 |
| `WordDiffE2ETest` | `diff-a/b` | — |
| `SwapPanelsE2ETest` | `diff-a/b` | — |
| `NavigationE2ETest` | `diff-a/b` | — |
| `DiffSummaryE2ETest` | `diff-a/b` | — |
| `DiffLegendE2ETest` | `legend-a/b` | #17 |

Each class is independently runnable: `mvn test -Dtest=ScrollSyncE2ETest -pl server`.

---

## Section 5 — Scope and Done Criteria

This branch delivers:
- `pom.xml` dependency + version property
- `%test.ui.dir=..` in `application.properties`
- All three fixture pairs (content driven by TDD)
- `PlaywrightFixtures`
- All eight test classes with full assertions
- `mvn test` green: 6 existing REST-Assured tests + all E2E tests passing

Issues closed on completion: #3 (scroll sync — implementation already in main, tests now written), #17 (diff legend — implementation and tests delivered together on this branch).

---

## Platform & Protocol Coherence

No platform concerns — pure application-tier test infrastructure. No foundation module touches, no SPI changes, no cross-repo effects.

Local Playwright protocols (`docs/protocols/`): four existing protocol files cover Playwright patterns for Electron. The Quarkus Playwright patterns established by this implementation should be captured as a new local protocol at close.
