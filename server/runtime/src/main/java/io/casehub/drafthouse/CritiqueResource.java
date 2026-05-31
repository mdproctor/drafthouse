package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/api/critique")
public class CritiqueResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response critique(String body) {
        // Phase 2: LLM critique via LangChain4j
        return Response.status(501).entity("Critique not yet implemented").build();
    }
}
