package io.github.graphmaquina.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * Finds a type by its canonical name, the name of a top-level or of a member type, for the names a manifest carries
 * and the ones the processor names itself: by the package of the code a name is read for, or, for a type that declares
 * a member of a host, among that host and its supertypes.
 *
 * <p>{@link Elements#getTypeElement} first looks a name up in javac's table of the classes it has entered, keyed by
 * module and by flat name, and a flat name is the canonical name of a top-level type only. A name that table does not
 * hold for the module of the compilation, as the canonical name of every member type, of every type of another module
 * such as the JDK's, and of every top-level type javac has not entered yet, such as a type of a dependency's package
 * that nothing in the compilation refers to, javac resolves as if it were written in a compilation unit of the unnamed
 * package of that module and, only when that finds nothing, in each other module. There a top-level type of the
 * unnamed package named like the first segment of the name obscures the package of that name (JLS 6.4.2), so the
 * lookup can answer no type, or a member type of that class with the same canonical name.</p>
 *
 * <p>That member type is what the name means where code of the unnamed package spells it, while code of a named
 * package never sees a type of the unnamed package and means a type of the named package. So
 * {@link #type(Elements, String, String)} looks the name up for the package of the code it is read for:</p>
 * <ul>
 *   <li>For the unnamed package, the top-level type of that package named like the first segment is looked up first,
 *       then every further segment as a member type that the type found so far declares, by its simple name. That type
 *       is answered when every segment matches. Otherwise the name is looked up as for a named package, which answers
 *       the type that code of the unnamed package reaches by that name when no class obscures it, or else through an
 *       import, in which no class of the unnamed package is in scope (JLS 6.3).</li>
 *   <li>For a named package, the name is split at each of its dots in turn, the last one first, into a package and the
 *       name of a type in it. That name's first segment is looked up as a top-level type of the package, every further
 *       one as a member type that the type found so far declares, by its simple name, and a type is answered only when
 *       every segment matches. When no split gives a type and a top-level type of the unnamed package is named like the
 *       first segment, the splits are tried again, each package looked up first, which makes javac enter its types,
 *       and the type then looked up in the module of that package when the compilation has modules. A type of the
 *       unnamed package is never answered. In a layout JLS 7.1 forbids, where a class and a package of the same name
 *       both hold a type of the name, the package's type can be answered where javac binds the class's member
 *       type.</li>
 * </ul>
 *
 * <p>A type that declares a member of a host is reached through the supertypes of the host, which the host's code may
 * name through an import, or which a supertype declared in another package names, so its canonical name need not mean
 * it in the host's package. So {@link #declaring} takes it from the hierarchy of the host as javac bound it, and looks
 * its name up for the host's package only when no type of that hierarchy has the name. That hierarchy can hold two
 * types of the name: a member type of a class of the unnamed package, and the type of the package that the class
 * obscures. The caller gives a test of the member, and the first of them met from the host that declares an element
 * passing that test is taken, or the first met when neither does. For the field or the getter of a property, that test
 * also requires the mark the generator read, which the caller does not check afterwards: a declaration that has lost
 * its mark since then is looked for in the first type of the name met, which may be the homonym.</p>
 *
 * <p>Looking a package up makes javac list the names of its files unless it has already, as it does for every package
 * it reads a type of, and read none of them; the members of a package are never asked for here.</p>
 */
final class CanonicalNames {

    private CanonicalNames() { }

    /** The type {@code canonicalName} names in code of a named package, or {@code null} when no lookup finds one. */
    static TypeElement type(Elements elements, String canonicalName) {
        return named(elements, segments(canonicalName));
    }

    /**
     * The type {@code canonicalName} names in code of the package {@code referencingPackage}, empty for the unnamed
     * package; for the unnamed package, when no type of that package has the name, the type it names in code of a
     * named package. {@code null} when no lookup finds one.
     */
    static TypeElement type(Elements elements, String canonicalName, String referencingPackage) {
        List<String> segments = segments(canonicalName);
        if (referencingPackage.isEmpty()) {
            TypeElement member = members(topLevel(elements.getTypeElement(segments.get(0)), segments, 0), segments, 0);
            if (member != null) {
                return member;
            }
        }
        return named(elements, segments);
    }

    /**
     * The type {@code canonicalName} names as the declaring type of a member of {@code host} that {@code member}
     * accepts: {@code host} itself or one of its supertypes, direct or not, as javac bound them, met breadth first from
     * {@code host}, a superclass before the interfaces. That is the first of them whose qualified name it is that
     * declares an element {@code member} accepts, or, when none of those does, the first of them whose qualified name
     * it is. When none of them has that name, the type it names in code of the host's package, as
     * {@link #type(Elements, String, String)} finds it, or {@code null} when there is none.
     */
    static TypeElement declaring(Elements elements, TypeElement host, String canonicalName, Predicate<Element> member) {
        TypeElement first = null;
        Deque<TypeElement> pending = new ArrayDeque<>(List.of(host));
        Set<TypeElement> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            TypeElement type = pending.removeFirst();
            if (!seen.add(type)) {
                continue;
            }
            if (type.getQualifiedName().contentEquals(canonicalName)) {
                for (Element enclosed : type.getEnclosedElements()) {
                    if (member.test(enclosed)) {
                        return type;
                    }
                }
                if (first == null) {
                    first = type;
                }
            }
            List<TypeMirror> supertypes = new ArrayList<>(type.getInterfaces());
            supertypes.add(0, type.getSuperclass());
            for (TypeMirror supertype : supertypes) {
                if (supertype.getKind() == TypeKind.DECLARED && supertype instanceof DeclaredType declared
                        && declared.asElement() instanceof TypeElement element) {
                    pending.addLast(element);
                }
            }
        }
        return first != null
                ? first
                : type(elements, canonicalName, elements.getPackageOf(host).getQualifiedName().toString());
    }

    /**
     * The package of a type that a top-level type declares, given by its canonical name, as the generator names every
     * vocabulary annotation and every symbol type: that name without its last two segments, empty for the unnamed
     * package.
     */
    static String packageOfMemberType(String canonicalName) {
        int member = canonicalName.lastIndexOf('.');
        int container = member < 0 ? -1 : canonicalName.lastIndexOf('.', member - 1);
        return container < 0 ? "" : canonicalName.substring(0, container);
    }

    /** The type {@code segments} name in code of a named package, or {@code null} when no lookup finds one. */
    private static TypeElement named(Elements elements, List<String> segments) {
        for (int top = segments.size() - 1; top > 0; top--) {
            TypeElement found = members(topLevel(elements.getTypeElement(prefix(segments, top + 1)), segments, top),
                    segments, top);
            if (found != null) {
                return found;
            }
        }
        TypeElement obscuring = topLevel(elements.getTypeElement(segments.get(0)), segments, 0);
        if (obscuring == null) {
            return null;
        }
        ModuleElement unnamed = elements.getModuleOf(obscuring);
        for (int top = segments.size() - 1; top > 0; top--) {
            PackageElement owner = unnamed == null ? elements.getPackageElement(prefix(segments, top))
                    : elements.getPackageElement(unnamed, prefix(segments, top));
            if (owner == null) {
                continue;
            }
            ModuleElement module = elements.getModuleOf(owner);
            String name = prefix(segments, top + 1);
            TypeElement found = members(topLevel(module == null ? elements.getTypeElement(name)
                    : elements.getTypeElement(module, name), segments, top), segments, top);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * {@code candidate} when it is the top-level type the segments up to {@code top} name: a type of the package
     * the segments before {@code top} name, whose simple name is the one at {@code top}; {@code null} otherwise.
     */
    private static TypeElement topLevel(TypeElement candidate, List<String> segments, int top) {
        return candidate != null && candidate.getEnclosingElement() instanceof PackageElement owner
                && owner.getQualifiedName().contentEquals(prefix(segments, top))
                && candidate.getSimpleName().contentEquals(segments.get(top)) ? candidate : null;
    }

    /**
     * The member type that {@code type} declares under the segment after {@code top}, the member type that one
     * declares under the next segment, and so on to the last; {@code type} itself when {@code top} is the last
     * segment, and {@code null} when {@code type} is {@code null} or one of these types declares no member type of the
     * next segment's name.
     */
    private static TypeElement members(TypeElement type, List<String> segments, int top) {
        TypeElement found = type;
        for (int at = top + 1; found != null && at < segments.size(); at++) {
            found = member(found, segments.get(at));
        }
        return found;
    }

    /** The member type {@code type} declares whose simple name is {@code simple}, {@code null} when there is none. */
    private static TypeElement member(TypeElement type, String simple) {
        for (TypeElement member : ElementFilter.typesIn(type.getEnclosedElements())) {
            if (member.getSimpleName().contentEquals(simple)) {
                return member;
            }
        }
        return null;
    }

    /** The segments of {@code canonicalName}, the identifiers its dots separate. */
    private static List<String> segments(String canonicalName) {
        return List.of(canonicalName.split("\\.", -1));
    }

    /** The first {@code count} segments, joined by dots: a package or a type name, empty for none. */
    private static String prefix(List<String> segments, int count) {
        return String.join(".", segments.subList(0, count));
    }
}
