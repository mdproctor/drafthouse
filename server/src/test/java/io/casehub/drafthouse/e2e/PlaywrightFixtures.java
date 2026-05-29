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
