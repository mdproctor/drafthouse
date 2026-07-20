package io.casehub.drafthouse.debate;

import io.casehub.drafthouse.DebateSession;
import io.casehub.drafthouse.WebSocketEventBus;
import io.casehub.qhorus.runtime.message.MessageService;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkspaceWatcher implements Closeable {

    private static final Logger LOG = Logger.getLogger(WorkspaceWatcher.class.getName());
    private static final Pattern RESPONSE_FILE = Pattern.compile(
            "(reviewer|implementor)-(\\d+)\\.(md|jsonl)$");

    private final WorkspaceReplayAdapter adapter;
    private final WebSocketEventBus eventBus;
    private final DebateSession session;
    private final MessageService messageService;
    private final String tenancyId;
    private final Runnable onComplete;

    private DirectoryWatcher directoryWatcher;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Set<String> processedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> existingIssueIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> raiseMessageIds = new ConcurrentHashMap<>();
    private volatile long lastMessageId;
    private volatile int lastReplayedRound;
    private volatile long progressLogOffset;
    private String projectRepoPath;
    private String specPath;
    private Path workspacePath;

    public WorkspaceWatcher(WorkspaceReplayAdapter adapter,
                            WebSocketEventBus eventBus,
                            DebateSession session,
                            MessageService messageService,
                            String tenancyId,
                            Runnable onComplete) {
        this.adapter = adapter;
        this.eventBus = eventBus;
        this.session = session;
        this.messageService = messageService;
        this.tenancyId = tenancyId;
        this.onComplete = onComplete;
    }

    public void start(Path workspacePath, int startFromRound,
                      Set<String> existingIssueIds,
                      Map<String, Long> raiseMessageIds,
                      long lastMessageId,
                      String projectRepoPath, String specPath) throws IOException {
        this.workspacePath = workspacePath;
        this.lastReplayedRound = startFromRound;
        this.existingIssueIds.addAll(existingIssueIds);
        this.raiseMessageIds.putAll(raiseMessageIds);
        this.lastMessageId = lastMessageId;
        this.projectRepoPath = projectRepoPath;
        this.specPath = specPath;

        Path progressLog = workspacePath.resolve("progress.log");
        this.progressLogOffset = Files.exists(progressLog) ? Files.size(progressLog) : 0;

        // Mark already-processed files so catch-up doesn't re-dispatch
        markExistingFiles(workspacePath.resolve("responses"), startFromRound);

        this.directoryWatcher = DirectoryWatcher.builder()
                .path(workspacePath)
                .listener(this::onEvent)
                .build();
        this.directoryWatcher.watchAsync();

        catchUpReconciliation();
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        if (stopped.compareAndSet(false, true) && directoryWatcher != null) {
            try {
                directoryWatcher.close();
            } catch (IOException e) {
                LOG.warning("Failed to close DirectoryWatcher: " + e.getMessage());
            }
        }
    }

    private void markExistingFiles(Path responsesDir, int upToRound) {
        if (!Files.isDirectory(responsesDir)) return;
        for (int n = 1; n <= upToRound; n++) {
            if (Files.exists(responsesDir.resolve("reviewer-" + n + ".md"))
                    || Files.exists(responsesDir.resolve("reviewer-" + n + ".jsonl"))) {
                processedFiles.add("reviewer-" + n);
            }
            if (Files.exists(responsesDir.resolve("implementor-" + n + ".md"))
                    || Files.exists(responsesDir.resolve("implementor-" + n + ".jsonl"))) {
                processedFiles.add("implementor-" + n);
            }
        }
    }

    private void catchUpReconciliation() {
        Path responsesDir = workspacePath.resolve("responses");
        if (!Files.isDirectory(responsesDir)) return;

        int maxRound = WorkspaceParser.discoverMaxRound(responsesDir);
        for (int n = lastReplayedRound + 1; n <= maxRound; n++) {
            processReviewerFile(responsesDir, n);
            processImplementorFile(responsesDir, n);
        }
        // Handle partial round where reviewer exists but implementor doesn't
        if (maxRound > lastReplayedRound) {
            String reviewerStem = "reviewer-" + maxRound;
            if (!processedFiles.contains(reviewerStem)) {
                if (Files.exists(responsesDir.resolve(reviewerStem + ".md"))
                        || Files.exists(responsesDir.resolve(reviewerStem + ".jsonl"))) {
                    processReviewerFile(responsesDir, maxRound);
                }
            }
        }
    }

    private void onEvent(DirectoryChangeEvent event) {
        if (stopped.get()) {return;}
        if (event.eventType() != DirectoryChangeEvent.EventType.CREATE
            && event.eventType() != DirectoryChangeEvent.EventType.MODIFY) {return;}

        Path   path     = event.path();
        String fileName = path.getFileName().toString();

        if (fileName.equals("progress.log")) {
            tailProgressLog();
            return;
        }

        Matcher m = RESPONSE_FILE.matcher(fileName);
        if (!m.matches()) {return;}

        String role         = m.group(1);
        int    roundNum     = Integer.parseInt(m.group(2));
        Path   responsesDir = path.getParent();

        var rc = io.quarkus.arc.Arc.container().requestContext();
        rc.activate();
        try {
            if ("reviewer".equals(role)) {
                processReviewerFile(responsesDir, roundNum);
            } else {
                processImplementorFile(responsesDir, roundNum);
            }
        } finally {
            rc.deactivate();
        }}

    private void processReviewerFile(Path responsesDir, int roundNum) {
        String stem = "reviewer-" + roundNum;
        if (!processedFiles.add(stem)) {return;}

        if (!waitForFile(responsesDir, stem)) {
            processedFiles.remove(stem);
            return;
        }

        try {
            boolean hasJsonl = Files.exists(responsesDir.resolve(stem + ".jsonl"));
            WorkspaceParser.ParsedRound round = hasJsonl
                                                ? WorkspaceParser.parseRoundFromJsonl(responsesDir, roundNum)
                                                : WorkspaceParser.parseRoundFromMarkdown(
                    responsesDir, roundNum, existingIssueIds);

            String revSender = session.instanceIdFor(AgentType.REV);
            UUID   channelId = session.channelId();

            int count = 0;
            count += adapter.dispatchIssues(channelId, revSender, round, raiseMessageIds);
            count += adapter.dispatchConfirmations(channelId, revSender, round, raiseMessageIds);
            count += adapter.dispatchMemos(channelId, revSender, round.roundNumber(),
                                           round.assumptions(), round.settledDecisions());

            round.issues().forEach(i -> existingIssueIds.add(i.issueId()));

            if (count > 0) {pushNewEntries();}

            LOG.info("Watcher processed " + stem + ": " + count + " entries dispatched");
        } catch (Exception e) {
            LOG.warning("Failed to process " + stem + ": " + e.getMessage());
        }}

    private void processImplementorFile(Path responsesDir, int roundNum) {
        String stem = "implementor-" + roundNum;
        if (!processedFiles.add(stem)) {return;}

        if (!waitForFile(responsesDir, stem)) {
            processedFiles.remove(stem);
            return;
        }

        try {
            boolean hasJsonl = Files.exists(responsesDir.resolve(stem + ".jsonl"));
            WorkspaceParser.ParsedRound round = hasJsonl
                                                ? WorkspaceParser.parseRoundFromJsonl(responsesDir, roundNum)
                                                : WorkspaceParser.parseRoundFromMarkdown(
                    responsesDir, roundNum, existingIssueIds);

            String impSender = session.instanceIdFor(AgentType.IMP);
            UUID   channelId = session.channelId();

            int count = 0;
            count += adapter.dispatchResponses(channelId, impSender, round, raiseMessageIds);

            if (count > 0) {pushNewEntries();}

            lastReplayedRound = roundNum;

            LOG.info("Watcher processed " + stem + ": " + count + " entries dispatched");
        } catch (Exception e) {
            LOG.warning("Failed to process " + stem + ": " + e.getMessage());
        }}

    private boolean waitForFile(Path dir, String stem) {
        Path md = dir.resolve(stem + ".md");
        Path jsonl = dir.resolve(stem + ".jsonl");
        for (int i = 0; i < 3; i++) {
            if (Files.exists(jsonl)) return true;
            if (Files.exists(md)) {
                try {
                    String content = Files.readString(md);
                    if (content.contains("SIGNAL:")) return true;
                } catch (IOException ignored) {}
            }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Files.exists(jsonl) || Files.exists(md);
    }

    private void pushNewEntries() {
        UUID channelId = session.channelId();
        var newMessages = messageService.pollAfter(channelId, lastMessageId, Integer.MAX_VALUE);
        var newEntries = newMessages.stream()
                .map(DebateStreamEntry::from).filter(Objects::nonNull).toList();
        if (!newEntries.isEmpty()) {
            eventBus.pushDebateEntries(channelId, newEntries);
            lastMessageId = newMessages.get(newMessages.size() - 1).id();
        }
    }

    private void tailProgressLog() {
        Path logPath = workspacePath.resolve("progress.log");
        if (!Files.exists(logPath)) return;

        try {
            long fileSize = Files.size(logPath);
            if (fileSize <= progressLogOffset) return;

            String newContent;
            try (var raf = new RandomAccessFile(logPath.toFile(), "r")) {
                raf.seek(progressLogOffset);
                byte[] bytes = new byte[(int) (fileSize - progressLogOffset)];
                raf.readFully(bytes);
                newContent = new String(bytes);
            }
            progressLogOffset = fileSize;

            for (String line : newContent.split("\n")) {
                var event = ProgressLogParser.parse(line.trim());
                if (event == null) continue;

                Map<String, Object> payload = toPayload(event);
                eventBus.pushMetadata(session.channelId(), "workspace-progress", payload);

                if (event instanceof ProgressLogParser.ReviewTerminal terminal) {
                    LOG.info("Watcher detected terminal state: " + terminal.finalState());
                    stop();
                    onComplete.run();
                    return;
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to tail progress.log: " + e.getMessage());
        }
    }

    private static Map<String, Object> toPayload(ProgressLogParser.ProgressEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (event) {
            case ProgressLogParser.AgentStart s -> {
                payload.put("type", "AGENT_START");
                payload.put("agent", s.agent());
                payload.put("cached", s.cached());
            }
            case ProgressLogParser.AgentStatus s -> {
                payload.put("type", "AGENT_STATUS");
                payload.put("agent", s.agent());
                payload.put("elapsed", s.elapsedSeconds());
                payload.put("message", s.message());
            }
            case ProgressLogParser.AgentComplete c -> {
                payload.put("type", "AGENT_COMPLETE");
                payload.put("agent", c.agent());
                payload.put("cost", c.cost());
            }
            case ProgressLogParser.IssuesRaised r -> {
                payload.put("type", "ISSUES_RAISED");
                payload.put("count", r.count());
            }
            case ProgressLogParser.RoundComplete r -> {
                payload.put("type", "ROUND_COMPLETE");
                payload.put("round", r.round());
                payload.put("cost", r.roundCost());
                payload.put("cumulativeCost", r.cumulativeCost());
            }
            case ProgressLogParser.ReviewTerminal t -> {
                payload.put("type", "REVIEW_TERMINAL");
                payload.put("finalState", t.finalState());
            }
        }
        return payload;
    }
}
