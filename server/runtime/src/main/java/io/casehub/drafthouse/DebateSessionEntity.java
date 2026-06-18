package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "debate_session")
class DebateSessionEntity {

    @Id
    @Column(name = "channel_id")
    UUID channelId;

    @Column(name = "debate_session_id", nullable = false)
    String debateSessionId;

    @Column(name = "channel_name", nullable = false)
    String channelName;

    @Column(name = "comparison_path_a", length = 1024)
    String comparisonPathA;

    @Column(name = "comparison_path_b", length = 1024)
    String comparisonPathB;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "debate_session_document",
            joinColumns = @JoinColumn(name = "session_channel_id"))
    @OrderColumn(name = "document_order")
    List<DocumentEmbeddable> documents = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "debate_session_participant",
            joinColumns = @JoinColumn(name = "session_channel_id"))
    @MapKeyColumn(name = "agent_type")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "instance_id")
    Map<AgentType, String> participants = new HashMap<>();

    @Embeddable
    static class DocumentEmbeddable {
        @Column(name = "path", nullable = false, length = 1024)
        String path;

        @Column(name = "label", nullable = false)
        String label;

        DocumentEmbeddable() {}

        DocumentEmbeddable(String path, String label) {
            this.path = path;
            this.label = label;
        }
    }

    DebateSessionSnapshot toSnapshot() {
        List<DocumentEntry> docs = documents.stream()
                .map(d -> new DocumentEntry(d.path, d.label))
                .toList();
        ComparisonPair cp = (comparisonPathA != null && comparisonPathB != null)
                ? new ComparisonPair(comparisonPathA, comparisonPathB)
                : null;
        return new DebateSessionSnapshot(channelId, debateSessionId, channelName,
                docs, cp, Map.copyOf(participants));
    }

    static DebateSessionEntity fromSnapshot(DebateSessionSnapshot snap) {
        var entity = new DebateSessionEntity();
        entity.channelId = snap.channelId();
        entity.debateSessionId = snap.debateSessionId();
        entity.channelName = snap.channelName();
        entity.documents = new ArrayList<>(snap.documents().stream()
                .map(d -> new DocumentEmbeddable(d.path(), d.label()))
                .toList());
        if (snap.comparison() != null) {
            entity.comparisonPathA = snap.comparison().pathA();
            entity.comparisonPathB = snap.comparison().pathB();
        }
        entity.participants = new HashMap<>(snap.participants());
        return entity;
    }
}
