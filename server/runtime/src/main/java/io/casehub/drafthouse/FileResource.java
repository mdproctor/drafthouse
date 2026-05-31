package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@ApplicationScoped
@Path("/api/file")
public class FileResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response readFile(@QueryParam("path") String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Response.status(400).entity("path parameter required").build();
        }
        try {
            String content = Files.readString(Paths.get(filePath));
            return Response.ok(content).build();
        } catch (IOException e) {
            return Response.status(404).entity(e.getMessage()).build();
        }
    }
}
