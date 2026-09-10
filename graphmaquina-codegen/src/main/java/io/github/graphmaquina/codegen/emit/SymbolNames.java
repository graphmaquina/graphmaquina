package io.github.graphmaquina.codegen.emit;

/**
 * Derives the generated references container of a host and the symbol type names it encloses, and joins or
 * separates a canonical name and the package it is declared in.
 *
 * <p>No method here infers a package from a canonical name: the package is always given, because only the
 * declaration that was read knows where it ends and the nested type names begin.</p>
 */
public final class SymbolNames {

    private SymbolNames() {
    }

    /**
     * Returns {@code canonicalName} without the {@code packageName} it is declared in, so a nested type keeps
     * the dots joining its enclosing simple names and can be passed to {@link #hostName(String)}.
     *
     * @throws IllegalArgumentException if {@code canonicalName} is not declared in {@code packageName}
     */
    public static String nameWithin(String packageName, String canonicalName) {
        if (packageName.isEmpty()) {
            return canonicalName;
        }
        if (!canonicalName.startsWith(packageName + '.')) {
            throw new IllegalArgumentException(canonicalName + " is not declared in package " + packageName);
        }
        return canonicalName.substring(packageName.length() + 1);
    }

    /** Joins {@code packageName}, which may be empty, and the top-level type name {@code simpleName}. */
    public static String qualified(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + '.' + simpleName;
    }

    /** Normalizes the host's name within its package, including any enclosing types, to PascalCase. */
    public static String hostName(String hostName) {
        return normalize(hostName);
    }

    /** The top-level references container for a host's name within its package. */
    public static String containerName(String hostName) {
        return hostName(hostName) + "Refs";
    }

    /** The implementation type for a machine or set's name within its package. */
    public static String runtimeName(String nameWithinPackage) {
        return hostName(nameWithinPackage) + "Runtime";
    }

    /**
     * Normalizes a member name to PascalCase without inventing collision escapes. The declaration collector
     * rejects members that normalize to the same symbol or to their references container.
     */
    public static String typeName(String hostSimpleName, String memberName) {
        return normalize(memberName);
    }

    private static String normalize(String name) {
        StringBuilder result = new StringBuilder();
        boolean wordStart = true;
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '_' || codePoint == '.') {
                wordStart = true;
            } else {
                result.appendCodePoint(wordStart ? Character.toUpperCase(codePoint) : codePoint);
                wordStart = false;
            }
        }
        String normalized = result.toString();
        if (!javax.lang.model.SourceVersion.isIdentifier(normalized)
                || javax.lang.model.SourceVersion.isKeyword(normalized)) {
            throw new IllegalArgumentException("the name '" + name
                + "' does not normalize to a valid nonempty Java type identifier: '" + normalized + "'");
        }
        return normalized;
    }
}
