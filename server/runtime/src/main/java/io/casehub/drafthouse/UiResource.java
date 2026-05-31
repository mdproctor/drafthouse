package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;

@ApplicationScoped
@jakarta.ws.rs.Path("/")
public class UiResource {

    @ConfigProperty(name = "ui.dir", defaultValue = ".")
    String uiDir;

    @GET
    public Response index() throws IOException {
        return serveFile("index.html");
    }

    @GET
    @jakarta.ws.rs.Path("{file}")
    public Response file(@PathParam("file") String file) throws IOException {
        return serveFile(file);
    }

    private Response serveFile(String fileName) throws IOException {
        java.nio.file.Path resolved = java.nio.file.Path.of(uiDir).resolve(fileName).normalize();
        if (!Files.exists(resolved)) {
            return Response.status(404).build();
        }
        String mediaType = fileName.endsWith(".css") ? "text/css" : MediaType.TEXT_HTML;
        return Response.ok(Files.readString(resolved), mediaType).build();
    }
}
