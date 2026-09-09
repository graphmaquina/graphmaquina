package io.stategraph.processor.gen;

import io.stategraph.processor.model.Binding;
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
 * answers agree on their own unless the host declares another overload of the same name and arity that could
 * take those arguments as well. Only then is a parameter type written out, and only at the positions where the
 * argument does not already have it: a cast to the type an expression already has is redundant, and the build
 * compiles its generated sources with {@code -Xlint:all -Werror}.</p>
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

    /** Whether another overload the host declares could take these arguments too. */
    private boolean competes(Binding binding) {
        if (binding.arguments().isEmpty()) {
            return false;
        }
        for (Element member : elements.getAllMembers(binding.owner())) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement other = (ExecutableElement) member;
            if (!other.equals(binding.method()) && other.getModifiers().contains(Modifier.PUBLIC)
                    && other.getSimpleName().contentEquals(binding.methodName())
                    && other.getParameters().size() == binding.arguments().size()
                    && applies(binding, other)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code other} would accept the arguments of the call, judged on erasures as overloading is. */
    private boolean applies(Binding binding, ExecutableElement other) {
        for (int at = 0; at < binding.arguments().size(); at++) {
            if (!types.isAssignable(types.erasure(binding.arguments().get(at)),
                    types.erasure(other.getParameters().get(at).asType()))) {
                return false;
            }
        }
        return true;
    }

    /** The arguments, each one written with the parameter type it must have for this overload to win. */
    private List<String> pinned(Binding binding, List<String> arguments) {
        List<? extends TypeMirror> parameters = binding.effectiveType().getParameterTypes();
        List<String> written = new ArrayList<>();
        for (int at = 0; at < arguments.size(); at++) {
            TypeMirror parameter = parameters.get(at);
            written.add(types.isSameType(binding.arguments().get(at), parameter)
                    ? arguments.get(at)
                    : "(" + parameter + ") " + arguments.get(at));
        }
        return written;
    }
}
