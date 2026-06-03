package io.casehub.drafthouse.debate;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ReviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSessionService.class);
    private static final Path SESSIONS_BASE =
            Path.of(System.getProperty("user.home"), ".drafthouse", "reviews");

    @Inject DebateAgentProvider agentProvider;

    private final DebateParser         parser    = new DebateParser();
    private final SummaryProjector     projector = new SummaryProjector();
    private final SummaryRenderer      renderer  = new SummaryRenderer();
    private final DebateEntryFormatter formatter = new DebateEntryFormatter();

    private final Map<String, ReviewSession> sessions = new ConcurrentHashMap<>();

    public ReviewSession startSession(String specPath) {
        String sessionId  = generateSessionId(specPath);
        Path sessionPath  = SESSIONS_BASE.resolve(sessionId);

        try {
            Files.createDirectories(sessionPath);
            Git.init().setDirectory(sessionPath.toFile()).call().close();

            String header = "# Debate Log\n**Spec:** " + specPath
                    + "\n**Session:** " + sessionId + "\n";
            Files.writeString(sessionPath.resolve("debate.md"), header);
            Files.writeString(sessionPath.resolve("summary.md"), "# Review Summary\n");

            try (Git git = Git.open(sessionPath.toFile())) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("round-0: session open").call();
            }

            ReviewSession session = new ReviewSession(
                    sessionId, sessionPath.toString(), specPath, 0, projector.identity(), 0);
            sessions.put(sessionId, session);
            return session;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new RuntimeException("Git error starting session", e);
        }
    }

    public ReviewSession executeNextRound(String sessionId) {
        ReviewSession session = sessions.get(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);

        Path sessionPath = Path.of(session.sessionPath());
        int  nextRound   = session.roundNumber() + 1;
        // Round 1 = REV, Round 2 = IMP, alternating
        AgentType agent  = (nextRound % 2 == 1) ? AgentType.REV : AgentType.IMP;

        try {
            String specContent   = Files.readString(Path.of(session.specPath()));
            String debateContent = Files.readString(sessionPath.resolve("debate.md"));

            DebateRoundContext ctx = new DebateRoundContext(
                    specContent, debateContent, session.currentState(), nextRound, sessionId);

            List<DebateEntry> entries = agent == AgentType.REV
                    ? agentProvider.executeReviewerRound(ctx)
                    : agentProvider.executeImplementerRound(ctx);

            // Format and append to debate.md
            String appendText = formatter.format(entries, nextRound, agent, debateContent);
            Files.writeString(sessionPath.resolve("debate.md"), appendText,
                    StandardOpenOption.APPEND);

            // Incremental fold: parse all events, apply only new ones
            String updatedDebate = Files.readString(sessionPath.resolve("debate.md"));
            List<DebateEvent> allEvents = parser.parse(updatedDebate);
            ReviewState newState = projector.projectIncremental(
                    session.currentState(), allEvents, session.foldedEventCount());
            int newCursor = allEvents.size();

            // Rewrite summary.md
            Files.writeString(sessionPath.resolve("summary.md"), renderer.render(newState));

            // Commit both files
            try (Git git = Git.open(sessionPath.toFile())) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("round-" + nextRound + ": " + agent.name()).call();
            }

            ReviewSession updated = new ReviewSession(
                    sessionId, session.sessionPath(), session.specPath(),
                    nextRound, newState, newCursor);
            sessions.put(sessionId, updated);
            return updated;

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GitAPIException e) {
            throw new RuntimeException("Git error committing round " + nextRound, e);
        }
    }

    private String generateSessionId(String specPath) {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        byte[] bytes = new byte[3];
        new SecureRandom().nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return "drafthouse-" + date + "-" + hex;
    }
}
