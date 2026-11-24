package io.github.graphmaquina.processor.model;

import io.github.graphmaquina.annotation.internal.SymbolKind;
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
 * declarations of a host do before a role has been asked for, carries none. {@code propagates} are the throwable types
 * a guard's {@code GraphGuard.propagates} declares, in their order; empty for every other role.</p>
 */
public record Binding(TypeElement owner, ExecutableElement method, ExecutableType effectiveType, SymbolKind role,
                      List<TypeMirror> arguments, boolean isStatic, boolean async, List<TypeMirror> propagates) {

    /** Copies the arguments and the types a guard propagates. */
    public Binding {
        arguments = List.copyOf(arguments);
        propagates = List.copyOf(propagates);
    }

    /** A binding that propagates nothing: every role but a guard, and a guard that declares no type. */
    public Binding(TypeElement owner, ExecutableElement method, ExecutableType effectiveType, SymbolKind role,
                   List<TypeMirror> arguments, boolean isStatic, boolean async) {
        this(owner, method, effectiveType, role, arguments, isStatic, async, List.of());
    }

    /** This binding with the types {@code types} a guard's {@code GraphGuard.propagates} declares. */
    public Binding propagating(List<TypeMirror> types) {
        return new Binding(owner, method, effectiveType, role, arguments, isStatic, async, types);
    }

    public String methodName() {
        return method.getSimpleName().toString();
    }
}
