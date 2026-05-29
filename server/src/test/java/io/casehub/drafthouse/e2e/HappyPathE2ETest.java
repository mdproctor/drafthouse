package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@WithPlaywright
class HappyPathE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Test
    void serverServesIndexHtml() {
        Page page = context.newPage();
        page.navigate(index.toString());
        assertEquals("DraftHouse", page.title(), "page title should be DraftHouse");
    }
}
