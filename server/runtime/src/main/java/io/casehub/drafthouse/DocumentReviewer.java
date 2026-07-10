package io.casehub.drafthouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.logging.Logger;

@ApplicationScoped
public class DocumentReviewer {

    private static final Logger LOG = Logger.getLogger(DocumentReviewer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject AgentProvider agentProvider;

    public ReviewResult review(String instructions, String documentA, String documentB,
                               String selectionContext, String reviewHistory, String query) {
        String systemPrompt = instructions + "\n\n"
                + "## Response Protocol\n"
                + "Respond with a JSON object with two fields:\n"
                + "- \"outcome\": one of \"AGREE\", \"QUALIFY\", or \"DECLINE\"\n"
                + "- \"content\": your analysis\n\n"
                + "outcome=DECLINE if the query is outside document review scope — explain why.\n"
                + "outcome=AGREE if you agree and consider this point resolved.\n"
                + "outcome=QUALIFY if you have more to say — discussion continues.\n"
                + "Always provide your analysis in the content field.\n"
                + "Respond with ONLY the JSON object, no other text.";

        String userPrompt = "Document A (original):\n" + documentA + "\n\n"
                + "Document B (revised):\n" + documentB + "\n\n"
                + selectionContext + "\n\n"
                + "Review history (prior turns in this session):\n" + reviewHistory + "\n\n"
                + "Current query: " + query;

        String responseText = collectResponse(AgentSessionConfig.of(systemPrompt, userPrompt));
        return parseResult(responseText);
    }

    private String collectResponse(AgentSessionConfig config) {
        StringBuilder sb = new StringBuilder();
        agentProvider.invoke(config)
                .subscribe().asStream()
                .forEach(event -> {
                    if (event instanceof AgentEvent.TextDelta td) {
                        sb.append(td.text());
                    }
                });
        return sb.toString();
    }

    private ReviewResult parseResult(String responseText) {
        try {
            String trimmed = responseText.strip();
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").strip();
            }
            JsonNode node = MAPPER.readTree(trimmed);
            String outcome = node.has("outcome") ? node.get("outcome").asText() : "DECLINE";
            String content = node.has("content") ? node.get("content").asText() : trimmed;
            return switch (outcome.toUpperCase()) {
                case "AGREE" -> ReviewResult.agree(content);
                case "QUALIFY" -> ReviewResult.qualify(content);
                default -> ReviewResult.decline(content);
            };
        } catch (Exception e) {
            LOG.warning("Failed to parse ReviewResult JSON — treating as QUALIFY: " + e.getMessage());
            return ReviewResult.qualify(responseText);
        }
    }
}
