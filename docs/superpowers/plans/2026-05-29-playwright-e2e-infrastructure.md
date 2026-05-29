# Quarkus Playwright E2E Infrastructure — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a Java Playwright test suite (`@QuarkusTest` + `quarkus-playwright`) covering all DraftHouse UI features, replacing the deleted Electron-based suite.

**Architecture:** `quarkus-playwright` extension injects `BrowserContext` per test class; each test method opens a fresh `Page` via `context.newPage()`. Quarkus starts automatically on port 9002. Static `PlaywrightFixtures` handles path resolution and the render-ready wait. Eight test classes, each independently runnable. Diff legend UI implemented TDD-style on this branch.

**Tech Stack:** Java 21, Quarkus 3.34.3, `io.quarkiverse.playwright:quarkus-playwright` (version resolved in Task 1), JUnit 5, Maven Surefire, `@TestHTTPResource`.

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `server/pom.xml` | Modify | Add `quarkus-playwright` dependency |
| `server/src/main/resources/application.properties` | Modify | Add `%test.ui.dir=..` |
| `server/src/test/resources/fixtures/diff-a.md` | Create | Primary fixture — A-side (with headings, modified paragraphs, A-only section) |
| `server/src/test/resources/fixtures/diff-b.md` | Create | Primary fixture — B-side |
| `server/src/test/resources/fixtures/no-headings-a.md` | Create | Scroll sync fallback fixture — no headings |
| `server/src/test/resources/fixtures/no-headings-b.md` | Create | Scroll sync fallback fixture — no headings |
| `server/src/test/java/io/casehub/drafthouse/e2e/PlaywrightFixtures.java` | Create | Static utilities: `fixturePath`, `loadFilePair`, `waitForRender` |
| `server/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java` | Create | Panels load and render |
| `server/src/test/java/io/casehub/drafthouse/e2e/DiffRenderingE2ETest.java` | Create | LCS block diff, minimap canvas |
| `server/src/test/java/io/casehub/drafthouse/e2e/ScrollSyncE2ETest.java` | Create | Anchor-based sync + percentage fallback |
| `server/src/test/java/io/casehub/drafthouse/e2e/WordDiffE2ETest.java` | Create | Word-level highlights, pre exclusion |
| `server/src/test/java/io/casehub/drafthouse/e2e/SwapPanelsE2ETest.java` | Create | Panel swap — state, labels, diff re-run |
| `server/src/test/java/io/casehub/drafthouse/e2e/NavigationE2ETest.java` | Create | Next/prev keyboard + topbar, counter |
| `server/src/test/java/io/casehub/drafthouse/e2e/DiffSummaryE2ETest.java` | Create | `~N −N +N` topbar summary |
| `server/src/test/java/io/casehub/drafthouse/e2e/DiffLegendE2ETest.java` | Create | Colour key visible on load (TDD — red first) |
| `index.html` | Modify | Add diff legend HTML to topbar |
| `styles.css` | Modify | Add diff legend CSS |

---

## Task 1: Wire up quarkus-playwright

**Files:**
- Modify: `server/pom.xml`
- Modify: `server/src/main/resources/application.properties`

- [ ] **Step 1.1: Resolve the quarkus-playwright version**

Visit https://mvnrepository.com/artifact/io.quarkiverse.playwright/quarkus-playwright and find the latest release compatible with Quarkus 3.34. The extension is outside the Quarkus platform BOM — pick an explicit version.

- [ ] **Step 1.2: Add dependency to pom.xml**

In `<properties>`, add:
```xml
<quarkus-playwright.version><!-- version from Step 1.1 --></quarkus-playwright.version>
```

