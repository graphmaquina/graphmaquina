package io.github.graphmaquina.codegen.source;

import java.util.List;
import lombok.NonNull;

/**
 * Identity of one declared type parameter: the declaration that owns it and its zero-based position in that
 * declaration's parameter list. Two parameters spelled {@code T} by different declarations never share a key,
 * so a substitution environment can never replace one with the other.
 *
 * <p>For a class, interface, enum, record or annotation parameter, {@code declaringType} is the canonical name
 * of that declaration, for example {@code fx.Base}.</p>
 *
 * <p>For a method parameter it is instead a declaration-owner token that no canonical class name can equal:
 * the canonical name of the declaring type, {@code '#'}, the method name, and the method's parameter types
 * between parentheses separated by {@code ", "}, each rendered as it was read from the declaration and with
 * type variables rendered by their own simple names. A method {@code <T extends Number> T map(T value)}
 * declared by {@code fx.Base} therefore owns the token {@code fx.Base#map(T)}, which is distinct from
 * {@code fx.Base}. Method parameters consequently never enter a class substitution environment.</p>
 */
public record TypeParameterKey(@NonNull String declaringType, int position) {

    public TypeParameterKey {
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative: " + position);
        }
    }

    /** Builds the declaration-owner token that scopes the type parameters of one method. */
    public static String methodOwner(@NonNull String declaringType, @NonNull String methodName,
            @NonNull List<String> parameterTypes) {
        return declaringType + '#' + methodName + '(' + String.join(", ", parameterTypes) + ')';
    }
}
