package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class BrainstormMcpToolsTest {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\":\"([^\"]+)\"");

    @Inject BrainstormMcpTools tools;
    @Inject BrainstormSessionRegistry registry;

    private String activeSessionId;

    @BeforeEach
    void setUp() {
        activeSessionId = null;
    }

    @AfterEach
    void tearDown() {
        if (activeSessionId != null) {
            tools.endBrainstorm(activeSessionId);
        }
    }

    @Test
    void startBrainstorm_createsSession_returnsSessionId() {
        String result = tools.startBrainstorm();
        assertThat(result).contains("sessionId");
        activeSessionId = extractSessionId(result);
        assertThat(activeSessionId).isNotBlank();

        assertThat(registry.find(activeSessionId)).isPresent();
        assertThat(registry.find(activeSessionId).get().state())
                .isEqualTo(BrainstormSession.State.ACTIVE);
    }

    @Test
    void presentOptions_addsOptionsToSession() {
        activeSessionId = startSession();

        String result = tools.presentOptions(activeSessionId,
                "[{\"id\":\"A\",\"title\":\"Option A\",\"description\":\"Desc A\",\"tradeoffs\":\"Trade A\"},"
              + "{\"id\":\"B\",\"title\":\"Option B\",\"description\":\"Desc B\",\"tradeoffs\":\"Trade B\"}]");

        assertThat(result).contains("presented");
        BrainstormSession session = registry.find(activeSessionId).get();
        assertThat(session.options()).hasSize(2);
        assertThat(session.options().get(0).id()).isEqualTo("A");
        assertThat(session.options().get(1).id()).isEqualTo("B");
    }

    @Test
    void presentOptions_invalidJson_returnsError() {
        activeSessionId = startSession();
        String result = tools.presentOptions(activeSessionId, "not json");
        assertThat(result).startsWith("error:");
    }

    @Test
    void presentOptions_invalidSessionId_returnsError() {
        String result = tools.presentOptions("nonexistent", "[]");
        assertThat(result).startsWith("error:");
    }

    @Test
    void setRecommendation_marksOptionRecommended() {
        activeSessionId = startSession();
        tools.presentOptions(activeSessionId,
                "[{\"id\":\"A\",\"title\":\"A\",\"description\":\"A\",\"tradeoffs\":\"A\"},"
              + "{\"id\":\"B\",\"title\":\"B\",\"description\":\"B\",\"tradeoffs\":\"B\"}]");

        String result = tools.setRecommendation(activeSessionId, "B");
        assertThat(result).contains("recommended");

        BrainstormSession session = registry.find(activeSessionId).get();
        assertThat(session.findOption("B").get().status())
                .isEqualTo(BrainstormOption.Status.RECOMMENDED);
        assertThat(session.findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.LIVE);
    }

    @Test
    void setRecommendation_unknownOption_returnsError() {
        activeSessionId = startSession();
        tools.presentOptions(activeSessionId,
                "[{\"id\":\"A\",\"title\":\"A\",\"description\":\"A\",\"tradeoffs\":\"A\"}]");
        String result = tools.setRecommendation(activeSessionId, "Z");
        assertThat(result).startsWith("error:");
    }

    @Test
    void updateOption_enrichesOption() {
        activeSessionId = startSession();
        tools.presentOptions(activeSessionId,
                "[{\"id\":\"A\",\"title\":\"A\",\"description\":\"A\",\"tradeoffs\":\"A\"}]");

        String result = tools.updateOption(activeSessionId, "A",
                "Updated description", "Updated tradeoffs");
        assertThat(result).contains("updated");

        BrainstormOption option = registry.find(activeSessionId).get().findOption("A").get();
        assertThat(option.description()).isEqualTo("Updated description");
        assertThat(option.tradeoffs()).isEqualTo("Updated tradeoffs");
        assertThat(option.status()).isEqualTo(BrainstormOption.Status.EXPLORED);
    }

    @Test
    void markEliminated_dimsOption() {
        activeSessionId = startSession();
        tools.presentOptions(activeSessionId,
                "[{\"id\":\"A\",\"title\":\"A\",\"description\":\"A\",\"tradeoffs\":\"A\"},"
              + "{\"id\":\"B\",\"title\":\"B\",\"description\":\"B\",\"tradeoffs\":\"B\"}]");

        String result = tools.markEliminated(activeSessionId, "A");
        assertThat(result).contains("eliminated");

        assertThat(registry.find(activeSessionId).get().findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.ELIMINATED);
    }

    @Test
    void markSelected_convergesSession() {
        activeSessionId = startSession();
        tools.presentOptions(activeSessionId,
                "[{\"id\":\"A\",\"title\":\"A\",\"description\":\"A\",\"tradeoffs\":\"A\"},"
              + "{\"id\":\"B\",\"title\":\"B\",\"description\":\"B\",\"tradeoffs\":\"B\"}]");

        String result = tools.markSelected(activeSessionId, "B");
        assertThat(result).contains("selected");

        BrainstormSession session = registry.find(activeSessionId).get();
        assertThat(session.state()).isEqualTo(BrainstormSession.State.CONVERGED);
        assertThat(session.findOption("B").get().status())
                .isEqualTo(BrainstormOption.Status.SELECTED);
        activeSessionId = null;
    }

    @Test
    void markSelected_onConvergedSession_returnsError() {
        activeSessionId = startSession();
        tools.presentOptions(activeSessionId,
                "[{\"id\":\"A\",\"title\":\"A\",\"description\":\"A\",\"tradeoffs\":\"A\"}]");
        tools.markSelected(activeSessionId, "A");

        String result = tools.markSelected(activeSessionId, "A");
        assertThat(result).startsWith("error:");
        activeSessionId = null;
    }

    @Test
    void endBrainstorm_removesSession() {
        activeSessionId = startSession();
        String result = tools.endBrainstorm(activeSessionId);
        assertThat(result).contains("ended");
        assertThat(registry.find(activeSessionId)).isEmpty();
        activeSessionId = null;
    }

    @Test
    void endBrainstorm_invalidSession_returnsError() {
        String result = tools.endBrainstorm("nonexistent");
        assertThat(result).startsWith("error:");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String startSession() {
        String result = tools.startBrainstorm();
        return extractSessionId(result);
    }

    private static String extractSessionId(String json) {
        Matcher m = SESSION_ID.matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
