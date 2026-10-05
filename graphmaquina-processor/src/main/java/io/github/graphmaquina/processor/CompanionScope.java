package io.github.graphmaquina.processor;

import io.github.graphmaquina.processor.gen.Written;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * Refuses, as {@code SG603}, a facade or a set whose generated companion would write a name that the companion's
 * own scope takes, and says so of a companion already written when a type that appears later takes one.
 *
 * <p>A qualified name starts with a package. Inside the companion's body, a type of that simple name hides the
 * package wherever the name heads one: a member type the companion declares or inherits, the companion itself, a
 * type it names by its simple name, a type of the companion's package, or a type of {@code java.lang}. A field of
 * that name obscures the package only where an expression reads the name, where the companion calls or reads a
 * member of the type or an annotation value names an enum constant (JLS 6.5.2); in a type position, such as a
 * parameter type, a class literal or a cast, a field takes nothing (JLS 6.5.4.1). The fields that count are a
 * constant the facade or the set declares or inherits, and a field the companion declares under a name it cannot
 * choose, such as a machine companion's {@code KEY}. No spelling of a qualified name gets past either. The
 * companion's imports are outside the scope of all of these, so only the names its body writes count.</p>
 *
 * <p>The qualified name of a type of the unnamed package starts instead with the top-level type it is or is nested
 * in, which the companion, generated in the unnamed package as well, sees as a type of its own package. A member
 * type, a type of another package that the companion names by its simple name, or the companion's own name takes
 * that top-level type's name in the same way, and a field does where an expression reads it; the type has no other
 * name.</p>
 *
 * <p>Any other simple name the companion writes for a type resolves to that type, since the generators choose it
 * so, but a field of that name takes it where an expression reads it all the same.</p>
 *
 * <p>The types of the companion's package include the companions this processor reserves there, in the round that
 * checks the companion or an earlier one, though they are not written yet. A companion reserved in a later round
 * is checked against the companions of its package written before it, and refused where its facade or set is
 * declared when it would take a name one of them writes. A type that another processor generates into the package,
 * in the round that writes the companion or a later one, can still take such a name. The companion is written by
 * then, and from then on the name means that type in it, which javac may refuse, as it refuses a class named like
 * a package the companion writes, or may not, as it accepts an annotation type named {@code Override} in place of
 * {@code java.lang.Override}. The next call of the processor reports, at the companion's facade or set, which name
 * the type took. A type generated in the last round, the one whose {@code processingOver()} is true, is not
 * checked: no call of the processor follows that round.</p>
 *
 * <p>What takes a name is a declaration of the build, a type, member type or constant of the author's or of a
 * library the facade or the set extends, a companion named after a facade or a set, or a type another processor
 * generates, or something fixed: the companion's own {@code KEY} and {@code Builder}, the library and JDK types it
 * names, and {@code java.lang}. Each diagnostic names what takes the name, and what the author can rename to free
 * it:</p>
 * <ul>
 *   <li>for the name of a package, or of a top-level type of the unnamed package: that package or type, and also
 *       what takes the name when that is a type of the package other than a companion, a member type the companion
 *       inherits, a constant, a type of a later round, or the facade or set whose companion would take it;</li>
 *   <li>for a simple name an expression reads and a field takes: that field when it is a constant, and otherwise
 *       the type whose name it is;</li>
 *   <li>for the simple name of a {@code java.lang} type that a type of a later round takes, or that a companion
 *       reserved in a later round would take: that type, or that companion's facade or set.</li>
 * </ul>
 */
final class CompanionScope {

    private static final String COLLISION = "SG603";

    private final Elements elements;
    private final Diagnostics diagnostics;
    private final Map<String, TypeElement> companions;
    private final Map<String, List<WrittenCompanion>> written = new LinkedHashMap<>();
    private final Set<String> reported = new HashSet<>();

    /**
     * {@code companions} maps the qualified name of every companion the processor reserves in this compilation to
     * the facade or the set it is for; the processor reserves the names of a round's companions before it checks
     * any of them.
     */
    CompanionScope(Elements elements, Diagnostics diagnostics, Map<String, TypeElement> companions) {
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.companions = companions;
    }

    /** What takes a name in the companion's scope, and what the author can rename to free it, empty when nothing. */
    private record Taker(String what, String remedy) { }

    /**
     * A companion already written: its qualified name, the qualified name of the facade or set it is for, and what
     * it writes. The facade or set is kept by name and looked up again when a later round reports against it.
     */
    private record WrittenCompanion(String name, String declaration, Written written) {

        /** {@code the companion <simple name> of <declaration>}, as a diagnostic names it. */
        String described() {
            return "the companion " + name.substring(name.lastIndexOf('.') + 1) + " of " + declaration;
        }

        /**
         * What this companion writes whose first identifier a top-level type of its package named {@code simple}
         * would take: a qualified name starting with the package {@code simple}, or the {@code java.lang} type it
         * writes by that simple name, which a type of its package hides. Nothing else it writes can be taken by a
         * type that appears later: an import hides such a type, a member type hides it as well, and a type of its
         * own package cannot be declared twice.
         */
        Optional<String> takenBy(String simple) {
            if (written.roots().containsKey(simple)) {
                return Optional.of(written.roots().get(simple));
            }
            String type = written.simpleNames().get(simple);
            return ("java.lang." + simple).equals(type)
                    ? Optional.of("the type " + type + " as " + simple) : Optional.empty();
        }
    }

