package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DocumentSetJsonTest {

    @Test
    void documentsToJson_emptyList() {
        assertThat(DocumentSetJson.documentsToJson(List.of())).isEqualTo("[]");
    }

    @Test
    void documentsToJson_singleDocument() {
        assertThat(DocumentSetJson.documentsToJson(List.of(new DocumentEntry("/a.md", "spec"))))
                .isEqualTo("[{\"path\":\"/a.md\",\"label\":\"spec\"}]");
    }

    @Test
    void documentsToJson_multipleDocuments() {
        String json = DocumentSetJson.documentsToJson(List.of(
                new DocumentEntry("/a.md", "spec"),
                new DocumentEntry("/b.md", "impl")
        ));
        assertThat(json).startsWith("[{");
        assertThat(json).contains("\"path\":\"/a.md\"");
        assertThat(json).contains("\"path\":\"/b.md\"");
        assertThat(json).endsWith("}]");
    }

    @Test
    void documentsToJson_escapesSpecialChars() {
        String json = DocumentSetJson.documentsToJson(List.of(
                new DocumentEntry("/path/with \"quotes\".md", "label\nwith\nnewlines")
        ));
        assertThat(json).contains("\\\"quotes\\\"");
        assertThat(json).contains("\\n");
    }

    @Test
    void comparisonToJson_nonNull() {
        var cp = new ComparisonPair("/a.md", "/b.md");
        assertThat(DocumentSetJson.comparisonToJson(cp))
                .isEqualTo("{\"pathA\":\"/a.md\",\"pathB\":\"/b.md\"}");
    }

    @Test
    void comparisonToJson_null() {
        assertThat(DocumentSetJson.comparisonToJson(null)).isEqualTo("null");
    }

    @Test
    void documentsAndComparisonToJson_withComparison() {
        var session = new DebateSession(UUID.randomUUID(), "id", "ch", (String) null);
        session.addDocument("/a.md", "spec");
        session.addDocument("/b.md", "impl");
        session.setComparison("/a.md", "/b.md");
        String json = DocumentSetJson.documentsAndComparisonToJson(session);
        assertThat(json).startsWith("{\"documents\":[");
        assertThat(json).contains("\"currentComparison\":{\"pathA\":\"/a.md\",\"pathB\":\"/b.md\"}");
        assertThat(json).endsWith("}");
    }

    @Test
    void documentsAndComparisonToJson_withoutComparison() {
        var session = new DebateSession(UUID.randomUUID(), "id", "ch", (String) null);
        session.addDocument("/a.md", "spec");
        String json = DocumentSetJson.documentsAndComparisonToJson(session);
        assertThat(json).contains("\"currentComparison\":null");
    }

    @Test
    void comparisonToJson_pathsWithSpecialChars() {
        var cp = new ComparisonPair("/path\twith\ttabs.md", "/normal.md");
        String json = DocumentSetJson.comparisonToJson(cp);
        assertThat(json).contains("\\t");
    }
}