In `<dependencies>`, add after the existing `quarkus-junit5` dependency:
```xml
<dependency>
    <groupId>io.quarkiverse.playwright</groupId>
    <artifactId>quarkus-playwright</artifactId>
    <version>${quarkus-playwright.version}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 1.3: Add test UI dir to application.properties**

Add to `server/src/main/resources/application.properties`:
```
%test.ui.dir=..
```

This makes the Quarkus test instance serve `index.html` from the DraftHouse project root (one directory above `server/`). Maven always runs from `server/`, so `..` is stable.

- [ ] **Step 1.4: Create a minimal smoke test to verify wiring**

Create `server/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HappyPathE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void serverServesIndexHtml() {
        Page page = context.newPage();
        page.navigate(index.toString());
        assertTrue(page.title().equals("DraftHouse"), "page title should be DraftHouse");
    }
}
```

- [ ] **Step 1.5: Run — existing tests plus new smoke test must pass**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test
```

Expected: 6 existing tests + 1 new test pass. If the dependency version is wrong, Maven will fail with "artifact not found" — check the version on Maven Central and retry.

If the build downloads Playwright browser binaries, it will take 2–3 minutes on first run. This is expected.

- [ ] **Step 1.6: Note CI setup for the pipeline**

The Playwright Java library downloads Chromium on first use (~200MB). Add to the GitHub Actions workflow before `mvn test`:

```yaml
- name: Install Playwright browsers
  run: cd server && /opt/homebrew/bin/mvn exec:java \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install chromium"

- name: Cache Playwright browsers
  uses: actions/cache@v4
  with:
    path: ~/.cache/ms-playwright
    key: playwright-${{ hashFiles('server/pom.xml') }}
```

This step is informational only — the CI pipeline update is a separate concern from this branch.

- [ ] **Step 1.7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/pom.xml server/src/main/resources/application.properties server/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(infra): wire up quarkus-playwright, smoke test passes

Refs #18"
```

---

## Task 2: Create fixture files

**Files:**
- Create: `server/src/test/resources/fixtures/diff-a.md`
- Create: `server/src/test/resources/fixtures/diff-b.md`
- Create: `server/src/test/resources/fixtures/no-headings-a.md`
- Create: `server/src/test/resources/fixtures/no-headings-b.md`

- [ ] **Step 2.1: Create the fixtures directory**

```bash
mkdir -p /Users/mdproctor/claude/casehub/drafthouse/server/src/test/resources/fixtures
```

- [ ] **Step 2.2: Create diff-a.md**

Invariants satisfied: 3 matching h2/h3 headings (scroll sync anchors), one modified paragraph with shared LCS tokens (word-level diff), one A-only section (diff-del), long enough to be scrollable (content > viewport height).

Create `server/src/test/resources/fixtures/diff-a.md`:
```markdown
## Introduction

DraftHouse is a side-by-side markdown comparison tool that highlights differences between documents.
It uses an LCS diff algorithm to identify changed, added, and removed blocks.
This paragraph is identical in both documents.

Markdown content is rendered using marked.js with syntax highlighting via highlight.js.
The tool supports live file watching via a Server-Sent Events stream from the Quarkus backend.

## Features

The diff viewer shows changes between two documents using colour-coded blocks.
Modified blocks are marked with a border; deleted blocks appear only on the A-side;
inserted blocks appear only on the B-side.

Word-level differences within modified blocks are highlighted at the token level.
The LCS word diff algorithm preserves inline formatting such as bold and code spans.

### Word Changes

This sentence has some **modified** words that differ from the other document.
The unchanged tokens around the changed words should not be highlighted.
Only the specific words that changed will receive a highlight mark element.

## Scroll Sync

Scroll synchronisation uses heading anchors extracted from both panels.
Matched headings define piecewise linear interpolation segments.
Between matched headings, scroll position is interpolated proportionally.

The anchor list is rebuilt whenever files are reloaded or panels are swapped.
Unmatched headings on one side are skipped; only paired headings create anchors.

## Navigation

The next and previous buttons navigate between non-equal diff chunks.
Keyboard shortcuts n and p are equivalent to the topbar buttons.
The chunk counter shows the current position as N / total.

Clicking a bar on the minimap canvas scrolls both panels to that position.
The minimap draws red bars for A-side changes and green bars for B-side changes.

## Summary

This section appears only in document A and has no counterpart in document B.
It will be marked as a deleted block with the diff-del CSS class applied.
The minimap will draw a red bar at this section's vertical position.

