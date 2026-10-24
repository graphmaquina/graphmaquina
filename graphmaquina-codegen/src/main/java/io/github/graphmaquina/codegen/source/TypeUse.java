package io.github.graphmaquina.codegen.source;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import lombok.NonNull;

/**
 * One use of a type inside a declaration header: a supertype, a type argument, a parameter type, a return type or
 * the type of a field. A use is read syntactically from an original source or materialized from a classpath header,
 * so it carries no attribution beyond the names its declaration spells.
 */
public sealed interface TypeUse {

    /** The canonical rendering of this use, as it reaches the published descriptors. */
    String render();

    /**
     * A class, interface, enum, record or annotation use. {@code name} is the canonical name once the
     * declaration index has resolved it, and the name as spelled until then; {@code owner} carries the
     * parameterization of an enclosing type when the use names an inner member type.
     */
    record Declared(@NonNull String name, List<TypeUse> arguments,
            @NonNull Optional<TypeUse> owner) implements TypeUse {

        public Declared {
            arguments = List.copyOf(arguments);
        }

        /** A use of {@code name} with no arguments and no parameterized owner. */
        public static Declared of(String name) {
            return new Declared(name, List.of(), Optional.empty());
        }

        @Override
        public String render() {
            StringBuilder rendered = new StringBuilder();
            if (owner.isPresent()) {
                int lastDot = name.lastIndexOf('.');
                rendered.append(owner.get().render()).append('.').append(name.substring(lastDot + 1));
            } else {
                rendered.append(name);
            }
            if (!arguments.isEmpty()) {
                StringJoiner joined = new StringJoiner(", ", "<", ">");
                for (TypeUse argument : arguments) {
                    joined.add(argument.render());
                }
                rendered.append(joined);
            }
            return rendered.toString();
        }
    }

    /** A use of a declared type parameter, identified by the declaration that owns it. */
    record Variable(@NonNull String name, @NonNull TypeParameterKey key) implements TypeUse {

        public Variable {
        }

        @Override
        public String render() {
            return name;
        }
    }

    /** An array of another use. */
    record Array(@NonNull TypeUse component) implements TypeUse {

        public Array {
        }

        @Override
        public String render() {
            return component.render() + "[]";
        }
    }

    /** A wildcard argument, with at most one of its two bounds present. */
    record Wildcard(@NonNull Optional<TypeUse> upperBound, @NonNull Optional<TypeUse> lowerBound) implements TypeUse {

        public Wildcard {
            if (upperBound.isPresent() && lowerBound.isPresent()) {
                throw new IllegalArgumentException("a wildcard declares at most one bound");
            }
        }

        /** An unbounded wildcard. */
        public static Wildcard unbounded() {
            return new Wildcard(Optional.empty(), Optional.empty());
        }

        @Override
        public String render() {
            if (upperBound.isPresent()) {
                return "? extends " + upperBound.get().render();
            }
            if (lowerBound.isPresent()) {
                return "? super " + lowerBound.get().render();
            }
            return "?";
        }
    }

    /** A primitive use, including {@code void}. */
    record Primitive(@NonNull String name) implements TypeUse {

        public Primitive {
        }

        @Override
        public String render() {
            return name;
        }
    }
}
