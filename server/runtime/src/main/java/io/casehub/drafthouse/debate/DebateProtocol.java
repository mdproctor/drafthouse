package io.casehub.drafthouse.debate;

import java.util.Map;

import io.casehub.blocks.channel.ChannelMessageMeta;

/**
 * DraftHouse-specific sentinel and convenience methods.
 * Delegates to {@link ChannelMessageMeta} for the format-level work.
 */
public final class DebateProtocol {

    public static final String META_SENTINEL = "DHMETA:";

    private DebateProtocol() {}

    public static Map<String, String> parseMeta(String content) {
        return ChannelMessageMeta.parseMeta(META_SENTINEL, content);
    }

    public static int parseRound(Map<String, String> meta) {
        return ChannelMessageMeta.parseInt(meta, "round");
    }

    public static String bodyContent(String content) {
        return ChannelMessageMeta.bodyContent(META_SENTINEL, content);
    }
}
