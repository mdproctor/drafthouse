package io.casehub.drafthouse.debate;

import static io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.blocks.conversation.Priority;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.Message;

class DebateStreamEntryTest {

    private static Message makeMessage(String content, String correlationId,
                                       Long inReplyTo, MessageType type) {
        Message m = new Message();
        m.id = 42L;
        m.channelId = UUID.randomUUID();
        m.sender = "drafthouse-rev-abc123";
        m.messageType = type;
        m.actorType = ActorType.AGENT;
        m.content = content;
        m.correlationId = correlationId;
        m.inReplyTo = inReplyTo;
        m.createdAt = Instant.parse("2026-06-11T10:00:00Z");
        return m;
    }

    // ── role wire key ────────────────────────────────────────────────────────

    @Test
    void from_raise_withRoleKey_extractsAllFields() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=1"
                + "|priority=HIGH|scope=ISOLATED|location=§3.2\n\nThe API is ambiguous.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.RAISE);
        assertThat(entry.agentRole()).isEqualTo(AgentType.REV);
        assertThat(entry.round()).isEqualTo(1);
        assertThat(entry.content()).isEqualTo("The API is ambiguous.");
        assertThat(entry.pointId()).isEqualTo("point-1");
        assertThat(entry.subTaskId()).isNull();
        assertThat(entry.priority()).isEqualTo(Priority.HIGH);
        assertThat(entry.scope()).isEqualTo("ISOLATED");
        assertThat(entry.location()).isEqualTo("§3.2");
        assertThat(entry.sender()).isEqualTo("drafthouse-rev-abc123");
        assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-06-11T10:00:00Z"));
    }

    @Test
    void from_raise_withLegacyAgentKey_fallsBack() {
        // Legacy "agent" key still works during transition
        String content = META_SENTINEL + "entryType=RAISE|agent=REV|round=1"
                + "|priority=HIGH|scope=ISOLATED|location=§3.2\n\nThe API is ambiguous.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.agentRole()).isEqualTo(AgentType.REV);
    }

    // ── priority wire values ─────────────────────────────────────────────────

    @Test
    void from_raise_legacyP1Priority_mapsToHigh() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=1"
                + "|priority=P1|scope=ISOLATED\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.priority()).isEqualTo(Priority.HIGH);
    }

    @Test
    void from_raise_legacyP2Priority_mapsToMedium() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=1"
                + "|priority=P2|scope=ISOLATED\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg).priority()).isEqualTo(Priority.MEDIUM);
    }

    @Test
    void from_raise_legacyP3Priority_mapsToLow() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=1"
                + "|priority=P3|scope=ISOLATED\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg).priority()).isEqualTo(Priority.LOW);
    }

    @Test
    void from_raise_newHighPriority_mapsToHigh() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=1"
                + "|priority=HIGH|scope=ISOLATED\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg).priority()).isEqualTo(Priority.HIGH);
    }

    // ── scope is now String ──────────────────────────────────────────────────

    @Test
    void from_raise_scopeIsRawString() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=1"
                + "|priority=HIGH|scope=SYSTEMIC\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.scope()).isEqualTo("SYSTEMIC");
    }

    // ── agree / sub-tasks ────────────────────────────────────────────────────

    @Test
    void from_agree_setsPointIdFromCorrelationId() {
        String content = META_SENTINEL + "entryType=AGREE|role=IMP|round=2\n\nCorrect.";
        Message msg = makeMessage(content, "point-1", 10L, MessageType.DONE);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.AGREE);
        assertThat(entry.agentRole()).isEqualTo(AgentType.IMP);
        assertThat(entry.pointId()).isEqualTo("point-1");
        assertThat(entry.subTaskId()).isNull();
    }

    @Test
    void from_subTaskRequest_extractsPointIdFromMeta_subTaskIdFromCorrelationId() {
        String content = META_SENTINEL + "entryType=SUB_TASK_REQUEST|role=REV"
                + "|taskType=VERIFY|subTaskId=sub-1|round=3|pointId=point-1\n\nVerify this.";
        Message msg = makeMessage(content, "sub-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.SUB_TASK_REQUEST);
        assertThat(entry.pointId()).isEqualTo("point-1");
        assertThat(entry.subTaskId()).isEqualTo("sub-1");
    }

    @Test
    void from_subTaskError_pointIdIsNull() {
        String content = META_SENTINEL + "entryType=SUB_TASK_ERROR|role=REV"
                + "|taskType=VERIFY|subTaskId=sub-1\n\nAgent timed out.";
        Message msg = makeMessage(content, "sub-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.SUB_TASK_ERROR);
        assertThat(entry.pointId()).isNull();
        assertThat(entry.subTaskId()).isEqualTo("sub-1");
    }

    @Test
    void from_memo_bothIdsNull() {
        String content = META_SENTINEL + "entryType=MEMO|role=REV|round=2\n\nMy reasoning.";
        Message msg = makeMessage(content, null, null, MessageType.STATUS);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.MEMO);
        assertThat(entry.pointId()).isNull();
        assertThat(entry.subTaskId()).isNull();
    }

    // ── edge cases ───────────────────────────────────────────────────────────

    @Test
    void from_noMetaSentinel_returnsNull() {
        Message msg = makeMessage("plain text no sentinel", "c-1", null, MessageType.QUERY);
        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_unknownEntryType_returnsNull() {
        String content = META_SENTINEL + "entryType=UNKNOWN_TYPE|role=REV|round=1\n\nBody.";
        Message msg = makeMessage(content, "c-1", null, MessageType.QUERY);
        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_missingRole_returnsNull() {
        String content = META_SENTINEL + "entryType=RAISE|round=1\n\nBody.";
        Message msg = makeMessage(content, "c-1", null, MessageType.QUERY);
        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_restartContext_parsesWithoutRole() {
        String content = META_SENTINEL + "entryType=RESTART_CONTEXT|originChannelId="
                + UUID.randomUUID() + "|originRound=3\n\n# Full summary...";
        Message msg = makeMessage(content, null, null, MessageType.STATUS);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.RESTART_CONTEXT);
        assertThat(entry.agentRole()).isNull();
        assertThat(entry.content()).isEqualTo("# Full summary...");
    }

    @Test
    void from_missingOptionalFields_defaultsToNull() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=1\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.priority()).isNull();
        assertThat(entry.scope()).isNull();
        assertThat(entry.location()).isNull();
    }

    @Test
    void from_malformedRound_defaultsToZero() {
        String content = META_SENTINEL + "entryType=RAISE|role=REV|round=abc\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.round()).isEqualTo(0);
    }
}
