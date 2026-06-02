package io.casehub.drafthouse.debate;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/review/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Deprecated // Superseded by DraftHouseMcpTools (#24)
public class ReviewSessionResource {

    @Inject ReviewSessionService service;

    public record StartRequest(String specPath) {}

    @POST
    public Response startSession(StartRequest request) {
        ReviewSession session = service.startSession(request.specPath());
        return Response.ok(Map.of(
                "sessionId",   session.sessionId(),
                "sessionPath", session.sessionPath())).build();
    }

    @POST
    @Path("/{sessionId}/next-round")
    @Consumes(MediaType.WILDCARD)
    public Response nextRound(@PathParam("sessionId") String sessionId) {
        ReviewSession session = service.executeNextRound(sessionId);
        return Response.ok(Map.of(
                "sessionId",       session.sessionId(),
                "roundNumber",     session.roundNumber(),
                "humanFlagRaised", !session.currentState().humanFlags().isEmpty()
        )).build();
    }
}
