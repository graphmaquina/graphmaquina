package io.stategraph.codegen.emit;

/**
 * Derives the generated references container of a host and the symbol type names it encloses, and splits a
 * canonical name into the package it belongs to and the name it carries inside that package.
 */
public final class SymbolNames {

    private SymbolNames() {
    }

    /**
     * Returns the package of {@code canonicalName}, empty for a type of the unnamed package. The type part of a
     * canonical name begins at its first segment that starts with an upper-case letter, the naming convention of
     * every package and type this generator reads; a name whose segments are all lower-case is taken as a
     * top-level type of the package its leading segments spell.
     */
    public static String packageOf(String canonicalName) {
        int start = typeStart(canonicalName);
        return start == 0 ? "" : canonicalName.substring(0, start - 1);
    }

    /**
     * Returns {@code canonicalName} without its package, so a nested type keeps the dots joining its enclosing
     * simple names and can be passed to {@link #hostName(String)}.
     */
    public static String nameWithoutPackage(String canonicalName) {
        return canonicalName.substring(typeStart(canonicalName));
    }

    /** The index of the first character of the type part of {@code canonicalName}. */
    private static int typeStart(String canonicalName) {
        int segment = 0;
        while (segment < canonicalName.length()) {
            if (Character.isUpperCase(canonicalName.charAt(segment))) {
                return segment;
            }
            int dot = canonicalName.indexOf('.', segment);
            if (dot < 0) {
                return segment;
            }
            segment = dot + 1;
        }
        return segment;
    }

    /**
     * Returns the name a host's symbols are grouped under. {@code hostName} is the host's canonical name
     * without its package, so a nested host joins its enclosing simple names with {@code _} and
     * {@code Outer.Inner} becomes {@code Outer_Inner}. This is the name to pass as {@code hostSimpleName}.
     */
    public static String hostName(String hostName) {
        return hostName.replace('.', '_');
    }

    /**
     * Returns the simple name of the generated references container of {@code hostName}, the host's canonical
     * name without its package. The container is a top-level type in the host's own package, so
     * {@code Outer.Inner} is exported through {@code Outer_InnerRefs}.
     */
    public static String containerName(String hostName) {
        return hostName(hostName) + "Refs";
    }

    /**
     * Returns the generated type name for {@code memberName} declared on {@code hostSimpleName}. The mapping is
     * injective: a lower-case first letter is upper-cased, any other first letter (including an already
     * upper-case one or an underscore) is prefixed with {@code _}, and the one name that would otherwise collide
     * with the host's own generated references type ({@code <hostSimpleName>Refs}) is prefixed with {@code _sg_}.
     */
    public static String typeName(String hostSimpleName, String memberName) {
        String candidate = memberName.charAt(0) >= 'a' && memberName.charAt(0) <= 'z'
            ? Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1)
            : "_" + memberName;
        return candidate.equals(hostSimpleName + "Refs") ? "_sg_" + candidate : candidate;
    }
}
