package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.model.SourceLocation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/**
 * The header of one type declaration, read from an original source or materialized from a classpath binary.
 * A header keeps the canonical identity of the declaration, the type parameters it declares, its direct
 * supertypes, its members, the annotations written on it and, for an enum, its constants in declaration
 * order. Bodies are never read, so nothing here depends on attributing an expression.
 *
 * @param canonicalName the canonical name of the declaration, for example {@code fx.Holder.Status}
 * @param kind the declaration kind, one of {@code CLASS}, {@code INTERFACE}, {@code ENUM}, {@code RECORD} or
 *     {@code ANNOTATION_TYPE}
 * @param modifiers the modifiers written on, or recorded for, the declaration
 * @param typeParameters the simple names of the declared type parameters, in declaration order
 * @param supertypes the direct supertypes, in declaration order, still spelled in this header's scope when
 *     the header comes from a source
 * @param members the declared methods and fields; constructors, initializers and enum constants are not
 *     members of a header
 * @param markers the annotations written on the declaration, unresolved until a caller needs one
 * @param constants the enum constants in declaration order, empty for every other kind
 * @param scope the lexical context this header's spelled names resolve in
 * @param origin the declaration's position in its original source, absent for a classpath header
 */
public record TypeHeader(String canonicalName, ElementKind kind, Set<Modifier> modifiers,
        List<String> typeParameters, List<TypeUse> supertypes, List<Member> members,
        List<Marker> markers, List<String> constants, Scope scope, Optional<SourceLocation> origin) {

    public TypeHeader {
        Objects.requireNonNull(canonicalName, "canonicalName");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(origin, "origin");
        modifiers = Set.copyOf(modifiers);
        typeParameters = List.copyOf(typeParameters);
        supertypes = List.copyOf(supertypes);
        members = List.copyOf(members);
        markers = List.copyOf(markers);
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
    public record Member(String name, ElementKind kind, Set<Modifier> modifiers, List<String> typeParameters,
            List<TypeUse> parameterTypes, TypeUse type, List<Marker> markers,
            Optional<SourceLocation> origin) {

        public Member {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(origin, "origin");
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
    public record Marker(String name, List<String> classLiterals, Optional<SourceLocation> origin) {

        public Marker {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(origin, "origin");
            classLiterals = List.copyOf(classLiterals);
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
    public record Scope(String packageName, List<String> singleImports, List<String> onDemandImports) {

        /** The scope of a classpath header, which spells every name canonically already. */
        public static final Scope BINARY = new Scope("", List.of(), List.of());

        public Scope {
            Objects.requireNonNull(packageName, "packageName");
            singleImports = List.copyOf(singleImports);
            onDemandImports = List.copyOf(onDemandImports);
        }
    }
}
