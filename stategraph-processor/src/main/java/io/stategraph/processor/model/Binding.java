package io.stategraph.processor.model;

import io.stategraph.annotation.internal.SymbolKind;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;

/**
 * One method the generated code calls: the concrete host that owns the call, the declaration it selected and
 * that declaration's signature as a member of the host, so a contract inherited from a generic base is read
 * with the host's own entity and states instead of with its type variables.
 *
 * <p>{@code arguments} are the static types of the expressions the generated call passes, which the emitter
 * needs to tell an argument that already has the parameter's type from one whose type must be pinned down for
 * the compiler to keep this exact overload. A binding that names no call site yet, as the effective
 * declarations of a host do before a role has been asked for, carries none.</p>
 */
public record Binding(TypeElement owner, ExecutableElement method, ExecutableType effectiveType, SymbolKind role,
                      List<TypeMirror> arguments, boolean isStatic, boolean async) {

    public Binding {
        arguments = List.copyOf(arguments);
    }

    public String methodName() {
        return method.getSimpleName().toString();
    }
}