This paragraph adds more content to ensure the panel is tall enough to scroll.
Additional lines of text make the scrollHeight exceed the clientHeight of the panel body.
Without scrollable content the scroll sync tests cannot verify that panels actually move.
```

- [ ] **Step 2.3: Create diff-b.md**

Same headings as diff-a.md (for anchor matching). Modified words in the "Word Changes" paragraph. "Summary" section replaced with "New Section" (B-only). Same long enough to scroll.

Create `server/src/test/resources/fixtures/diff-b.md`:
```markdown
## Introduction

DraftHouse is a side-by-side markdown comparison tool that highlights differences between documents.
It uses an LCS diff algorithm to identify changed, added, and removed blocks.
This paragraph is identical in both documents.

Markdown content is rendered using marked.js with syntax highlighting via highlight.js.
The tool supports live file watching via a Server-Sent Events stream from the Quarkus backend.

## Features

The diff viewer highlights changes between two documents using colour-coded blocks.
Modified blocks are marked with a border; deleted blocks appear only on the A-side;
inserted blocks appear only on the B-side.

Word-level differences within modified blocks are highlighted at the token level.
The LCS word diff algorithm preserves inline formatting such as bold and code spans.

### Word Changes

This sentence has some **updated** words that differ from the other document.
The unchanged tokens around the changed words should not be highlighted.
Only the specific words that changed will receive a highlight mark element.

## Scroll Sync

Scroll synchronisation uses heading anchors extracted from both panels.
Matched headings define piecewise linear interpolation segments.
Between matched headings, scroll position is interpolated proportionally.

The anchor list is rebuilt whenever files are reloaded or panels are swapped.
Unmatched headings on one side are skipped; only paired headings create anchors.

## Navigation

The next and previous buttons navigate between non-equal diff chunks.
Keyboard shortcuts n and p are equivalent to the topbar buttons.
The chunk counter shows the current position as N / total.

Clicking a bar on the minimap canvas scrolls both panels to that position.
The minimap draws red bars for A-side changes and green bars for B-side changes.

## New Section

This section appears only in document B and has no counterpart in document A.
It will be marked as an inserted block with the diff-ins CSS class applied.
The minimap will draw a green bar at this section's vertical position.

This paragraph adds more content to ensure the panel is tall enough to scroll.
Additional lines of text make the scrollHeight exceed the clientHeight of the panel body.
Without scrollable content the scroll sync tests cannot verify that panels actually move.
```

- [ ] **Step 2.4: Create no-headings-a.md**

No headings (`#`, `##`, `###`). At least one diff vs its pair. Enough content to be scrollable.

Create `server/src/test/resources/fixtures/no-headings-a.md`:
```markdown
This is a plain document with no headings of any level.
It contains only paragraphs so that the scroll anchor builder finds nothing to match.

When both panels contain content like this, the scroll anchor list will have only two entries:
the start anchor at position zero and the end anchor at maximum scroll position.
The interpolation between these two points is equivalent to percentage-based sync.

This paragraph differs from the corresponding paragraph in the other document.
The words in this sentence are arranged in a specific way that produces a diff.

Additional content ensures this document is long enough to be scrollable in the panel.
The panel body must be able to scroll for the scroll sync test to verify movement.
More text here to increase the document height beyond the viewport panel height.
Even more content so the scrollHeight clearly exceeds the clientHeight of the panel body element.
The diff viewer needs the panels to actually scroll for any scroll sync assertion to be meaningful.
This final paragraph ensures we have comfortably exceeded the minimum required document length.
```

- [ ] **Step 2.5: Create no-headings-b.md**

