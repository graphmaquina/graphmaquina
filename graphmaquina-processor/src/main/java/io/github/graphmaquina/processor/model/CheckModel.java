package io.github.graphmaquina.processor.model;

import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * A facade method that checks, without running it, whether an operation of the same facade may pass its admission
 * conditions now: the identifier of that operation, which the method's {@code Check} names, and the names the method
 * declares its parameters with, {@code entityParameter} for the entity and {@code inputParameter} for the payload,
 * present exactly when {@code inputType} is, which is the type javac resolved for the method's second parameter.
 *
 * <p>A check is no operation: it owns no identifier and opens no edge. It repeats the parameters of the operation it
 * checks, so its payload is present exactly when that operation takes one, and of the same type.</p>
 */
public record CheckModel(String name, String transitionId, String entityParameter, Optional<String> inputParameter,
                         Optional<TypeMirror> inputType) {
}
