package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

public abstract class DebateAgentProviderContractTest {

    protected abstract DebateAgentProvider provider();
    protected abstract String validRoundSnippet();
    protected abstract void stubModelToReturn(String snippet);

    @BeforeEach
    void stubDefault() {
        stubModelToReturn(validRoundSnippet());
    }

    private DebateRoundContext emptyContext() {
        return new DebateRoundContext("spec content", "",
                new ReviewState(new LinkedHashMap<>(), new ArrayList<>()), 1, "session-1");
    }

    @Test
    void reviewerReturnsAtLeastOneEntry() {
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        assertThat(entries).isNotEmpty();
    }

    @Test
    void implementerReturnsAtLeastOneEntry() {
        List<DebateEntry> entries = provider().executeImplementerRound(emptyContext());
        assertThat(entries).isNotEmpty();
    }

    @Test
    void entryTypeIsNotNull() {
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        assertThat(entries).allMatch(e -> e.type() != null);
    }

    @Test
    void raiseEntryHasPriorityAndScope() {
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        entries.stream()
               .filter(e -> e.type() == EntryType.RAISE)
               .forEach(e -> {
                   assertThat(e.priority()).isNotNull();
                   assertThat(e.scope()).isNotNull();
               });
    }

    @Test
    void entryContentIsNotBlank() {
        List<DebateEntry> entries = provider().executeReviewerRound(emptyContext());
        assertThat(entries).allMatch(e -> e.content() != null && !e.content().isBlank());
    }
}
