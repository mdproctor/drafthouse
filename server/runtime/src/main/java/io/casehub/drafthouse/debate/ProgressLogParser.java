package io.casehub.drafthouse.debate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProgressLogParser {

    public sealed interface ProgressEvent {}
    public record AgentStart(String agent, boolean cached) implements ProgressEvent {}
    public record AgentStatus(String agent, int elapsedSeconds, String message) implements ProgressEvent {}
    public record AgentComplete(String agent, double cost) implements ProgressEvent {}
    public record IssuesRaised(int count) implements ProgressEvent {}
    public record RoundComplete(int round, double roundCost, double cumulativeCost) implements ProgressEvent {}
    public record ReviewTerminal(String finalState) implements ProgressEvent {}

    private static final Pattern AGENT_START = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+(Reviewer|Implementor)\\s+\\((fresh session|continued .*cached.*)\\)");
    private static final Pattern AGENT_STATUS = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+\\[(\\d+)s]\\s+(reviewer|implementor):\\s+(.+)");
    private static final Pattern AGENT_COMPLETE = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+(Reviewer|Implementor)\\s+done\\s+\\(\\$(\\d+\\.\\d+)\\)");
    private static final Pattern ISSUES_RAISED = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+(\\d+)\\s+new\\s+issue\\(s\\)\\s+raised");
    private static final Pattern ROUND_COMPLETE = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+Round\\s+(\\d+)\\s+complete\\s+.+~\\$(\\d+\\.\\d+)/round,\\s+\\$(\\d+\\.\\d+)\\s+cumulative");
    private static final Pattern TERMINAL = Pattern.compile(
            "REVIEW\\s+(DONE|PAUSED|FAILED|ABORTED|CRASHED|INTERRUPTED)\\b");

    private ProgressLogParser() {}

    public static ProgressEvent parse(String line) {
        if (line == null || line.isBlank()) return null;

        Matcher m;

        m = TERMINAL.matcher(line);
        if (m.find()) return new ReviewTerminal(m.group(1));

        m = AGENT_START.matcher(line);
        if (m.find()) return new AgentStart(m.group(1).toLowerCase(), m.group(2).contains("cached"));

        m = AGENT_STATUS.matcher(line);
        if (m.find()) return new AgentStatus(m.group(2), Integer.parseInt(m.group(1)), m.group(3).trim());

        m = AGENT_COMPLETE.matcher(line);
        if (m.find()) return new AgentComplete(m.group(1).toLowerCase(), Double.parseDouble(m.group(2)));

        m = ISSUES_RAISED.matcher(line);
        if (m.find()) return new IssuesRaised(Integer.parseInt(m.group(1)));

        m = ROUND_COMPLETE.matcher(line);
        if (m.find()) return new RoundComplete(
                Integer.parseInt(m.group(1)),
                Double.parseDouble(m.group(2)),
                Double.parseDouble(m.group(3)));

        return null;
    }

    public static boolean isTerminal(String line) {
        return line != null && TERMINAL.matcher(line).find();
    }

    public static String terminalState(String line) {
        if (line == null) return null;
        Matcher m = TERMINAL.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
