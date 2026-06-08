package io.casehub.drafthouse.debate;

/**
 * Protocol constants shared by DebateMcpTools and DebateChannelProjection.
 *
 * META_SENTINEL prefixes every structured debate message written by DebateMcpTools.
 * The SOH byte (U+0001) guarantees no LLM-generated content ever begins with this sequence,
 * eliminating ambiguity between structured headers and plain debate body text.
 */
public final class DebateProtocol {

    /** SOH (U+0001) + "DHMETA:" — length 8, invisible to LLMs, identifiable in hex dumps. */
    public static final String META_SENTINEL = "\u0001DHMETA:";

    private DebateProtocol() {}
}
