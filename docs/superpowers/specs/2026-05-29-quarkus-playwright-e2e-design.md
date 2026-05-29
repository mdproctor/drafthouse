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

The extension is outside the Quarkus platform BOM and must be pinned manually — check the Quarkiverse release page for the latest version compatible with Quarkus 3.34.

Add to `<properties>`:
```xml
<quarkus-playwright.version><!-- resolved from Quarkiverse releases at implementation time --></quarkus-playwright.version>
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

### CI — browser binary installation

The Playwright Java library downloads Chromium (~200MB) on first use from the network. On a fresh CI runner this either adds 2–3 minutes or fails on restricted networks. Add to the CI pipeline before `mvn test`:

```bash
mvn -pl server exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
```

Cache `~/.cache/ms-playwright` between CI runs to avoid repeated downloads.

---

## Section 2 — Fixture Files

Location: `server/src/test/resources/fixtures/`

Fixtures are test-owned markdown files with controlled, frozen content. They are not the demo files (`sample-a.md`, `sample-b.md`) — demo content can change without breaking tests; fixtures cannot.

**Invariant for all fixture pairs:** every pair must produce at least one diff between A and B. `waitForRender` waits for `[data-diff-chunk]`, which `annotateRendered()` only sets on changed blocks. Identical-content pairs cause `waitForRender` to timeout.

| File pair | Purpose | Structural invariants |
|-----------|---------|----------------------|
| `diff-a.md` / `diff-b.md` | Primary pair used by most test classes. | At least 2 h2/h3 headings with identical text in both files (scroll sync anchor matching); one paragraph that is modified between A and B with enough shared tokens for LCS to produce word-level highlights (not a complete replacement); one block present only in A; one block present only in B. |
| `no-headings-a.md` / `no-headings-b.md` | Scroll sync percentage-fallback test only. | No headings (`#`, `##`, `###`); at least one diff to trigger `[data-diff-chunk]`. |

`DiffLegendE2ETest` uses `diff-a/b` — the legend renders whenever the page loads with any content, and `diff-a/b` already guarantees at least one diff. A third fixture pair for the legend test would be redundant.

Fixture content is finalised during TDD, but must satisfy the structural invariants above. The invariants exist so that assertions written against the fixture remain valid regardless of how the markdown is otherwise worded.

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
        // Paths are URL-encoded to handle spaces and other special characters.
        // URLSearchParams.get('a') on the JS side decodes %2F → / correctly.
        page.navigate(base + "?a=" + URLEncoder.encode(fileA, StandardCharsets.UTF_8)
                           + "&b=" + URLEncoder.encode(fileB, StandardCharsets.UTF_8));
        waitForRender(page);
    }

    public static void waitForRender(Page page) {
        // [data-diff-chunk] is set by annotateRendered() inside updateDiffMap(),
        // which runs after marked.js finishes. It is the reliable signal that
        // both rendering and diff annotation are complete.
        // Requires at least one diff — identical-content pairs will timeout here.
        page.waitForSelector("[data-diff-chunk]");
    }
}
```

---

## Section 4 — Test Class Structure

### Browser / page lifecycle

**Preferred pattern — verify at implementation time:** The `quarkus-playwright` extension may support `@InjectPlaywright Page page` with per-test-method lifecycle managed by the extension. If supported, use it — no `context.newPage()` call in test methods, no page lifecycle concern, cleaner tests.

**Fallback pattern — if `Page` injection is not supported:** Inject `BrowserContext` and call `context.newPage()` per test method. The extension closes the `BrowserContext` at cleanup, which closes all child pages, so not calling `page.close()` per method is safe for DraftHouse's stateless UI. If multiple open pages per class is considered imprecise, add `@AfterEach void closePage() { page.close(); }`.

`@InjectPlaywright` injects a fresh `BrowserContext` (and thus `Page`) per test class. Isolation between test classes is at the `BrowserContext` level — cookies and localStorage do not bleed.

### Preferred pattern (if Page injection supported)

```java
@QuarkusTest
class ScrollSyncE2ETest {

    @InjectPlaywright
    Page page;

    @TestHTTPResource("/")
    URL index;

    @Test
    void anchorSyncMatchesHeadings() {
        loadFilePair(page, index,
            fixturePath("diff-a.md"),
            fixturePath("diff-b.md"));
        // anchor-mode assertions
    }

    @Test
    void percentageFallbackWhenNoHeadings() {
        loadFilePair(page, index,
            fixturePath("no-headings-a.md"),
            fixturePath("no-headings-b.md"));
        // percentage-fallback assertions
    }
}
```

`ScrollSyncE2ETest` is the only class that navigates to two different fixture pairs — one test method per fixture pair. `@TestHTTPResource("/")` resolves the test server base URL including the correct port (9002). No hardcoded ports.

### Fallback pattern (BrowserContext)

```java
@QuarkusTest
class HappyPathE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void rendersCorrectly() {
        Page page = context.newPage();
        loadFilePair(page, index,
            fixturePath("diff-a.md"),
            fixturePath("diff-b.md"));
        // assertions
    }
}
```

### Seven test classes

| Class | Fixture pair(s) | Closes |
|-------|----------------|--------|
| `HappyPathE2ETest` | `diff-a/b` | — |
| `DiffRenderingE2ETest` | `diff-a/b` | — |
| `ScrollSyncE2ETest` | `diff-a/b` (anchor mode) + `no-headings-a/b` (% fallback) | #3 |
| `WordDiffE2ETest` | `diff-a/b` | — |
| `SwapPanelsE2ETest` | `diff-a/b` | — |
| `NavigationE2ETest` | `diff-a/b` | — |
| `DiffSummaryE2ETest` | `diff-a/b` | — |
| `DiffLegendE2ETest` | `diff-a/b` | #17 |

Each class is independently runnable: `mvn test -Dtest=ScrollSyncE2ETest -pl server`.

---

## Section 5 — Scope and Done Criteria

This branch delivers:
- `pom.xml` dependency + version property
- `%test.ui.dir=..` in `application.properties`
- Both fixture pairs (content satisfying the structural invariants, driven by TDD)
- `PlaywrightFixtures`
- All eight test classes with full assertions
- Diff legend UI implementation in `index.html`/`styles.css` (`DiffLegendE2ETest` is written red-first, then the legend is implemented to make it green)
- `mvn test` green: 6 existing REST-Assured tests + all E2E tests passing

Issues closed on completion: #3 (scroll sync — implementation already in main, tests now written), #17 (diff legend — implementation and tests both delivered on this branch via TDD).

---

## Platform & Protocol Coherence

No platform concerns — pure application-tier test infrastructure. No foundation module touches, no SPI changes, no cross-repo effects.

Local Playwright protocols (`docs/protocols/`): four existing files cover Electron-based patterns. The Quarkus Playwright test patterns established by this implementation should be captured as a new local protocol at branch close.
