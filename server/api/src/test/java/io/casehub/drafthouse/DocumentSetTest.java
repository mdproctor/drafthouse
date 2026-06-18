package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DocumentSetTest {

    @Test
    void add_returnsTrue_andDocumentIsRetrievable() {
        var set = new DocumentSet();
        assertThat(set.add("/a.md", "spec")).isTrue();
        assertThat(set.documents()).hasSize(1);
        assertThat(set.documents().get(0).path()).isEqualTo("/a.md");
        assertThat(set.documents().get(0).label()).isEqualTo("spec");
    }

    @Test
    void add_duplicatePath_returnsFalse() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        assertThat(set.add("/a.md", "different-label")).isFalse();
        assertThat(set.documents()).hasSize(1);
    }

    @Test
    void remove_existingPath_returnsTrue() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        assertThat(set.remove("/b.md")).isTrue();
        assertThat(set.documents()).hasSize(1);
    }

    @Test
    void remove_nonexistentPath_returnsFalse() {
        var set = new DocumentSet();
        assertThat(set.remove("/no-such.md")).isFalse();
    }

    @Test
    void primary_returnsFirstDocument() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        assertThat(set.primary()).isPresent();
        assertThat(set.primary().get().path()).isEqualTo("/a.md");
    }

    @Test
    void primary_emptySet_returnsEmpty() {
        var set = new DocumentSet();
        assertThat(set.primary()).isEmpty();
    }

    @Test
    void setComparison_storesCurrentPair() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        assertThat(set.currentComparison()).isNotNull();
        assertThat(set.currentComparison().pathA()).isEqualTo("/a.md");
        assertThat(set.currentComparison().pathB()).isEqualTo("/b.md");
    }

    @Test
    void clearComparison_setsToNull() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        set.clearComparison();
        assertThat(set.currentComparison()).isNull();
    }

    @Test
    void remove_pathInComparison_clearsComparison() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        set.remove("/b.md");
        assertThat(set.currentComparison()).isNull();
    }

    @Test
    void documentEntry_rejectsNullPath() {
        assertThatThrownBy(() -> new DocumentEntry(null, "label"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path");
    }

    @Test
    void documentEntry_rejectsBlankPath() {
        assertThatThrownBy(() -> new DocumentEntry("  ", "label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
    }

    @Test
    void documentEntry_rejectsNullLabel() {
        assertThatThrownBy(() -> new DocumentEntry("/a.md", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("label");
    }

    @Test
    void copyOf_deepCopiesDocumentsAndComparison() {
        var original = new DocumentSet();
        original.add("/a.md", "spec");
        original.add("/b.md", "impl");
        original.setComparison("/a.md", "/b.md");

        var copy = DocumentSet.copyOf(original);
        assertThat(copy.documents()).hasSize(2);
        assertThat(copy.currentComparison().pathA()).isEqualTo("/a.md");

        // Mutations on copy don't affect original
        copy.add("/c.md", "test");
        assertThat(original.documents()).hasSize(2);
    }

    @Test
    void documents_returnsDefensiveCopy() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        var list = set.documents();
        assertThatThrownBy(() -> list.add(new DocumentEntry("/b.md", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void setComparison_allowsSamePathForBothSides() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.setComparison("/a.md", "/a.md");
        assertThat(set.currentComparison().pathA()).isEqualTo("/a.md");
        assertThat(set.currentComparison().pathB()).isEqualTo("/a.md");
    }

    @Test
    void add_concurrentSamePath_onlyOneSucceeds() throws Exception {
        var set = new DocumentSet();
        int threadCount = 20;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        var latch = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return set.add("/race.md", "label");
            }));
        }
        latch.countDown();

        long trueCount = futures.stream()
                .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                .filter(b -> b)
                .count();

        executor.shutdown();
        assertThat(trueCount).isEqualTo(1);
        assertThat(set.documents()).hasSize(1);
    }

    @Test
    void addAndRemove_concurrent_noExceptionOrLostUpdate() throws Exception {
        var set = new DocumentSet();
        int iterations = 100;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        var latch = new java.util.concurrent.CountDownLatch(1);

        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < iterations; i++) {
            String path = "/doc-" + i + ".md";
            futures.add(executor.submit(() -> {
                latch.await();
                set.add(path, "label");
                return null;
            }));
            futures.add(executor.submit(() -> {
                latch.await();
                set.remove(path);
                return null;
            }));
        }
        latch.countDown();

        for (var f : futures) {
            assertThatCode(() -> f.get()).doesNotThrowAnyException();
        }
        executor.shutdown();
    }
}
