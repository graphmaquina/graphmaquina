package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.processor.model.Binding;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Writes the call to the method a binding selected, and only to that one.
 *
 * <p>The processor picks an overload among the declarations a symbol exports; javac then picks one again, from
 * every declaration the host has, using the static types of the arguments the generated code passes. The two
 * answers agree on their own unless the host has another overload that is not private, declared or inherited, of
 * the same name and arity that could take those arguments as well. Only then is a parameter type written out, and
 * only at the positions where the argument does not already have it: a cast to the type an expression already has
 * is redundant, and a build that compiles the generated sources with {@code -Xlint:all -Werror} fails on it.</p>
 */
public final class BoundInvocation {

    private final Elements elements;
    private final Types types;

    public BoundInvocation(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /** {@code receiver.method(arguments)}, with the parameter types that keep this exact overload selected. */
    public String emit(Binding binding, String receiver, List<String> arguments) {
        List<String> written = competes(binding) ? pinned(binding, arguments) : arguments;
        return receiver + '.' + binding.methodName() + '(' + String.join(", ", written) + ')';
    }

    /**
     * The parameter types {@link #emit} writes for {@code binding} to keep it selected, one per argument it
     * pins: none unless another overload competes, and none for an argument that already has its type.
     */
    public List<TypeMirror> casts(Binding binding) {
        List<TypeMirror> casts = new ArrayList<>();
        if (!competes(binding)) {
            return casts;
        }
        List<? extends TypeMirror> parameters = binding.effectiveType().getParameterTypes();
        for (int at = 0; at < binding.arguments().size(); at++) {
            if (!types.isSameType(binding.arguments().get(at), parameters.get(at))) {
                casts.add(parameters.get(at));
            }
        }
        return casts;
    }

    /**
     * Whether another overload of the host that is not private, declared or inherited, could take these arguments
     * too; never for a call that passes none.
     */
    private boolean competes(Binding binding) {
        if (binding.arguments().isEmpty()) {
            return false;
        }
        for (Element member : elements.getAllMembers(binding.owner())) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement other = (ExecutableElement) member;
            // Package and protected overloads can also be visible from the generated facade. Considering
            // every non-private candidate is conservative when the facade lives in another package: it may
            // add a cast, but must never let javac select a different accessible overload.
            if (!other.equals(binding.method()) && !other.getModifiers().contains(Modifier.PRIVATE)
                    && other.getSimpleName().contentEquals(binding.methodName())
                    && other.getParameters().size() == binding.arguments().size()
                    && applies(binding, other)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code other} could take the arguments: each one's erasure is assignable to its parameter's erasure. */
    private boolean applies(Binding binding, ExecutableElement other) {
        for (int at = 0; at < binding.arguments().size(); at++) {
            if (!types.isAssignable(types.erasure(binding.arguments().get(at)),
                    types.erasure(other.getParameters().get(at).asType()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The arguments, each one that does not already have the type of its parameter cast to that type, so this
     * overload wins. The type is rendered by {@link TypeSource} as Java source, so its type annotations name their
     * enum constants and their types fully qualified, and never as javac prints a type.
     */
    private List<String> pinned(Binding binding, List<String> arguments) {
        List<? extends TypeMirror> parameters = binding.effectiveType().getParameterTypes();
        List<String> written = new ArrayList<>();
        for (int at = 0; at < arguments.size(); at++) {
            TypeMirror parameter = parameters.get(at);
            written.add(types.isSameType(binding.arguments().get(at), parameter)
                    ? arguments.get(at)
                    : "(" + TypeSource.render(parameter) + ") " + arguments.get(at));
        }
        return written;
    }
}
