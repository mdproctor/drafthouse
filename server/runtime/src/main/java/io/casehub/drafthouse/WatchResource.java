package io.casehub.drafthouse;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@jakarta.ws.rs.Path("/api/watch")
public class WatchResource {

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @org.jboss.resteasy.reactive.RestSseElementType(MediaType.TEXT_PLAIN)
    public Multi<String> watch(@QueryParam("path") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Multi.createFrom().failure(new BadRequestException("path required"));
        }

        return Multi.createFrom().emitter(em -> {
            java.nio.file.Path target = java.nio.file.Path.of(filePath);
            java.nio.file.Path dir    = target.getParent();
            String name = target.getFileName().toString();

            WatchService ws;
            try {
                ws = FileSystems.getDefault().newWatchService();
                dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY);
            } catch (IOException e) {
                em.fail(e);
                return;
            }

            em.onTermination(() -> {
                try { ws.close(); } catch (IOException ignored) {}
            });

            Thread.ofVirtual().start(() -> {
                try {
                    while (!em.isCancelled()) {
                        WatchKey key = ws.poll(200, TimeUnit.MILLISECONDS);
                        if (key == null) continue;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (name.equals(event.context().toString())) {
                                em.emit("changed");
                            }
                        }
                        if (!key.reset()) break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ClosedWatchServiceException ignored) {
                    // WatchService was closed by onTermination — normal shutdown path
                } finally {
                    em.complete();
                }
            });
        });
    }
}
