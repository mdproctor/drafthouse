package io.casehub.drafthouse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class BrainstormSession {

    public enum State { ACTIVE, CONVERGED, ABANDONED }

    private final String sessionId;
    private final List<BrainstormOption> options = new ArrayList<>();
    private State state;
    private Instant lastActivity;

    public BrainstormSession(String sessionId) {
        this.sessionId = sessionId;
        this.state = State.ACTIVE;
        this.lastActivity = Instant.now();
    }

    public String sessionId() { return sessionId; }
    public State state() { return state; }
    public List<BrainstormOption> options() { return Collections.unmodifiableList(options); }
    public Instant lastActivity() { return lastActivity; }

    public void touch() {
        this.lastActivity = Instant.now();
    }

    public void addOption(BrainstormOption option) {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Cannot add options to a " + state + " session");
        }
        options.add(option);
        touch();
    }

    public Optional<BrainstormOption> findOption(String optionId) {
        return options.stream().filter(o -> o.id().equals(optionId)).findFirst();
    }

    public void markSelected(String optionId) {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("Cannot select in a " + state + " session");
        }
        BrainstormOption option = findOption(optionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown option: " + optionId));
        option.setStatus(BrainstormOption.Status.SELECTED);
        this.state = State.CONVERGED;
        touch();
    }

    public void abandon() {
        if (state == State.CONVERGED) {
            throw new IllegalStateException("Cannot abandon a CONVERGED session");
        }
        this.state = State.ABANDONED;
        touch();
    }
}