    /**
     * Notes that the companion {@code name} of {@code declaration} was written, writing what {@code written} holds,
     * so that a type of its package that a later round brings is checked against it.
     */
    void wrote(String name, TypeElement declaration, Written written) {
        this.written.computeIfAbsent(elements.getPackageOf(declaration).getQualifiedName().toString(),
                (String packageName) -> new ArrayList<>())
                .add(new WrittenCompanion(name, declaration.getQualifiedName().toString(), written));
    }

    /**
     * Whether the companion {@code name} that this round reserves for {@code declaration} takes no name from a
     * companion of its package written in an earlier round; {@code SG603} at {@code declaration} otherwise, whose
     * companion is not written yet.
     */
    boolean admitsLater(TypeElement declaration, String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        boolean admitted = true;
        for (WrittenCompanion earlier : earlier(elements.getPackageOf(declaration).getQualifiedName().toString())) {
            Optional<String> taken = earlier.takenBy(simple);
            if (taken.isPresent()) {
                boolean root = earlier.written().roots().containsKey(simple);
                diagnostics.error(COLLISION, earlier.described() + ", written in an earlier round, writes " + taken.get()
                        + ", but in its scope " + simple + " would name the companion " + name + " of "
                        + declaration.getQualifiedName() + ", which this round reserves" + (root
                        ? ", not a package: rename " + declaration.getQualifiedName() + " or the package " + simple
                        : ": rename " + declaration.getQualifiedName()), declaration);
                admitted = false;
            }
        }
        return admitted;
    }

    /**
     * {@code SG603} at the facade or set of every companion already written whose package now holds a top-level
     * type, other than a companion of this processor, that takes a name the companion writes. No such type was a
     * member of the package when the companion was checked, so another processor generated it since. The companion
     * is written already and the name means that type in it now, whether javac refuses that or not, so this says
     * so, once. It looks at the package itself rather than at a round's root elements: once a type a round brings
     * breaks a source of the compilation, javac may run only the last round next, whose root elements are empty. A
     * type generated in that last round is never seen: no call of the processor follows it.
     */
    void checkLaterTypes() {
        for (List<WrittenCompanion> ofPackage : written.values()) {
            for (WrittenCompanion earlier : ofPackage) {
                TypeElement declaration = elements.getTypeElement(earlier.declaration());
                for (TypeElement type : ElementFilter.typesIn(elements.getPackageOf(declaration).getEnclosedElements())) {
                    String simple = type.getSimpleName().toString();
                    Optional<String> taken = earlier.takenBy(simple);
                    if (taken.isEmpty() || companions.containsKey(type.getQualifiedName().toString())
                            || !reported.add(earlier.name() + " " + type.getQualifiedName())) {
                        continue;
                    }
                    boolean root = earlier.written().roots().containsKey(simple);
                    diagnostics.error(COLLISION, earlier.described() + ", written in an earlier round, writes "
                            + taken.get() + ", but in its scope " + simple + " now names the type "
                            + type.getQualifiedName() + ", new in a later round" + (root
                            ? ", not a package: rename that type or the package " + simple : ": rename that type"),
                            declaration);
                }
            }
        }
    }

    /** The companions of the package {@code packageName} written so far. */
    private List<WrittenCompanion> earlier(String packageName) {
        return written.getOrDefault(packageName, List.of());
    }

