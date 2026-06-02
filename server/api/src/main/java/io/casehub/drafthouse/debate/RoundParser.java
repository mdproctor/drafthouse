package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.List;

public class RoundParser {

    public List<DebateEntry> parse(String roundSnippet) {
        List<DebateEntry> entries = new ArrayList<>();
        String[] blocks = roundSnippet.split("(?m)^\\s*$");
        for (String block : blocks) {
            block = block.strip();
            if (block.isEmpty() || block.toUpperCase().startsWith("MEMO:")) continue;
            DebateEntry entry = parseBlock(block);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private DebateEntry parseBlock(String block) {
        String type = null;
        String target = null;
        String content = null;
        String priority = null;
        String scope = null;
        String location = null;
        String status = null;

        for (String line : block.lines().toList()) {
            String upper = line.stripLeading().toUpperCase();
            if (upper.startsWith("TYPE:"))     type     = value(line);
            else if (upper.startsWith("TARGET:"))   target   = value(line);
            else if (upper.startsWith("CONTENT:"))  content  = value(line);
            else if (upper.startsWith("PRIORITY:")) priority = value(line);
            else if (upper.startsWith("SCOPE:"))    scope    = value(line);
            else if (upper.startsWith("LOCATION:")) location = value(line);
            else if (upper.startsWith("STATUS:"))   status   = value(line);
        }

        if (type == null || content == null) return null;

        EntryType entryType = switch (type.toUpperCase().replace("-", "_")) {
            case "RAISE"      -> EntryType.RAISE;
            case "AGREE"      -> EntryType.AGREE;
            case "DISPUTE"    -> EntryType.DISPUTE;
            case "QUALIFY"    -> EntryType.QUALIFY;
            case "FLAG_HUMAN" -> EntryType.FLAG_HUMAN;
            default           -> null;
        };
        if (entryType == null) return null;

        Priority p = null;
        if (priority != null) {
            try { p = Priority.valueOf(priority.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }
        Scope s = null;
        if (scope != null) {
            try { s = Scope.valueOf(scope.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }
        ReviewStatus rs = status != null ? parseStatus(status) : null;

        return new DebateEntry(entryType, target, content, rs, p, s, location);
    }

    private String value(String line) {
        int colon = line.indexOf(':');
        return colon >= 0 ? line.substring(colon + 1).strip() : null;
    }

    private ReviewStatus parseStatus(String s) {
        return switch (s.toUpperCase().replace(" ", "_")) {
            case "AGREED"         -> ReviewStatus.AGREED;
            case "ACTIVE"         -> ReviewStatus.ACTIVE;
            case "OPEN"           -> ReviewStatus.OPEN;
            case "PENDING_HUMAN"  -> ReviewStatus.PENDING_HUMAN;
            default               -> null;
        };
    }
}