Create `server/src/test/resources/fixtures/no-headings-b.md`:
```markdown
This is a plain document with no headings of any level.
It contains only paragraphs so that the scroll anchor builder finds nothing to match.

When both panels contain content like this, the scroll anchor list will have only two entries:
the start anchor at position zero and the end anchor at maximum scroll position.
The interpolation between these two points is equivalent to percentage-based sync.

This paragraph is different from the corresponding paragraph in the other document.
The words in this sentence are arranged in an alternative way that produces a diff.

Additional content ensures this document is long enough to be scrollable in the panel.
The panel body must be able to scroll for the scroll sync test to verify movement.
More text here to increase the document height beyond the viewport panel height.
Even more content so the scrollHeight clearly exceeds the clientHeight of the panel body element.
The diff viewer needs the panels to actually scroll for any scroll sync assertion to be meaningful.
This final paragraph ensures we have comfortably exceeded the minimum required document length.
```

- [ ] **Step 2.6: Commit fixtures**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/resources/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(fixtures): add E2E fixture markdown files

Refs #18"
```

---

## Task 3: Create PlaywrightFixtures utility

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/PlaywrightFixtures.java`

- [ ] **Step 3.1: Write PlaywrightFixtures**

Create `server/src/test/java/io/casehub/drafthouse/e2e/PlaywrightFixtures.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.Page;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class PlaywrightFixtures {

    private PlaywrightFixtures() {}

    /**
     * Returns the absolute filesystem path to a fixture file.
     * Maven always runs from server/, so src/test/resources/fixtures/ is stable.
     */
    public static String fixturePath(String name) {
        return Path.of("src/test/resources/fixtures/" + name)
                   .toAbsolutePath()
                   .toString();
    }

    /**
     * Navigates to the app with the given file pair and waits for full render.
     * Paths are URL-encoded so spaces and special chars don't corrupt the query string.
     * The JS side uses URLSearchParams.get('a') which correctly decodes %2F back to /.
     */
    public static void loadFilePair(Page page, URL base, String fileA, String fileB) {
        String encodedA = URLEncoder.encode(fileA, StandardCharsets.UTF_8);
        String encodedB = URLEncoder.encode(fileB, StandardCharsets.UTF_8);
        page.navigate(base + "?a=" + encodedA + "&b=" + encodedB);
        waitForRender(page);
    }

    /**
     * Waits until diff annotation is complete.
     * [data-diff-chunk] is set by annotateRendered() inside updateDiffMap(), which
     * runs after marked.js rendering. It only appears when at least one diff chunk
     * exists — all fixture pairs must produce at least one diff.
     */
    public static void waitForRender(Page page) {
        page.waitForSelector("[data-diff-chunk]");
    }
}
```

- [ ] **Step 3.2: Verify it compiles**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test-compile -q
```

Expected: BUILD SUCCESS with no errors.

- [ ] **Step 3.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/PlaywrightFixtures.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(infra): PlaywrightFixtures utility — fixturePath, loadFilePair, waitForRender

Refs #18"
```

---

## Task 4: Complete HappyPathE2ETest

**Files:**
- Modify: `server/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java`

- [ ] **Step 4.1: Replace the smoke test with full happy-path assertions**

Overwrite `server/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;

@QuarkusTest
class HappyPathE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void panelARendersContent() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#render-a")).not().isEmpty();
    }

    @Test
    void panelBRendersContent() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#render-b")).not().isEmpty();
    }

    @Test
    void topbarIsVisible() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#topbar")).isVisible();
        assertThat(page.locator("#btn-sync")).isVisible();
        assertThat(page.locator("#btn-swap")).isVisible();
    }

    @Test
    void pageTitleIsDraftHouse() {
        Page page = context.newPage();
        page.navigate(index.toString());
        org.junit.jupiter.api.Assertions.assertEquals("DraftHouse", page.title());
    }
}
```

- [ ] **Step 4.2: Run**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=HappyPathE2ETest
```

Expected: 4 tests pass.

- [ ] **Step 4.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/HappyPathE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): HappyPathE2ETest — panels render, topbar visible

Refs #18"
```

---

