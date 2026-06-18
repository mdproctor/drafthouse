package io.casehub.drafthouse.debate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;

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

    @Test
    void from_raise_extractsAllFields() {
        String content = "DHMETA:entryType=RAISE|agent=REV|round=1"
                + "|priority=P1|scope=ISOLATED|location=§3.2\n\nThe API is ambiguous.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.RAISE);
        assertThat(entry.agentRole()).isEqualTo(AgentType.REV);
        assertThat(entry.round()).isEqualTo(1);
        assertThat(entry.content()).isEqualTo("The API is ambiguous.");
        assertThat(entry.pointId()).isEqualTo("point-1");
        assertThat(entry.subTaskId()).isNull();
        assertThat(entry.priority()).isEqualTo(Priority.P1);
        assertThat(entry.scope()).isEqualTo(Scope.ISOLATED);
        assertThat(entry.location()).isEqualTo("§3.2");
        assertThat(entry.sender()).isEqualTo("drafthouse-rev-abc123");
        assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-06-11T10:00:00Z"));
    }

    @Test
    void from_agree_setsPointIdFromCorrelationId() {
        String content = "DHMETA:entryType=AGREE|agent=IMP|round=2\n\nCorrect.";
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
        String content = "DHMETA:entryType=SUB_TASK_REQUEST|agent=REV"
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
        String content = "DHMETA:entryType=SUB_TASK_ERROR|agent=REV"
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
        String content = "DHMETA:entryType=MEMO|agent=REV|round=2\n\nMy reasoning.";
        Message msg = makeMessage(content, null, null, MessageType.STATUS);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.MEMO);
        assertThat(entry.pointId()).isNull();
        assertThat(entry.subTaskId()).isNull();
    }

    @Test
    void from_noMetaSentinel_returnsNull() {
        Message msg = makeMessage("plain text no sentinel", "c-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_unknownEntryType_returnsNull() {
        String content = "DHMETA:entryType=UNKNOWN_TYPE|agent=REV|round=1\n\nBody.";
        Message msg = makeMessage(content, "c-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_missingAgent_returnsNull() {
        String content = "DHMETA:entryType=RAISE|round=1\n\nBody.";
        Message msg = makeMessage(content, "c-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_restartContext_parsesWithoutAgent() {
        String content = "DHMETA:entryType=RESTART_CONTEXT|originChannelId="
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
        String content = "DHMETA:entryType=RAISE|agent=REV|round=1\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.priority()).isNull();
        assertThat(entry.scope()).isNull();
        assertThat(entry.location()).isNull();
    }

    @Test
    void from_malformedRound_defaultsToZero() {
        String content = "DHMETA:entryType=RAISE|agent=REV|round=abc\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.round()).isEqualTo(0);
    }
}
