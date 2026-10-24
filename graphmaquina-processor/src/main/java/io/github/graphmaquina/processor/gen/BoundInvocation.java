package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.processor.model.Binding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Writes the call to the method a binding selected, and only to that one.
 *
 * <p>The processor picks an overload among the declarations a symbol exports; javac then picks one again, from
 * every declaration the host has, using the static types of the arguments the generated code passes. The two
 * answers agree on their own unless the host has another overload that is not private, declared or inherited, of
 * the same name and arity that could take those arguments as well. Only then is a parameter type written out, and
 * only at the positions where the argument does not already have it: a cast to the type an expression already has
 * is redundant, and a build that compiles the generated sources with {@code -Xlint:all -Werror} fails on it.</p>
 *
 * <p>It also tells which calls the generated lambda making them catches, because they may throw a checked exception,
 * which no functional interface the generated code implements declares.</p>
 *
 * <p>The methods of a host are listed once per round and kept by name, however many calls to it are written:
 * {@link #forget}, which the processor calls at the start of every round, drops them with the members javac enters
 * afresh in that round. A binding a machine's model holds from an earlier round, which a set of a later round builds
 * that machine from, selected a method javac has entered again since: it is recognized among those of the round by
 * the type that declares it, its name and the erasures of its parameters, so it never competes with itself.</p>
 */
@RequiredArgsConstructor
public final class BoundInvocation {

    /** What a lambda catches around a call that throws checked exceptions, all of them exceptions. */
    static final String EXCEPTION = "java.lang.Exception";

    /** What a lambda catches around a call that throws a checked throwable that is no exception. */
    static final String THROWABLE = "java.lang.Throwable";

    /** The element utilities of the compilation, with which a generator also looks up a type by its qualified name. */
    @Getter(AccessLevel.PACKAGE)
    private final Elements elements;
    private final Types types;
    private final Map<TypeElement, Map<String, List<ExecutableElement>>> methods = new HashMap<>();

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
        for (ExecutableElement other : methods(binding.owner()).getOrDefault(binding.methodName(), List.of())) {
            // Package and protected overloads can also be visible from the generated facade. Considering
            // every non-private candidate is conservative when the facade lives in another package: it may
            // add a cast, but must never let javac select a different accessible overload.
            if (!same(other, binding.method()) && !other.getModifiers().contains(Modifier.PRIVATE)
                    && other.getParameters().size() == binding.arguments().size()
                    && applies(binding, other)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code other}, listed in this round, is the declaration {@code method} is, which javac may have
     * entered in an earlier round: both are declared by one type, with one name and parameters that erase to the
     * same types, which no two methods a type declares share.
     */
    private boolean same(ExecutableElement other, ExecutableElement method) {
        if (other.equals(method)) {
            return true;
        }
        if (!(other.getEnclosingElement() instanceof TypeElement otherOwner)
                || !(method.getEnclosingElement() instanceof TypeElement owner)
                || !otherOwner.getQualifiedName().contentEquals(owner.getQualifiedName())
                || !other.getSimpleName().contentEquals(method.getSimpleName())
                || other.getParameters().size() != method.getParameters().size()) {
            return false;
        }
        for (int at = 0; at < method.getParameters().size(); at++) {
            if (!types.isSameType(types.erasure(other.getParameters().get(at).asType()),
                    types.erasure(method.getParameters().get(at).asType()))) {
                return false;
            }
        }
        return true;
    }

    /** The methods {@code owner} declares or inherits, by name, listed once in this round. */
    private Map<String, List<ExecutableElement>> methods(TypeElement owner) {
        return methods.computeIfAbsent(owner, (TypeElement key) -> {
            Map<String, List<ExecutableElement>> byName = new HashMap<>();
            for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(key))) {
                byName.computeIfAbsent(method.getSimpleName().toString(), (String name) -> new ArrayList<>())
                        .add(method);
            }
            return byName;
        });
    }

    /** Drops the methods listed in a round, which the members a later round enters replace. */
    public void forget() {
        methods.clear();
    }

    /**
     * The type a lambda that calls {@code binding} catches, to throw on unchanged what the method declares: nothing
     * when its throws clause holds no checked type, {@code java.lang.Exception} when every checked type it holds is
     * an exception, {@code java.lang.Throwable} otherwise. A type is checked unless it is {@code RuntimeException},
     * {@code Error} or a subclass of either. A thrown type variable counts as its bound or, when that bound is an
     * intersection, as the class among its bounds. javac infers the bound itself for a call that leaves the variable to
     * it, unless the bound is a supertype of {@code RuntimeException}, as {@code Exception} and {@code Throwable} are:
     * javac then infers {@code RuntimeException}, and the call is caught although it need not be.
     */
    Optional<String> caught(Binding binding) {
        boolean checked = false;
        boolean exceptions = true;
        for (TypeMirror thrown : binding.effectiveType().getThrownTypes()) {
            if (extendsJavaLang(thrown, "RuntimeException") || extendsJavaLang(thrown, "Error")) {
                continue;
            }
            checked = true;
            exceptions &= extendsJavaLang(thrown, "Exception");
        }
        if (!checked) {
            return Optional.empty();
        }
        return Optional.of(exceptions ? EXCEPTION : THROWABLE);
    }

    /**
     * Whether {@code type} is the class {@code java.lang.<simple>} or a subclass of it; a type variable is when its
     * bound is, and an intersection when one of its bounds is, which only the class among them can be. Each class is
     * recognized by its package and simple name along the superclasses of {@code type}, so no lookup by name is made,
     * which a class of the unnamed package named {@code java} could obscure.
     */
    private static boolean extendsJavaLang(TypeMirror type, String simple) {
        if (type instanceof TypeVariable variable) {
            return extendsJavaLang(variable.getUpperBound(), simple);
        }
        if (type instanceof IntersectionType intersection) {
            for (TypeMirror bound : intersection.getBounds()) {
                if (extendsJavaLang(bound, simple)) {
                    return true;
                }
            }
            return false;
        }
        TypeMirror current = type;
        while (current instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            if (element.getSimpleName().contentEquals(simple)
                    && element.getEnclosingElement() instanceof PackageElement owner
                    && owner.getQualifiedName().contentEquals("java.lang")) {
                return true;
            }
            current = element.getSuperclass();
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
