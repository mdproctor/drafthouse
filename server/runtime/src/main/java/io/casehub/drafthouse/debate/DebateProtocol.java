package io.casehub.drafthouse.debate;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Protocol constants and utilities shared by DebateMcpTools and DebateChannelProjection.
 *
 * META_SENTINEL prefixes every structured debate message written by DebateMcpTools.
 * The SOH byte (U+0001) guarantees no LLM-generated content ever begins with this sequence,
 * eliminating ambiguity between structured headers and plain debate body text.
 */
public final class DebateProtocol {

    /** SOH (U+0001) + "DHMETA:" — length 8, invisible to LLMs, identifiable in hex dumps. */
    public static final String META_SENTINEL = "DHMETA:";

    private static final Logger LOG = Logger.getLogger(DebateProtocol.class.getName());

    private DebateProtocol() {}

    /**
     * Parse META sentinel header from encoded message content.
     * Format: META_SENTINEL + "key=value|key=value\n\n&lt;body&gt;"
     * Returns empty map if sentinel absent (plain content — not an error).
     */
    public static Map<String, String> parseMeta(String content) {
        Map<String, String> map = new HashMap<>();
        if (content == null || content.isBlank() || !content.startsWith(META_SENTINEL)) return map;
        int headerEnd = content.indexOf("\n\n");
        String headerLine = headerEnd > 0
                ? content.substring(META_SENTINEL.length(), headerEnd)
                : content.substring(META_SENTINEL.length());
        for (String part : headerLine.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        if (map.get("entryType") == null && !map.isEmpty()) {
            LOG.warning("Structured debate message (sentinel present) has no entryType. Header: "
                    + headerLine.substring(0, Math.min(80, headerLine.length())));
        }
        return map;
    }

    /**
     * Strip the sentinel header and return only the human-readable body.
     * Returns content unchanged if sentinel absent.
     * Returns null if input is null.
     */
    public static String bodyContent(String content) {
        if (content == null || !content.startsWith(META_SENTINEL)) return content;
        int headerEnd = content.indexOf("\n\n");
        return headerEnd > 0 ? content.substring(headerEnd + 2) : "";
    }
}
