package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Live state for an active debate session.
 *
 * A record implies immutability; a live session with dynamic participants is not a value
 * type — participants join over the session's lifetime via lazy registration.
 *
 * The participants map starts empty and is populated via registerIfAbsent() as roles post.
 * REV and IMP are registered eagerly by start_debate; other roles register on first use.
 */
public class DebateSession {

    private final UUID channelId;
    private final String debateSessionId;
    private final String channelName;
    private final String agentId;
    private final ConcurrentHashMap<AgentType, String> participants = new ConcurrentHashMap<>();
    private final DocumentSet documentSet;
    private final ContextTracker contextTracker = new ContextTracker();
    private volatile SelectionScope currentSelection;

    public DebateSession(final UUID channelId, final String debateSessionId,
                         final String channelName, final String agentId) {
        this.channelId       = channelId;
        this.debateSessionId = debateSessionId;
        this.channelName     = channelName;
        this.agentId         = agentId;
        this.documentSet     = new DocumentSet();
    }

    public DebateSession(final UUID channelId, final String debateSessionId,
                         final String channelName, final String agentId, final DocumentSet documentSet) {
        this.channelId       = channelId;
        this.debateSessionId = debateSessionId;
        this.channelName     = channelName;
        this.agentId         = agentId;
        this.documentSet     = documentSet;
    }

    /**
     * Derives the Qhorus instance ID for a role in a session.
     * Single source of truth for the naming convention — use at every call site.
     */
    public static String instanceId(final AgentType role, final String debateSessionId) {
        return "drafthouse-" + role.name().toLowerCase() + "-" + debateSessionId;
    }

    /**
     * Creates a new session branched from an existing session.
     * Copies documents and comparison from the source session.
     */
    public static DebateSession branchFrom(final DebateSession source, final UUID channelId,
                                           final String sessionId, final String channelName) {
        return new DebateSession(channelId, sessionId, channelName, source.agentId, DocumentSet.copyOf(source.documentSet));
    }

    /**
     * Reconstitutes a live session from a snapshot.
     *
     * <p>ContextTracker and SelectionScope are ephemeral — initialized to defaults.
     * Document state and participants are restored from the snapshot.
     */
    public static DebateSession fromSnapshot(DebateSessionSnapshot snapshot) {
        DocumentSet ds = new DocumentSet();
        for (DocumentEntry e : snapshot.documents()) {
            ds.add(e.path(), e.label());
        }
        if (snapshot.comparison() != null) {
            ds.setComparison(snapshot.comparison().pathA(), snapshot.comparison().pathB());
        }
        DebateSession session = new DebateSession(
                snapshot.channelId(), snapshot.debateSessionId(),
                snapshot.channelName(), snapshot.agentId(), ds);
        for (var entry : snapshot.participants().entrySet()) {
            session.registerIfAbsent(entry.getKey(), entry::getValue);
        }
        return session;
    }

    /**
     * Atomically registers a role's instance on first use.
     *
     * <p>Success path: the supplier is called exactly once per role; its return value is stored
     * atomically. Subsequent calls return the stored value without invoking the supplier.
     *
     * <p>Exception path: if the supplier throws, {@link ConcurrentHashMap#computeIfAbsent}
     * does not store a value — the key remains absent and the next call will retry the supplier.
     * Retry is safe because {@code InstanceService.register()} is an upsert (idempotent).
     */
    public String registerIfAbsent(final AgentType role, final Supplier<String> registration) {
        return participants.computeIfAbsent(role, r -> registration.get());
    }

    /** Returns the stored instance ID for a role, or null if not yet registered. */
    public String instanceIdFor(final AgentType role) {
        return participants.get(role);
    }

    /** Returns an unmodifiable view of the current participants map. */
    public Map<AgentType, String> participants() {
        return Collections.unmodifiableMap(participants);
    }

    public UUID channelId()         { return channelId; }
    public String debateSessionId() { return debateSessionId; }
    public String channelName()     { return channelName; }
    public String agentId()         { return agentId; }
    public String primaryPath()     { return documentSet.primary().map(DocumentEntry::path).orElse(null); }
    public ContextTracker contextTracker() { return contextTracker; }

    public void updateSelection(SelectionScope selection) {
        this.currentSelection = selection;
    }

    public SelectionScope currentSelection() {
        return currentSelection;
    }

    // ── Document operations ───────────────────────────────────────────────

    /**
     * Adds a document to the set.
     * @return true if added, false if path already exists
     */
    public boolean addDocument(final String path, final String label) {
        return documentSet.add(path, label);
    }

    /**
     * Removes a document from the set.
     * @return true if comparison was cleared as a side effect, false otherwise
     * @throws IllegalArgumentException if path is the primary document or not found
     */
    public boolean removeDocument(final String path) {
        synchronized (documentSet) {
            // Check if it's the primary document
            Optional<DocumentEntry> primary = documentSet.primary();
            if (primary.isPresent() && primary.get().path().equals(path)) {
                throw new IllegalArgumentException("cannot remove primary document: " + path);
            }

            // Check if path exists
            boolean exists = documentSet.documents().stream()
                    .anyMatch(d -> d.path().equals(path));
            if (!exists) {
                throw new IllegalArgumentException("path not in document set: " + path);
            }

            // Capture comparison before removal
            ComparisonPair before = documentSet.currentComparison();

            // Remove the document
            documentSet.remove(path);

            // Capture comparison after removal
            ComparisonPair after = documentSet.currentComparison();

            // Return whether comparison was cleared
            return before != null && after == null;
        }
    }

    /**
     * Sets the comparison pair.
     * @throws IllegalArgumentException if either path is not in the document set
     */
    public void setComparison(final String pathA, final String pathB) {
        synchronized (documentSet) {
            List<DocumentEntry> docs = documentSet.documents();
            boolean hasA = docs.stream().anyMatch(d -> d.path().equals(pathA));
            boolean hasB = docs.stream().anyMatch(d -> d.path().equals(pathB));

            if (!hasA || !hasB) {
                throw new IllegalArgumentException("both paths must exist in document set");
            }

            documentSet.setComparison(pathA, pathB);
        }
    }

    /** Clears the current comparison. */
    public void clearComparison() {
        documentSet.clearComparison();
    }

    /** Returns the current comparison pair, or null if none set. */
    public ComparisonPair currentComparison() {
        return documentSet.currentComparison();
    }

    /** Returns an unmodifiable list of all documents. */
    public List<DocumentEntry> documents() {
        return documentSet.documents();
    }

    /** Returns the primary (first) document, or empty if no documents. */
    public Optional<DocumentEntry> primary() {
        return documentSet.primary();
    }

    /**
     * Captures durable state for persistence.
     *
     * <p>Document state is captured atomically under the DocumentSet lock.
     * Participant state is read separately from the ConcurrentHashMap.
     * The snapshot is effectively consistent because document and participant
     * mutations happen in different MCP tool methods.
     *
     * <p>Ephemeral state (ContextTracker, SelectionScope) is excluded.
     */
    public DebateSessionSnapshot snapshot() {
        List<DocumentEntry> docs;
        ComparisonPair comp;
        synchronized (documentSet) {
            docs = documentSet.documents();
            comp = documentSet.currentComparison();
        }
        return new DebateSessionSnapshot(
                channelId, debateSessionId, channelName,
                docs, comp, Map.copyOf(participants), agentId);
    }
}