## Task 5: DiffRenderingE2ETest

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/DiffRenderingE2ETest.java`

- [ ] **Step 5.1: Write the test**

Create `server/src/test/java/io/casehub/drafthouse/e2e/DiffRenderingE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DiffRenderingE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void diffChunksAnnotated() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // waitForRender already waited for [data-diff-chunk]; confirm count > 0
        int count = page.locator("[data-diff-chunk]").count();
        assertTrue(count > 0, "expected at least one diff-chunk annotation");
    }

    @Test
    void deletedBlockOnAside() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // diff-del marks a block present in A but not in B
        int count = page.locator("#render-a .diff-del").count();
        assertTrue(count > 0, "expected at least one .diff-del block in panel A");
    }

    @Test
    void insertedBlockOnBside() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // diff-ins marks a block present in B but not in A
        int count = page.locator("#render-b .diff-ins").count();
        assertTrue(count > 0, "expected at least one .diff-ins block in panel B");
    }

    @Test
    void minimapCanvasHasNonZeroDimensions() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#diff-map")).isVisible();
        int width  = (int) page.locator("#diff-map").evaluate("el => el.width");
        int height = (int) page.locator("#diff-map").evaluate("el => el.height");
        assertTrue(width  > 0, "minimap canvas width should be > 0");
        assertTrue(height > 0, "minimap canvas height should be > 0");
    }
}
```

- [ ] **Step 5.2: Run**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=DiffRenderingE2ETest
```

Expected: 4 tests pass.

- [ ] **Step 5.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/DiffRenderingE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): DiffRenderingE2ETest — diff blocks, minimap canvas

Refs #18"
```

---

## Task 6: ScrollSyncE2ETest

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/ScrollSyncE2ETest.java`

- [ ] **Step 6.1: Write the test**

`getScrollAnchors()` is exposed by `index.html` at line 330 and can be called from `page.evaluate()`.
The `body-a` scroll handler sets `body-b.scrollTop` after two `requestAnimationFrame` cycles (~33ms). `page.waitForTimeout(200)` is safe margin.

Create `server/src/test/java/io/casehub/drafthouse/e2e/ScrollSyncE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ScrollSyncE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void anchorModeBuildsInteriorAnchors() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // diff-a/b has 3 matching headings (## Introduction, ## Features, ### Word Changes)
        // plus start and end anchors = at least 5 total
        int anchorCount = (int) page.evaluate("() => getScrollAnchors().length");
        assertTrue(anchorCount > 2,
            "expected interior anchors from heading matches, got " + anchorCount);
    }

    @Test
    void anchorModeScrollsPanelB() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // Scroll panel A to 300px; sync should move panel B
        page.evaluate("() => { document.getElementById('body-a').scrollTop = 300; }");
        page.waitForTimeout(200); // allow two rAF cycles for syncing flag to reset
        int scrollB = (int) page.evaluate("() => document.getElementById('body-b').scrollTop");
        assertTrue(scrollB > 0, "panel B should have scrolled when panel A was scrolled");
    }

    @Test
    void noHeadingsProducesOnlyEndpointAnchors() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("no-headings-a.md"), fixturePath("no-headings-b.md"));
        // No heading matches → only start {a:0,b:0} and end {a:maxA,b:maxB} anchors
        int anchorCount = (int) page.evaluate("() => getScrollAnchors().length");
        assertEquals(2, anchorCount,
            "expected exactly 2 anchors (start + end) with no heading matches");
    }

    @Test
    void noHeadingsModeStillScrollsPanelB() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("no-headings-a.md"), fixturePath("no-headings-b.md"));
        page.evaluate("() => { document.getElementById('body-a').scrollTop = 300; }");
        page.waitForTimeout(200);
        int scrollB = (int) page.evaluate("() => document.getElementById('body-b').scrollTop");
        assertTrue(scrollB > 0, "panel B should scroll even when sync uses only endpoint anchors");
    }
}
```

- [ ] **Step 6.2: Run**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=ScrollSyncE2ETest
```

Expected: 4 tests pass. If the scroll tests fail with scrollB == 0, the fixture files may not be tall enough to scroll — add more content to diff-a/b and no-headings-a/b.

- [ ] **Step 6.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/ScrollSyncE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): ScrollSyncE2ETest — anchor mode and endpoint-only fallback

Closes #3
Refs #18"
```

---

