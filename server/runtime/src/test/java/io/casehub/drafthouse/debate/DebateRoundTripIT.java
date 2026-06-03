package io.casehub.drafthouse.debate;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class DebateRoundTripIT {

    private final RoundParser           roundParser = new RoundParser();
    private final DebateEntryFormatter  formatter   = new DebateEntryFormatter();
    private final DebateParser          parser      = new DebateParser();
    private final SummaryProjector      projector   = new SummaryProjector();
    private final SummaryRenderer       renderer    = new SummaryRenderer();

    private String fixture(String name) throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("fixtures/debate/" + name);
        assertThat(url).as("fixture %s not found", name).isNotNull();
        return Files.readString(Path.of(url.toURI()));
    }

    private String debateHeader() {
        return "# Debate Log\n**Spec:** /spec.md\n**Session:** test-session\n";
    }

    @Test
    void round1RoundTrip() throws Exception {
        String snippet  = fixture("round1.md");
        List<DebateEntry> entries = roundParser.parse(snippet);
        // 2 raises (memo excluded)
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> e.type() == EntryType.RAISE);

        // Format to debate.md append block
        String formatted = formatter.format(entries, 1, AgentType.REV, "");
        assertThat(formatted).contains("R1-REV-001").contains("R1-REV-002");

        // Parse back
        String fullDebate = debateHeader() + formatted;
        List<DebateEvent> events = parser.parse(fullDebate);
        long raises = events.stream().filter(e -> e instanceof DebateEvent.RaiseEvent).count();
        assertThat(raises).isEqualTo(2);

        // Project
        ReviewState state = projector.project(events);
        assertThat(state.points()).hasSize(2);
        assertThat(state.points().values()).allMatch(p -> p.currentStatus() == ReviewStatus.OPEN);

        // Render — must not throw and must contain status marker
        String summary = renderer.render(state);
        assertThat(summary).contains("🔴").contains("R1-REV-001");
    }

    @Test
    void round2AppendAndIncrementalProject() throws Exception {
        // Build round 1 debate.md
        String snippet1 = fixture("round1.md");
        List<DebateEntry> entries1 = roundParser.parse(snippet1);
        String debate = debateHeader() + formatter.format(entries1, 1, AgentType.REV, "");

        // Parse round 1 and project
        List<DebateEvent> events1 = parser.parse(debate);
        ReviewState state1 = projector.project(events1);
        assertThat(state1.points()).hasSize(2);

        // Append round 2
        String snippet2 = fixture("round2.md");
        List<DebateEntry> entries2 = roundParser.parse(snippet2);
        debate += formatter.format(entries2, 2, AgentType.IMP, debate);

        // Incremental fold (only process round 2 events)
        List<DebateEvent> allEvents = parser.parse(debate);
        ReviewState state2 = projector.projectIncremental(state1, allEvents, events1.size());

        assertThat(state2.points().get("R1-REV-001").currentStatus()).isEqualTo(ReviewStatus.AGREED);
        assertThat(state2.points().get("R1-REV-002").currentStatus()).isEqualTo(ReviewStatus.ACTIVE);

        // Summary must contain strikethrough for agreed point
        String summary = renderer.render(state2);
        assertThat(summary).contains("~~");  // agreed point strikethrough
        assertThat(summary).contains("🟡");   // active point
    }

    @Test
    void noInformationLostAcrossRoundTrip() throws Exception {
        String snippet = fixture("round1.md");
        List<DebateEntry> entries = roundParser.parse(snippet);
        String formatted = debateHeader() + formatter.format(entries, 1, AgentType.REV, "");
        List<DebateEvent> events = parser.parse(formatted);

        // All raise events must have content
        events.stream()
              .filter(e -> e instanceof DebateEvent.RaiseEvent)
              .map(e -> (DebateEvent.RaiseEvent) e)
              .forEach(r -> assertThat(r.content()).isNotBlank());

        // Entry IDs must be assigned
        events.stream()
              .filter(e -> e instanceof DebateEvent.RaiseEvent)
              .map(e -> (DebateEvent.RaiseEvent) e)
              .forEach(r -> assertThat(r.entryId()).isNotNull().startsWith("R1-REV-"));
    }
}
