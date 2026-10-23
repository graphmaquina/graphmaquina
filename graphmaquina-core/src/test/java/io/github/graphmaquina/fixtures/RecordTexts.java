package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.TransitionRecord;
import java.util.List;

/** The transition records of an outcome as texts that a test compares with literals. */
public final class RecordTexts {

    private RecordTexts() { }

    /** One record as {@code entity:FROM-ID>TO@depth}. */
    public static String describe(TransitionRecord<?, ?, ?> record) {
        return record.entity() + ":" + record.from() + "-" + record.transition() + ">" + record.to() + "@"
                + record.depth();
    }

    /** Each record as {@code entity:FROM-ID>TO@depth}, as in {@code Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0}. */
    public static List<String> described(List<TransitionRecord<?, ?, ?>> records) {
        return records.stream().map(RecordTexts::describe).toList();
    }
}
