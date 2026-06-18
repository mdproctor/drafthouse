package io.casehub.drafthouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

class DocumentSet {

    private final ArrayList<DocumentEntry> documents = new ArrayList<>();
    private ComparisonPair currentComparison;

    public synchronized boolean add(String path, String label) {
        for (DocumentEntry e : documents) {
            if (e.path().equals(path)) return false;
        }
        documents.add(new DocumentEntry(path, label));
        return true;
    }

    public synchronized boolean remove(String path) {
        boolean removed = documents.removeIf(e -> e.path().equals(path));
        if (removed) {
            ComparisonPair cp = currentComparison;
            if (cp != null && (path.equals(cp.pathA()) || path.equals(cp.pathB()))) {
                currentComparison = null;
            }
        }
        return removed;
    }

    public synchronized List<DocumentEntry> documents() {
        return List.copyOf(documents);
    }

    public synchronized Optional<DocumentEntry> primary() {
        return documents.isEmpty() ? Optional.empty() : Optional.of(documents.get(0));
    }

    public synchronized void setComparison(String pathA, String pathB) {
        currentComparison = new ComparisonPair(pathA, pathB);
    }

    public synchronized void clearComparison() {
        currentComparison = null;
    }

    public synchronized ComparisonPair currentComparison() {
        return currentComparison;
    }

    public static DocumentSet copyOf(DocumentSet source) {
        var copy = new DocumentSet();
        synchronized (source) {
            for (DocumentEntry e : source.documents) {
                copy.documents.add(e);
            }
            copy.currentComparison = source.currentComparison;
        }
        return copy;
    }
}
