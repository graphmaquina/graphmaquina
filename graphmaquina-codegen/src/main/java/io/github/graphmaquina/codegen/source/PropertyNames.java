package io.github.graphmaquina.codegen.source;

import java.util.Objects;

/**
 * Derives the logical name of a property from the accessor that declares it.
 *
 * <p>A {@code get} or {@code is} prefix followed by an upper-case letter is removed and the JavaBeans
 * decapitalization is applied to what remains: a name whose first two letters are upper-case keeps them, so
 * {@code getURL} exports {@code URL}, and any other name lowers its first letter. A fluent reader that spells
 * no prefix keeps its own name.</p>
 */
public final class PropertyNames {

    private PropertyNames() {
    }

    /** The logical property name {@code getter} declares. */
    public static String fromGetter(String getter) {
        Objects.requireNonNull(getter, "getter");
        String name = getter;
        if (getter.startsWith("get") && getter.length() > 3
                && Character.isUpperCase(getter.charAt(3))) {
            name = getter.substring(3);
        } else if (getter.startsWith("is") && getter.length() > 2
                && Character.isUpperCase(getter.charAt(2))) {
            name = getter.substring(2);
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0))
                && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
