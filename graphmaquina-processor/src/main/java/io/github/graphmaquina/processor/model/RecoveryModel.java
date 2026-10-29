package io.github.graphmaquina.processor.model;

import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.type.TypeMirror;

/**
 * One recovery rule an edge declares in a nested {@code Recovery} of its {@code Transition}: the failure kind
 * {@code kind} names, the identifier of the edge the rule recovers through, resolved from each origin of the edge
 * that declares it, and the throwable types {@code failsOn} lists, in declaration order.
 *
 * <p>The rule keeps the values it was read from, so that a diagnostic about it points at the value that is wrong:
 * {@code annotation} is the nested {@code Recovery} itself, {@code kindValue} the value that names the kind, and
 * {@code failsOnValues} holds, at each position, the value that names the type {@code failsOn} holds there.
 * {@code continueChain} says whether the rule continues the chain of automatic attempts.</p>
 */
public record RecoveryModel(TypeMirror kind, String transition, List<TypeMirror> failsOn, AnnotationMirror annotation,
                            AnnotationValue kindValue, List<AnnotationValue> failsOnValues,
                            boolean continueChain) {

    public RecoveryModel {
        failsOn = List.copyOf(failsOn);
        failsOnValues = List.copyOf(failsOnValues);
    }
}
