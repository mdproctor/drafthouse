package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;

import java.util.Collections;
import java.util.Map;
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
    private final ConcurrentHashMap<AgentType, String> participants = new ConcurrentHashMap<>();
    private final String specPath;
    private final ContextTracker contextTracker = new ContextTracker();
    private volatile SelectionScope currentSelection;

    public DebateSession(final UUID channelId, final String debateSessionId,
                         final String channelName, final String specPath) {
        this.channelId       = channelId;
        this.debateSessionId = debateSessionId;
        this.channelName     = channelName;
        this.specPath        = specPath;
    }

    /**
     * Derives the Qhorus instance ID for a role in a session.
     * Single source of truth for the naming convention — use at every call site.
     */
    public static String instanceId(final AgentType role, final String debateSessionId) {
        return "drafthouse-" + role.name().toLowerCase() + "-" + debateSessionId;
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
    public String specPath()        { return specPath; }
    public ContextTracker contextTracker() { return contextTracker; }

    public void updateSelection(SelectionScope selection) {
        this.currentSelection = selection;
    }

    public SelectionScope currentSelection() {
        return currentSelection;
    }
}
