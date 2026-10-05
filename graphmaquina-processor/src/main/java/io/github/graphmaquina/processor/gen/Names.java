package io.github.graphmaquina.processor.gen;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * How one generated file writes the types it needs.
 *
 * <p>A simple name means, inside the generated class, whatever the scope of that class resolves it to, so a type
 * is written by its simple name only where that resolution yields the type itself:</p>
 * <ul>
 *   <li>a member type the class declares or inherits owns its simple name throughout the class, so every other
 *       type of that simple name is written fully qualified;</li>
 *   <li>otherwise the first type claiming a simple name keeps it, and is written by it where it resolves to that
 *       type: a top-level type of the file's own package is visible already, a {@code java.lang} type is visible
 *       unless a type of the file's own package takes its name, and any other type is imported, except one of the
 *       unnamed package, which no import can name, and, in a file of the unnamed package, one whose simple name a
 *       type of that package takes, since the import would hide that type, whose simple name is its only name;
 *       those exceptions are written qualified;</li>
 *   <li>every later type sharing that simple name is written fully qualified, so no two single-type imports
 *       collide.</li>
 * </ul>
 *
 * <p>A qualified name starts with a package, except that of a type of the unnamed package, which starts with the
 * top-level type it is or is nested in. {@link #written()} answers what the file then writes at the head of a
 * type reference, these two kinds of first segments apart, and which of them {@link #qualifier} wrote where an
 * expression reads them. No variable of the file may take one of them, because a variable of that name obscures
 * the package or the type it names wherever an expression could read it as the variable; the processor checks
 * all of them against the types of the file's scope, and the ones an expression reads against its fields.</p>
 */
final class Names {

    private final Set<String> imports = new LinkedHashSet<>();
    private final Map<String, String> rendered = new LinkedHashMap<>();
    private final Map<String, String> roots = new LinkedHashMap<>();
    private final Map<String, String> typeRoots = new LinkedHashMap<>();
    private final Map<String, String> simpleNames = new LinkedHashMap<>();
    private final Map<String, String> expressions = new LinkedHashMap<>();

    /**
     * {@code types} maps each qualified name to the package that declares it, in the order the file claims them;
     * {@code scope} is what the generated class already sees besides its imports.
     */
    Names(String packageName, Map<String, String> types, Scope scope) {
        Set<String> claimed = new LinkedHashSet<>();
        for (Map.Entry<String, String> type : types.entrySet()) {
            String qualified = type.getKey();
            String owner = type.getValue();
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            boolean topLevel = qualified.equals(owner.isEmpty() ? simple : owner + '.' + simple);
            if (scope.members().containsKey(simple)) {
                render(qualified, owner, scope.members().get(simple).equals(qualified));
            } else if (!claimed.add(simple)) {
                render(qualified, owner, false);
            } else if (topLevel && owner.equals(packageName)) {
                render(qualified, owner, true);
            } else if (topLevel && owner.equals("java.lang")) {
                render(qualified, owner, !scope.packageTypes().test(simple));
            } else if (owner.isEmpty() || (packageName.isEmpty() && scope.packageTypes().test(simple))) {
                render(qualified, owner, false);
            } else {
                imports.add(qualified);
                render(qualified, owner, true);
            }
        }
    }

    /**
     * The scope a generated class sees besides its imports: the member types it declares or inherits, by simple
     * name, and whether a top-level type of its package takes a simple name.
     *
     * <p>A simple name that two different member types of the interface the class implements or of its
     * superinterfaces take, wherever they are in that hierarchy, maps to no qualified name a type could equal, so
     * every type of that name is written qualified. That covers two member types the class inherits from two
     * interfaces, where the name resolves to neither, and one that hides another of its name from a superinterface,
     * where the name resolves to the hiding one: a qualified name is correct in both. A member type the class
     * declares itself takes its name whatever the interfaces hold.</p>
     */
    record Scope(Map<String, String> members, Predicate<String> packageTypes) {

        private static final String AMBIGUOUS = "";

        Scope {
            members = Map.copyOf(members);
        }

        /**
         * The scope of a class of {@code packageElement} that implements {@code implemented} and declares the
         * member types {@code declared}, simple name to qualified name, which hide the ones it inherits.
         *
         * <p>Whether a top-level type of the package takes a simple name is asked of {@code elements} by that
         * type's qualified name, for that name alone and only when the file needs to know: for a {@code java.lang}
         * type it writes and, in a file of the unnamed package, for every type it would import. That lookup finds
         * the type wherever javac finds it when it resolves the simple name as a type of that package: among the
         * sources it compiles, on the source path or on the class path. A type declared in a source file named
         * after another type, an auxiliary class, is found only once javac has read that file, which may come
         * after this lookup. A member type the qualified name resolves to, as it does when a class of the unnamed
         * package is named like the first segment of the package and nests a type of that canonical name, is no
         * type of the package and does not count.</p>
         *
         * <p>Unlike a listing of the package's members, the lookup does not make javac read every file of the
         * package that the source path holds, among them files the build leaves out of the compilation. It does
         * read a file named after a type it asks for, which in the unnamed package can be one that javac would not
         * read to compile the file: a file named like a type the file imports.</p>
         */
        static Scope of(Elements elements, PackageElement packageElement, TypeElement implemented,
                        Map<String, String> declared) {
            Map<String, String> members = new LinkedHashMap<>();
            inherit(implemented, members, new LinkedHashSet<>());
            members.putAll(declared);
            String packageName = packageElement.getQualifiedName().toString();
            return new Scope(members, (String simple) -> {
                TypeElement type = elements.getTypeElement(packageName.isEmpty() ? simple : packageName + '.' + simple);
                return type != null && type.getEnclosingElement() instanceof PackageElement owner
                        && owner.getQualifiedName().contentEquals(packageName);
            });
        }

        /**
         * Every member type of {@code type} and of its superinterfaces, by simple name; a name two different ones
         * take maps to {@code AMBIGUOUS}. An interface's member types are all public, so a class implementing it
         * inherits every one it does not hide.
         */
        private static void inherit(TypeElement type, Map<String, String> members, Set<String> visited) {
            if (!visited.add(type.getQualifiedName().toString())) {
                return;
            }
            for (Element member : type.getEnclosedElements()) {
                if (member instanceof TypeElement nested) {
                    String qualified = nested.getQualifiedName().toString();
                    String previous = members.putIfAbsent(nested.getSimpleName().toString(), qualified);
                    if (previous != null && !previous.equals(qualified)) {
                        members.put(nested.getSimpleName().toString(), AMBIGUOUS);
                    }
                }
            }
            for (TypeMirror supertype : type.getInterfaces()) {
                if (supertype instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
                    inherit(element, members, visited);
                }
            }
        }
    }

    /** The package that declares {@code type}, the unnamed one included, however deeply the type is nested. */
    static PackageElement packageOf(Element type) {
        Element current = type;
        while (!(current instanceof PackageElement)) {
            current = current.getEnclosingElement();
        }
        return (PackageElement) current;
    }

    /** {@code types} with {@code type} claimed after the types already in it, keyed by its qualified name. */
    static void claim(Map<String, String> types, TypeElement type) {
        types.putIfAbsent(type.getQualifiedName().toString(), packageOf(type).getQualifiedName().toString());
    }

    /** The same for a top-level type known by its qualified name only, whose package is everything before the last dot. */
    static void claim(Map<String, String> types, String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        types.putIfAbsent(qualifiedName, dot < 0 ? "" : qualifiedName.substring(0, dot));
    }

    /** The single-type imports of the file, in claim order. */
    Set<String> imports() {
        return imports;
    }

    /**
     * How the file writes the type: its simple name when that name resolves to it, its qualified name otherwise.
     *
     * @throws IllegalArgumentException if the file never claimed {@code qualifiedName}
     */
    String of(String qualifiedName) {
        String name = rendered.get(qualifiedName);
        if (name == null) {
            throw new IllegalArgumentException(qualifiedName + " is not a type this file claimed");
        }
        return name;
    }

    /**
     * How the file writes the type where an expression reads a member of it, a method it calls or a constant it
     * reads: as {@link #of} writes it, noting the identifier that heads that name as one an expression reads, where
     * a field of that name would be read instead.
     *
     * @throws IllegalArgumentException if the file never claimed {@code qualifiedName}
     */
    String qualifier(String qualifiedName) {
        String name = of(qualifiedName);
        int dot = name.indexOf('.');
        expressions.putIfAbsent(dot < 0 ? name : name.substring(0, dot), qualifiedName);
        return name;
    }

    /**
     * What the file writes at the head of a type reference: the first segment of every qualified name, a package
     * or a top-level type of the unnamed package, and every simple name, with the ones that {@link #qualifier}
     * noted so far.
     */
    Written written() {
        return new Written(roots, typeRoots, simpleNames, expressions);
    }

    /**
     * Writes the type of {@code owner}, its package, by its simple name, or else by its qualified name, whose first
     * segment is a package or, for a type of the unnamed package, the top-level type it is or is nested in.
     */
    private void render(String qualifiedName, String owner, boolean bySimpleName) {
        if (bySimpleName) {
            String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            rendered.put(qualifiedName, simple);
            simpleNames.putIfAbsent(simple, qualifiedName);
            return;
        }
        rendered.put(qualifiedName, qualifiedName);
        int dot = qualifiedName.indexOf('.');
        Map<String, String> heads = owner.isEmpty() ? typeRoots : roots;
        heads.putIfAbsent(dot < 0 ? qualifiedName : qualifiedName.substring(0, dot), qualifiedName);
    }
}
