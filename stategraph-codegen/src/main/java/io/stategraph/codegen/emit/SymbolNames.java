package io.stategraph.codegen.emit;

/** Derives the generated type name for a symbol from its host and member name. */
public final class SymbolNames {

    private SymbolNames() {
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
