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

            User query: {{query}}

            If this query is outside the scope of document review (e.g. general knowledge, \
            unrelated topics), respond with declined=true and explain why in content.
            Otherwise respond with declined=false and your review in content.
            """)
    ReviewResult review(String personality, String documentA,
                        String documentB, String selectionContext, String query);
}
