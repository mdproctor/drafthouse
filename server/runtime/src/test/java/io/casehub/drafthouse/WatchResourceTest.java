package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WatchResourceTest {

    @Test
    void fileChangeTriggersSseEvent(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("watch-test.md");
        Files.writeString(file, "initial content");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        String url = "http://localhost:8081/api/watch?path=" +
                     java.net.URLEncoder.encode(file.toString(), "UTF-8");

        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.connect();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            received.set(line.trim());
                            latch.countDown();
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                // expected when connection closes after test
            }
        });

        // Give SSE connection time to establish
        Thread.sleep(300);

        // Modify the file
        Files.writeString(file, "modified content");

        // Wait for SSE event (max 5 seconds)
        assertTrue(latch.await(5, TimeUnit.SECONDS),
                   "SSE event was not received within 5 seconds");
        // RESTEasy reactive SSE emits "data:changed" (no space after colon)
        assertTrue(received.get().startsWith("data:") && received.get().contains("changed"),
                   "Expected SSE data event containing 'changed', got: " + received.get());

        sseThread.interrupt();
    }
}
