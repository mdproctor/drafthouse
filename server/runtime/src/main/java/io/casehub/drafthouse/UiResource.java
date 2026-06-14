package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@ApplicationScoped
@jakarta.ws.rs.Path("/")
public class UiResource {

    @ConfigProperty(name = "ui.dir", defaultValue = ".")
    String uiDir;

    private static final Map<String, String> MEDIA_TYPES = Map.of(
        ".css", "text/css",
        ".js", "application/javascript",
        ".mjs", "application/javascript",
        ".html", "text/html",
        ".json", "application/json",
        ".svg", "image/svg+xml"
    );

    @GET
    public Response index() throws IOException {
        return serveFile("index.html");
    }

    @GET
    @jakarta.ws.rs.Path("{path:.+}")
    public Response file(@PathParam("path") String path) throws IOException {
        return serveFile(path);
    }

    private Response serveFile(String fileName) throws IOException {
        Path base = Path.of(uiDir).normalize();
        Path resolved = base.resolve(fileName).normalize();

        // Path traversal guard
        if (!resolved.startsWith(base)) {
            return Response.status(403).build();
        }

        if (!Files.exists(resolved)) {
            return Response.status(404).build();
        }

        // Directory guard
        if (Files.isDirectory(resolved)) {
            return Response.status(404).build();
        }

        String mediaType = detectMediaType(fileName);
        return Response.ok(Files.readString(resolved), mediaType).build();
    }

    private String detectMediaType(String fileName) {
        for (Map.Entry<String, String> entry : MEDIA_TYPES.entrySet()) {
            if (fileName.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "application/octet-stream";
    }
}
