package io.github.graphmaquina.processor.model;

import java.util.List;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * A facade method that runs, or with {@code check} checks without running it, the first of {@code candidates} that
 * is admitted: the identifiers its {@code Choice} names, in the order a call tries them, and the names the method
 * declares its parameters with, {@code entityParameter} for the entity and {@code inputParameter} for the payload,
 * present exactly when {@code inputType} is, which is the type javac resolved for the method's second parameter.
 *
 * <p>A choice is no operation: it owns no identifier and opens no edge, so a candidate may also have an operation of
 * its own. Whether it runs or checks is told by its result, an {@code Outcome} or a {@code ChoiceEligibility}.</p>
 */
public record ChoiceModel(String name, List<String> candidates, boolean check, String entityParameter,
                          Optional<String> inputParameter, Optional<TypeMirror> inputType) {

    public ChoiceModel {
        candidates = List.copyOf(candidates);
    }
}
