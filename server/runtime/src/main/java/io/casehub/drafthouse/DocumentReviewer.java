package io.casehub.drafthouse;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface DocumentReviewer {

    @SystemMessage("""
            {{instructions}}

            ## Response Protocol
            outcome=DECLINE if the query is outside document review scope — explain why.
            outcome=AGREE if you agree and consider this point resolved.
            outcome=QUALIFY if you have more to say — discussion continues.
            Always provide your analysis in the content field.
            """)
    @UserMessage("""
            Document A (original):
            {{documentA}}

            Document B (revised):
            {{documentB}}

            {{selectionContext}}

            Review history (prior turns in this session):
            {{reviewHistory}}

            Current query: {{query}}
            """)
    ReviewResult review(String instructions, String documentA, String documentB,
                        String selectionContext, String reviewHistory, String query);
}
