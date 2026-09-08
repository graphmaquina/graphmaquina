package io.stategraph.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/** A method in a by class that the generated code references. */
public record Binding(TypeElement owner, ExecutableElement method, boolean isStatic, boolean async) {

    public String methodName() {
        return method.getSimpleName().toString();
    }

    public String ownerName() {
        return owner.getSimpleName().toString();
    }
}
