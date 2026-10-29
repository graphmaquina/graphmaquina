package io.github.graphmaquina.processor.model;

import java.util.List;
import javax.lang.model.element.AnnotationMirror;

/**
 * One {@code AutomaticOrder} the interface declares: the state whose automatic edges it orders, the identifiers it
 * names in the order it names them, repeats included, and the annotation it was read from.
 */
public record AutomaticOrderModel(String from, List<String> transitions, AnnotationMirror annotation) {

    public AutomaticOrderModel {
        transitions = List.copyOf(transitions);
    }
}