## Task 7: WordDiffE2ETest

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/WordDiffE2ETest.java`

- [ ] **Step 7.1: Write the test**

The fixture diff-a/b has "**modified**" vs "**updated**" in the `### Word Changes` section. `annotateWordDiffs` runs on `mod` chunks only; `pre` blocks are explicitly excluded. After a swap, `updateDiffMap()` re-runs word diff — highlights must reappear.

Create `server/src/test/java/io/casehub/drafthouse/e2e/WordDiffE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.waitForRender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WordDiffE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void changedWordsHighlightedInPanelA() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-a mark.diff-word-a").count();
        assertTrue(count > 0, "expected at least one word highlight in panel A");
    }

    @Test
    void changedWordsHighlightedInPanelB() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-b mark.diff-word-b").count();
        assertTrue(count > 0, "expected at least one word highlight in panel B");
    }

    @Test
    void preBlocksHaveNoWordHighlights() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        int count = page.locator("#render-a pre mark, #render-b pre mark").count();
        assertEquals(0, count, "pre blocks must not contain word highlights");
    }

    @Test
    void wordHighlightsPersistAfterSwap() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.evaluate("() => swapPanels()");
        waitForRender(page);
        int countA = page.locator("#render-a mark.diff-word-a").count();
        int countB = page.locator("#render-b mark.diff-word-b").count();
        // After swap: what was B is now A. Highlights should re-appear on the new A/B.
        assertTrue(countA + countB > 0, "word highlights should reappear after swap");
        page.evaluate("() => swapPanels()"); // restore for other tests
    }
}
```

- [ ] **Step 7.2: Run**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=WordDiffE2ETest
```

Expected: 4 tests pass. If `changedWordsHighlightedInPanelA` fails, verify the fixtures produce a `mod` chunk (not just `del`/`ins`) — the `### Word Changes` paragraph must be present in both files with different words.

- [ ] **Step 7.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/WordDiffE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): WordDiffE2ETest — word highlights, pre exclusion, swap persistence

Refs #18"
```

---

## Task 8: SwapPanelsE2ETest

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/SwapPanelsE2ETest.java`

- [ ] **Step 8.1: Write the test**

After swap: what was panel A is now panel B and vice versa. The diff re-runs. The A-only section ("Summary") appears as `diff-ins` on the new B side (was `diff-del` on old A). Swap button must be enabled after both panels load.

Create `server/src/test/java/io/casehub/drafthouse/e2e/SwapPanelsE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.waitForRender;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SwapPanelsE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void swapButtonEnabledAfterLoad() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#btn-swap")).isEnabled();
    }

    @Test
    void swapRerunsAndProducesDiffChunks() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.evaluate("() => swapPanels()");
        waitForRender(page);
        int count = page.locator("[data-diff-chunk]").count();
        assertTrue(count > 0, "diff chunks should be present after swap");
    }

    @Test
    void afterSwapAsideShowsOriginalBContent() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // diff-b.md has "New Section" which is B-only. After swap it becomes A-side.
        page.evaluate("() => swapPanels()");
        waitForRender(page);
        String textA = page.locator("#render-a").innerText();
        assertTrue(textA.contains("New Section"),
            "after swap, panel A should contain text from original B (New Section)");
    }

    @Test
    void swapTogglesBackToOriginal() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String originalTextA = page.locator("#render-a").innerText();
        page.evaluate("() => swapPanels()");
        waitForRender(page);
        page.evaluate("() => swapPanels()");
        waitForRender(page);
        String restoredTextA = page.locator("#render-a").innerText();
        assertTrue(restoredTextA.contains("Summary"),
            "after double swap, panel A should be restored (contains 'Summary')");
    }
}
```

- [ ] **Step 8.2: Run**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=SwapPanelsE2ETest
```

Expected: 4 tests pass.

- [ ] **Step 8.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/SwapPanelsE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): SwapPanelsE2ETest — swap button, diff re-run, content swap

Refs #18"
```

---

