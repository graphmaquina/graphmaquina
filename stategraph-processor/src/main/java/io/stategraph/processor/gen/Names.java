package io.stategraph.processor.gen;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * How one generated file writes the types it needs. The first type claiming a simple name keeps it and is imported;
 * every later type sharing that simple name is written fully qualified, so no two single-type imports collide.
 * Types of the file's own package and of {@code java.lang} claim their simple name without an import.
 */
final class Names {

    private final Set<String> imports = new LinkedHashSet<>();
    private final Map<String, String> rendered = new LinkedHashMap<>();

    /** {@code types} are qualified names in the order the file claims them; the first claimant of a simple name wins. */
    Names(String packageName, Set<String> types) {
        Set<String> claimed = new LinkedHashSet<>();
        for (String type : types) {
            String simple = type.substring(type.lastIndexOf('.') + 1);
            if (!claimed.add(simple)) {
                rendered.put(type, type);
                continue;
            }
            rendered.put(type, simple);
            if (!samePackageOrJavaLang(type, packageName)) {
                imports.add(type);
            }
        }
    }

    /** The single-type imports of the file, in claim order. */
    Set<String> imports() {
        return imports;
    }

    /** How the file writes the type: its simple name when it owns it, its qualified name otherwise. */
    String of(String qualifiedName) {
        return rendered.getOrDefault(qualifiedName, qualifiedName);
    }

    private static boolean samePackageOrJavaLang(String qualifiedName, String packageName) {
        int dot = qualifiedName.lastIndexOf('.');
        String owner = dot < 0 ? "" : qualifiedName.substring(0, dot);
        return owner.equals(packageName) || owner.equals("java.lang");
    }
}
