package com.lrj.platform.knowledge;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * In-process mirror of ingested segments. This keeps deletion/listing behavior coherent before
 * the dedicated hybrid keyword index is migrated.
 */
@Component
public class DocumentMirror {

    private final List<TextSegment> segments = new CopyOnWriteArrayList<>();

    public void add(List<TextSegment> newSegments) {
        segments.addAll(newSegments);
    }

    public List<TextSegment> all() {
        return List.copyOf(segments);
    }

    public int size() {
        return segments.size();
    }

    public int removeWhere(Predicate<TextSegment> predicate) {
        int before = segments.size();
        segments.removeIf(predicate);
        return before - segments.size();
    }
}