## Task 9: NavigationE2ETest

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/NavigationE2ETest.java`

- [ ] **Step 9.1: Write the test**

After `loadFilePair`, nav buttons are enabled. `nextDiff()` scrolls to the first diff chunk and sets the counter to "1 / N". The `n` key calls `nextDiff()`. The `diff-counter` starts as "— / —" before any navigation.

Create `server/src/test/java/io/casehub/drafthouse/e2e/NavigationE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class NavigationE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void navButtonsEnabledAfterLoad() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#btn-next")).isEnabled();
        assertThat(page.locator("#btn-prev")).isEnabled();
    }

    @Test
    void nextButtonUpdatesCounter() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-next").click();
        String counter = page.locator("#diff-counter").innerText();
        assertTrue(counter.startsWith("1 /"),
            "counter should show '1 / N' after first next click, got: " + counter);
    }

    @Test
    void nKeyNavigatesToNextDiff() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.keyboard().press("n");
        String counter = page.locator("#diff-counter").innerText();
        assertTrue(counter.startsWith("1 /"),
            "n key should navigate to first diff, counter was: " + counter);
    }

    @Test
    void pKeyNavigatesToPreviousDiff() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        // Navigate forward twice, then back once → should be at chunk 1 (not 0)
        page.keyboard().press("n");
        page.keyboard().press("n");
        page.keyboard().press("p");
        String counter = page.locator("#diff-counter").innerText();
        assertTrue(counter.startsWith("1 /"),
            "p key after two n presses should go back to 1, counter was: " + counter);
    }
}
```

- [ ] **Step 9.2: Run**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=NavigationE2ETest
```

Expected: 4 tests pass.

- [ ] **Step 9.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/NavigationE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): NavigationE2ETest — next/prev buttons and keyboard shortcuts

Refs #18"
```

---

## Task 10: DiffSummaryE2ETest

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/DiffSummaryE2ETest.java`

- [ ] **Step 10.1: Write the test**

`#diff-summary` text is set by `updateDiffSummary()` to a string like `~2 −1 +1`. The fixture diff-a/b produces: modified blocks (word changes paragraph), one del-only block (Summary section), one ins-only block (New Section). The tooltip is `~ modified · − only in A · + only in B`.

Create `server/src/test/java/io/casehub/drafthouse/e2e/DiffSummaryE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DiffSummaryE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void summaryIsNonEmptyAfterLoad() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        assertFalse(text.isBlank(), "diff summary should show counts after loading files with diffs");
    }

    @Test
    void summaryShowsModifiedCount() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        assertTrue(text.contains("~"),
            "summary should contain ~ for modified chunks, got: " + text);
    }

    @Test
    void summaryShowsDeletedCount() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        // diff-a.md has a "Summary" section absent from diff-b.md → at least one del chunk
        assertTrue(text.contains("−"),
            "summary should contain − for A-only chunks, got: " + text);
    }

    @Test
    void summaryShowsInsertedCount() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        String text = page.locator("#diff-summary").innerText();
        // diff-b.md has a "New Section" absent from diff-a.md → at least one ins chunk
        assertTrue(text.contains("+"),
            "summary should contain + for B-only chunks, got: " + text);
    }
}
```

- [ ] **Step 10.2: Run**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=DiffSummaryE2ETest
```

Expected: 4 tests pass.

- [ ] **Step 10.3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/DiffSummaryE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): DiffSummaryE2ETest — ~N −N +N summary counts

Refs #18"
```

---

## Task 11: DiffLegendE2ETest — RED

**Files:**
- Create: `server/src/test/java/io/casehub/drafthouse/e2e/DiffLegendE2ETest.java`

- [ ] **Step 11.1: Write the failing test**

The legend doesn't exist yet. This test will fail. That is correct — TDD requires the test to fail first.

Create `server/src/test/java/io/casehub/drafthouse/e2e/DiffLegendE2ETest.java`:

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;

@QuarkusTest
class DiffLegendE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void legendIsVisible() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#diff-legend")).isVisible();
    }

    @Test
    void legendShowsAsideColour() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator(".legend-del")).isVisible();
    }

    @Test
    void legendShowsBsideColour() {
        Page page = context.newPage();
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator(".legend-ins")).isVisible();
    }
}
```

- [ ] **Step 11.2: Run — confirm it fails**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=DiffLegendE2ETest
```