    /**
     * Whether the companion {@code companion} of {@code declaration}, a facade or a set, can write what
     * {@code written} holds; {@code SG603} at {@code declaration} for each name its scope takes otherwise.
     * {@code memberTypes} are the simple names of the member types the companion declares itself, and
     * {@code fields} the names of the fields it declares that it does not choose.
     */
    boolean admits(TypeElement declaration, String companion, Set<String> memberTypes, Set<String> fields, Written written) {
        Map<String, Taker> types = types(declaration, companion, memberTypes, written);
        Map<String, Taker> packageTypes = packageTypes(declaration);
        Map<String, Taker> variables = variables(declaration, companion, fields);
        String prefix = "the companion " + companion + " of " + declaration.getQualifiedName() + " writes ";
        boolean admitted = true;
        for (Map.Entry<String, String> root : written.roots().entrySet()) {
            String name = root.getKey();
            Taker taker = first(name, List.of(types, packageTypes));
            if (taker == null && elements.getTypeElement("java.lang." + name) != null) {
                taker = new Taker("the type java.lang." + name, "");
            }
            if (taker != null) {
                diagnostics.error(COLLISION, prefix + root.getValue() + ", but in its scope " + name + " names "
                        + taker.what() + ", not a package: " + remedy(taker, "the package " + name), declaration);
                admitted = false;
            } else if (written.expressions().containsKey(name) && variables.containsKey(name)) {
                diagnostics.error(COLLISION, prefix + written.expressions().get(name) + ", but in its expressions "
                        + name + " names " + variables.get(name).what() + ", not a package: "
                        + remedy(variables.get(name), "the package " + name), declaration);
                admitted = false;
            }
        }
        for (Map.Entry<String, String> root : written.typeRoots().entrySet()) {
            String name = root.getKey();
            if (types.containsKey(name)) {
                diagnostics.error(COLLISION, prefix + root.getValue() + ", but in its scope " + name + " names "
                        + types.get(name).what() + ", not the type " + name + " of the unnamed package: "
                        + remedy(types.get(name), "the type " + name), declaration);
                admitted = false;
            } else if (written.expressions().containsKey(name) && variables.containsKey(name)) {
                diagnostics.error(COLLISION, prefix + written.expressions().get(name) + ", but in its expressions "
                        + name + " names " + variables.get(name).what() + ", not the type " + name
                        + " of the unnamed package: " + remedy(variables.get(name), "the type " + name), declaration);
                admitted = false;
            }
        }
        for (Map.Entry<String, String> simple : written.simpleNames().entrySet()) {
            Taker taker = variables.get(simple.getKey());
            if (taker != null && written.expressions().containsKey(simple.getKey())) {
                diagnostics.error(COLLISION, prefix + "the type " + simple.getValue() + " as " + simple.getKey()
                        + ", but in its expressions " + simple.getKey() + " names " + taker.what() + ": "
                        + (taker.remedy().isEmpty() ? "rename the type " + simple.getValue() : taker.remedy()),
                        declaration);
                admitted = false;
            }
        }
        return admitted;
    }

    /** What the first of {@code scopes} that holds {@code name} holds for it, {@code null} when none does. */
    private static Taker first(String name, List<Map<String, Taker>> scopes) {
        for (Map<String, Taker> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

    /** What the author can rename: what {@code taker} names or {@code alternative}, or {@code alternative} alone. */
    private static String remedy(Taker taker, String alternative) {
        return taker.remedy().isEmpty() ? "rename " + alternative : taker.remedy() + " or " + alternative;
    }

    /**
     * Every type of the companion's scope that is not a top-level type of the author's in its package, by simple
     * name and with what declares it: the companion itself; the types it writes by their simple name, which it
     * imports, takes from {@code java.lang} or inherits; the member types it inherits from {@code declaration};
     * and the member types it declares itself. Where two share a name, the later one hides the earlier one.
     */
    private Map<String, Taker> types(TypeElement declaration, String companion, Set<String> memberTypes, Written written) {
        String packageName = elements.getPackageOf(declaration).getQualifiedName().toString();
        Map<String, Taker> types = new LinkedHashMap<>();
        types.put(companion, new Taker("the companion " + companion + " itself", ""));
        for (Map.Entry<String, String> simple : written.simpleNames().entrySet()) {
            String ownPackage = packageName.isEmpty() ? simple.getKey() : packageName + "." + simple.getKey();
            if (!simple.getValue().equals(ownPackage)) {
                types.put(simple.getKey(), new Taker("the type " + simple.getValue(), ""));
            }
        }
        for (TypeElement member : ElementFilter.typesIn(elements.getAllMembers(declaration))) {
            types.put(member.getSimpleName().toString(),
                    new Taker("the member type " + member.getQualifiedName(), "rename that member type"));
        }
        for (String member : memberTypes) {
            types.put(member, new Taker("the member type " + companion + "." + member + " the companion declares", ""));
        }
        return types;
    }

    /**
     * The simple name of every top-level type of the package of {@code declaration}, each with its qualified name,
     * and of every companion the processor reserves in that package, each with what it is for.
     */
    private Map<String, Taker> packageTypes(TypeElement declaration) {
        PackageElement own = elements.getPackageOf(declaration);
        Map<String, Taker> types = new LinkedHashMap<>();
        for (Element member : own.getEnclosedElements()) {
            if (member instanceof TypeElement type) {
                types.put(type.getSimpleName().toString(), new Taker("the type " + type.getQualifiedName(), "rename that type"));
            }
        }
        for (Map.Entry<String, TypeElement> companion : companions.entrySet()) {
            if (elements.getPackageOf(companion.getValue()).equals(own)) {
                String name = companion.getKey().substring(companion.getKey().lastIndexOf('.') + 1);
                types.put(name, new Taker("the companion " + companion.getKey() + " this processor reserves for "
                        + companion.getValue().getQualifiedName(), ""));
            }
        }
        return types;
    }

    /** The name of every field in the companion's scope that it does not choose, each with what declares it. */
    private Map<String, Taker> variables(TypeElement declaration, String companion, Set<String> fields) {
        Map<String, Taker> variables = new LinkedHashMap<>();
        for (VariableElement field : ElementFilter.fieldsIn(elements.getAllMembers(declaration))) {
            variables.putIfAbsent(field.getSimpleName().toString(), new Taker("the constant "
                    + field.getEnclosingElement().getSimpleName() + "." + field.getSimpleName(), "rename that constant"));
        }
        for (String field : fields) {
            variables.put(field, new Taker("the field " + companion + "." + field + " the companion declares", ""));
        }
        return variables;
    }
}
