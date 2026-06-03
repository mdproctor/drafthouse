package io.casehub.drafthouse.debate;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

@DefaultBean
@ApplicationScoped
public class LangChain4jDebateAgentProvider implements DebateAgentProvider {

    @Inject SpecReviewerAiService    reviewerService;
    @Inject SpecImplementerAiService implementerService;

    private final RoundParser roundParser = new RoundParser();

    @Override
    public List<DebateEntry> executeReviewerRound(DebateRoundContext ctx) {
        String openPoints = summariseOpenPoints(ctx.currentState());
        String snippet = reviewerService.review(
                ctx.specContent(), ctx.debateContent(), openPoints, ctx.roundNumber());
        return roundParser.parse(snippet);
    }

    @Override
    public List<DebateEntry> executeImplementerRound(DebateRoundContext ctx) {
        String openPoints = summariseOpenPoints(ctx.currentState());
        String snippet = implementerService.respond(
                ctx.specContent(), ctx.debateContent(), openPoints, ctx.roundNumber());
        return roundParser.parse(snippet);
    }

    private String summariseOpenPoints(ReviewState state) {
        return state.points().values().stream()
                .filter(p -> p.currentStatus() != ReviewStatus.AGREED)
                .map(p -> p.id() + ": " + (p.thread().isEmpty() ? "(no content)" : p.thread().get(0).content())
                        + " [" + p.currentStatus() + "]")
                .collect(Collectors.joining("\n"));
    }
}
