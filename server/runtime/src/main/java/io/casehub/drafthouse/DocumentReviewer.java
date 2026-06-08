package io.casehub.drafthouse;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface DocumentReviewer {

    @SystemMessage("{{personality}}")
    @UserMessage("""
            Document A (original):
            {{documentA}}

            Document B (revised):
            {{documentB}}

            {{selectionContext}}

            Review history (prior turns in this session):
            {{reviewHistory}}

            Current query: {{query}}

            If this query is outside the scope of document review (e.g. general knowledge, \
            unrelated topics): outcome=DECLINE, explain why in content.
            Otherwise: outcome=AGREE if you agree and this point is resolved (discussion concludes); \
            outcome=QUALIFY if you qualify your position (discussion continues, you have more to say). \
            Provide your review in content.
            """)
    ReviewResult review(String personality, String documentA, String documentB,
                        String selectionContext, String reviewHistory, String query);
}
