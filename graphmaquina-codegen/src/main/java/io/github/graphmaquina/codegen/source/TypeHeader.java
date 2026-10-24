package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.model.SourceLocation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import lombok.NonNull;

/**
 * The header of one type declaration, read from an original source or materialized from a classpath binary.
 * A header keeps the canonical identity of the declaration, the type parameters it declares, its direct
 * supertypes, its members, the annotations written on it and, for an enum, its constants in declaration
 * order. Of a body, only the local and anonymous classes it declares are read: the annotations written in them, and
 * the member types and supertypes that decide what those annotations name, so nothing here depends on attributing
 * an expression.
 *
 * @param canonicalName the canonical name of the declaration, for example {@code fx.Holder.Status}
 * @param kind the declaration kind, one of {@code CLASS}, {@code INTERFACE}, {@code ENUM}, {@code RECORD} or
 *     {@code ANNOTATION_TYPE}
 * @param modifiers the modifiers of the declaration: for a classpath header, as javac records them; for a source,
 *     the written ones plus the {@code public} and {@code static} the language implies for a nested declaration,
 *     such as {@code static} on a nested enum, but no other implied modifier
 * @param typeParameters the simple names of the declared type parameters, in declaration order
 * @param supertypes the direct supertypes, in declaration order, still spelled in this header's scope when
 *     the header comes from a source
 * @param members the declared methods and fields; constructors, initializers and enum constants are not
 *     members of a header
 * @param markers the annotations written on the declaration, unresolved until a caller needs one
 * @param localMarkers the annotations written in a local or anonymous class the declaration's own body declares, at
 *     any depth, on the class or on one of its methods and fields, unresolved like {@code markers}, each with the
 *     class bodies that decide what its name means; always empty for a classpath header
 * @param constants the enum constants in declaration order, empty for every other kind
 * @param scope the lexical context this header's spelled names resolve in
 * @param origin the declaration's position in its original source, absent for a classpath header
 */
public record TypeHeader(@NonNull String canonicalName, @NonNull ElementKind kind, Set<Modifier> modifiers,
        List<String> typeParameters, List<TypeUse> supertypes, List<Member> members,
        List<Marker> markers, List<LocalMarker> localMarkers, List<String> constants, @NonNull Scope scope,
        @NonNull Optional<SourceLocation> origin) {

    public TypeHeader {
        modifiers = Set.copyOf(modifiers);
        typeParameters = List.copyOf(typeParameters);
        supertypes = List.copyOf(supertypes);
        members = List.copyOf(members);
        markers = List.copyOf(markers);
        localMarkers = List.copyOf(localMarkers);
        constants = List.copyOf(constants);
    }

    /** The identity of the type parameter this declaration declares at {@code position}. */
    public TypeParameterKey parameterKey(int position) {
        return new TypeParameterKey(canonicalName, position);
    }

    /** The last segment of the canonical name. */
    public String simpleName() {
        return canonicalName.substring(canonicalName.lastIndexOf('.') + 1);
    }

    /**
     * One declared method or field of a header.
     *
     * @param name the member's simple name
     * @param kind {@code METHOD} or {@code FIELD}
     * @param modifiers the modifiers written on, or recorded for, the member
     * @param typeParameters the simple names of the type parameters the member itself declares
     * @param parameterTypes the formal parameter types of a method, empty for a field
     * @param type the return type of a method or the declared type of a field
     * @param markers the annotations written on the member
     * @param origin the member's position in its original source, absent for a classpath header
     */
    public record Member(@NonNull String name, @NonNull ElementKind kind, Set<Modifier> modifiers,
            List<String> typeParameters, List<TypeUse> parameterTypes, @NonNull TypeUse type, List<Marker> markers,
            @NonNull Optional<SourceLocation> origin) {

        public Member {
            modifiers = Set.copyOf(modifiers);
            typeParameters = List.copyOf(typeParameters);
            parameterTypes = List.copyOf(parameterTypes);
            markers = List.copyOf(markers);
        }
    }

    /**
     * One annotation written on a declaration, kept as spelled. Its target is resolved only when a caller
     * needs that particular marker, so an annotation the generator is still about to produce never fails a
     * header that does not depend on it.
     *
     * @param name the annotation type as spelled at the use site
     * @param classLiterals the {@code X.class} arguments of the annotation, spelled and in order
     * @param origin the annotation's position in its original source, absent for a classpath header
     */
    public record Marker(@NonNull String name, List<String> classLiterals, @NonNull Optional<SourceLocation> origin) {

        public Marker {
            classLiterals = List.copyOf(classLiterals);
        }
    }

    /**
     * One annotation written in a local or anonymous class that a member of a source declaration declares, with what
     * javac reads its name against before the body of that declaration: the bodies of the classes between the member
     * and the annotation, and the local classes the member declares.
     *
     * @param marker the annotation as written
     * @param scopes the bodies of the classes that enclose the annotation inside the member, innermost first: for an
     *     annotation on one of their methods or fields, the class that declares it comes first; for one on a class
     *     itself, which javac reads outside that class's body, the class that encloses it does
     * @param localTypes the simple names of every local class the member declares, in any block: one of them may be
     *     what a name means where the annotation is written
     */
    public record LocalMarker(@NonNull Marker marker, List<LocalScope> scopes, Set<String> localTypes) {

        public LocalMarker {
            scopes = List.copyOf(scopes);
            localTypes = Set.copyOf(localTypes);
        }
    }

    /**
     * The body of a class declared inside a member of a source declaration, a local or anonymous class or a member
     * class of one, as far as it decides what a name written in it means: a member type it declares, or inherits from
     * one of its supertypes, takes that name over.
     *
     * @param memberTypes the simple names of the member types the class declares
     * @param supertypes the supertypes the class spells, without their type arguments; empty when the source cannot
     *     tell them, for an anonymous class created through a qualified instance creation, whose supertype is a
     *     member of the type of an expression. The supertype of the class body of an enum constant is its enum, whose
     *     body is the next one out, so such a body spells none.
     */
    public record LocalScope(List<String> memberTypes, Optional<List<String>> supertypes) {

        public LocalScope {
            memberTypes = List.copyOf(memberTypes);
            supertypes = supertypes.map(List::copyOf);
        }
    }

    /**
     * The lexical context a source header's spelled names resolve in. Static and non-static imports are kept
     * alike because both make a nested type reachable by its simple name.
     *
     * @param packageName the declaring package, empty for the unnamed package and for a classpath header
     * @param singleImports the fully spelled targets of every single import, for example {@code a.b.C}
     * @param onDemandImports the prefixes of every on-demand import, for example {@code a.b}
     */
    public record Scope(@NonNull String packageName, List<String> singleImports, List<String> onDemandImports) {

        /** The scope of a classpath header, which spells every name canonically already. */
        public static final Scope BINARY = new Scope("", List.of(), List.of());

        public Scope {
            singleImports = List.copyOf(singleImports);
            onDemandImports = List.copyOf(onDemandImports);
        }
    }
}