Expected: 3 tests FAIL with "element not found" or "element not visible". If they pass, the legend already exists — skip Task 12.

- [ ] **Step 11.3: Commit the red test**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/src/test/java/io/casehub/drafthouse/e2e/DiffLegendE2ETest.java
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(e2e): DiffLegendE2ETest — red, legend not yet implemented

Refs #17
Refs #18"
```

---

## Task 12: Implement diff legend — GREEN

**Files:**
- Modify: `index.html`
- Modify: `styles.css`

The legend is a small colour-key in the topbar. It reuses the exact same colours as the diff rendering (`rgba(239,68,68,0.35)` for A-side, `rgba(34,197,94,0.35)` for B-side) — no new colour values.

- [ ] **Step 12.1: Add legend HTML to the topbar in index.html**

In `index.html`, locate the topbar div (around line 33). Add the legend element after `#diff-summary` and before `#topbar-spacer`:

Find:
```html
  <span id="diff-summary" data-tooltip="" style="font-size:11px;color:#a09080;padding:0 6px;cursor:default;position:relative"></span>
  <div id="topbar-spacer"></div>
```

Replace with:
```html
  <span id="diff-summary" data-tooltip="" style="font-size:11px;color:#a09080;padding:0 6px;cursor:default;position:relative"></span>
  <span id="diff-legend" title="A only · B only">
    <span class="legend-swatch legend-del"></span><span class="legend-label">A</span>
    <span class="legend-swatch legend-ins"></span><span class="legend-label">B</span>
  </span>
  <div id="topbar-spacer"></div>
```

- [ ] **Step 12.2: Add legend CSS to styles.css**

Append to `styles.css`:

```css
/* ── Diff legend ──────────────────────────────────────────────────── */
#diff-legend {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: #a09080;
  padding: 0 6px;
}
.legend-swatch {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}
.legend-del { background: rgba(239, 68, 68, 0.35); }
.legend-ins { background: rgba(34, 197, 94, 0.35); }
.legend-label { padding-right: 4px; }
```

- [ ] **Step 12.3: Run the legend test — confirm it passes**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test -Dtest=DiffLegendE2ETest
```

Expected: 3 tests PASS.

- [ ] **Step 12.4: Run the full suite — confirm nothing regressed**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test
```

Expected: all tests pass (6 existing REST-Assured + all E2E tests).

- [ ] **Step 12.5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add index.html styles.css
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(legend): add diff colour-key legend to topbar

Small A/B colour swatch legend in the topbar. Reuses existing diff colours
(rgba(239,68,68) for A-side, rgba(34,197,94) for B-side).

Closes #17
Refs #18"
```

---

## Task 13: Final verification and CLAUDE.md update

- [ ] **Step 13.1: Full test suite — green**

```bash
cd /Users/mdproctor/claude/casehub/drafthouse/server && /opt/homebrew/bin/mvn test
```

Expected output includes something like:
```
Tests run: N, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All 6 existing REST-Assured tests + all E2E tests must pass.

- [ ] **Step 13.2: Update CLAUDE.md testing section**

In `CLAUDE.md`, update the Testing section. Find:

```
**Java server tests (6 tests):**
```bash
cd server && /opt/homebrew/bin/mvn test
```

**E2E tests:** Deferred — Quarkus Playwright migration is a separate epic.
```

Replace with:

```
**All tests (Java server + Playwright E2E):**
```bash
cd server && /opt/homebrew/bin/mvn test
```

Run a single E2E class:
```bash
cd server && /opt/homebrew/bin/mvn test -Dtest=ScrollSyncE2ETest
```

E2E tests live in `server/src/test/java/io/casehub/drafthouse/e2e/`. Fixture files are in `server/src/test/resources/fixtures/`.
```

- [ ] **Step 13.3: Commit CLAUDE.md**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add CLAUDE.md
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "docs: update CLAUDE.md — Playwright E2E tests now in server/mvn test

no-issue: documentation only"
```
