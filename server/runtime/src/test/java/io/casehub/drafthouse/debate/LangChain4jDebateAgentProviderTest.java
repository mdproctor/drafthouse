package io.casehub.drafthouse.debate;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class LangChain4jDebateAgentProviderTest extends DebateAgentProviderContractTest {

    private static final String VALID_SNIPPET =
            "TYPE: raise\n" +
            "PRIORITY: P1\n" +
            "SCOPE: Isolated\n" +
            "LOCATION: §3.2\n" +
            "CONTENT: Both start_review and begin_review appear with no canonical form stated.";

    @InjectMock SpecReviewerAiService    reviewerService;
    @InjectMock SpecImplementerAiService implementerService;

    @Inject LangChain4jDebateAgentProvider provider;

    @Override
    protected DebateAgentProvider provider() {
        return provider;
    }

    @Override
    protected String validRoundSnippet() {
        return VALID_SNIPPET;
    }

    @Override
    protected void stubModelToReturn(String snippet) {
        when(reviewerService.review(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(snippet);
        when(implementerService.respond(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(snippet);
    }
}
