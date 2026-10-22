package io.casehub.drafthouse.debate;

import io.casehub.agency.communication.dispatch.ChannelMessageMeta;
import io.casehub.agency.deliberation.conversation.ConversationProtocol;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL;
import static org.assertj.core.api.Assertions.assertThat;

class ThreadStreamEntryTest {

    @Test
    void from_startMessage_parsesAllFields() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", "t-123");
        meta.put("threadAction", "START");
        meta.put(ConversationProtocol.ROLE, "REV");
        meta.put("side", "A");
        meta.put("startLine", "10");
        meta.put("endLine", "15");
        meta.put("selectedText", "hello world");
        String encoded = ChannelMessageMeta.encode(META_SENTINEL, meta, "My comment");

        ThreadStreamEntry entry = ThreadStreamEntry.from(encoded, "sender-1");

        assertThat(entry).isNotNull();
        assertThat(entry.threadId()).isEqualTo("t-123");
        assertThat(entry.threadAction()).isEqualTo("START");
        assertThat(entry.agentRole()).isEqualTo("REV");
        assertThat(entry.content()).isEqualTo("My comment");
        assertThat(entry.anchor()).isNotNull();
        assertThat(entry.anchor().side()).isEqualTo("A");
        assertThat(entry.anchor().startLine()).isEqualTo(10);
        assertThat(entry.anchor().endLine()).isEqualTo(15);
        assertThat(entry.anchor().selectedText()).isEqualTo("hello world");
    }

    @Test
    void from_replyMessage_noAnchor() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", "t-123");
        meta.put("threadAction", "REPLY");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        String encoded = ChannelMessageMeta.encode(META_SENTINEL, meta, "Great point");

        ThreadStreamEntry entry = ThreadStreamEntry.from(encoded, "sender-2");

        assertThat(entry).isNotNull();
        assertThat(entry.threadAction()).isEqualTo("REPLY");
        assertThat(entry.anchor()).isNull();
        assertThat(entry.content()).isEqualTo("Great point");
    }

    @Test
    void from_resolveMessage() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", "t-123");
        meta.put("threadAction", "RESOLVE");
        meta.put(ConversationProtocol.ROLE, "HUMAN");
        String encoded = ChannelMessageMeta.encode(META_SENTINEL, meta, "");

        ThreadStreamEntry entry = ThreadStreamEntry.from(encoded, "sender-1");

        assertThat(entry).isNotNull();
        assertThat(entry.threadAction()).isEqualTo("RESOLVE");
    }

    @Test
    void from_nonThreadMessage_returnsNull() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(ConversationProtocol.ENTRY_TYPE, "RAISE");
        meta.put(ConversationProtocol.ROLE, "REV");
        meta.put(ConversationProtocol.ROUND, "1");
        meta.put(ConversationProtocol.PRIORITY, "HIGH");
        meta.put(ConversationProtocol.SCOPE, "ISOLATED");
        String encoded = ChannelMessageMeta.encode(META_SENTINEL, meta, "debate point");

        ThreadStreamEntry entry = ThreadStreamEntry.from(encoded, "sender-1");

        assertThat(entry).isNull();
    }

    @Test
    void from_missingThreadAction_returnsNull() {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("threadId", "t-123");
        meta.put(ConversationProtocol.ROLE, "REV");
        String encoded = ChannelMessageMeta.encode(META_SENTINEL, meta, "no action");

        ThreadStreamEntry entry = ThreadStreamEntry.from(encoded, "sender-1");

        assertThat(entry).isNull();
    }
}
