package io.stategraph.codegen.emit;

/** Derives the generated references container of a host and the symbol type names it encloses. */
public final class SymbolNames {

    private SymbolNames() {
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
