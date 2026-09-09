package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.StateAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Turns a property symbol into the accessors one use needs.
 *
 * <p>A marker on a getter exports that reader and no other: the declaration it names is resolved on the type
 * that declares it and then read as a member of the entity, so an accessor inherited from a generic base is
 * seen with the entity's own arguments. A marker on a field exports the property its name spells, and the
 * accessors are searched by the conventions, readers as {@code p()}, {@code getP()}, {@code isP()} and writers
 * as {@code setP(x)} before {@code p(x)}. Within one writer alternative the most specific parameter wins;
 * two that no rule orders are reported rather than decided by the order the members are found in.</p>
 */
final class PropertyResolver {

    private static final String WRONG_HOST = "SG605";
    private static final String NO_ACCESSOR = "SG503";
    private static final String COLLECTION = "java.util.Collection";
    private static final String OPTIONAL = "java.util.Optional";

    private final Map<String, SymbolDescriptor> symbols;
    private final Signatures signatures;
    private final Accessors accessors;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    PropertyResolver(Map<String, SymbolDescriptor> symbols, Signatures signatures, Accessors accessors,
            Diagnostics diagnostics) {
        this.symbols = symbols;
        this.signatures = signatures;
        this.accessors = accessors;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /** The identity reader of {@code machine}: a public instance method taking nothing and returning a value. */
    Optional<Property> id(TypeMirror symbol, MachineTypes machine, DiagnosticAnchor anchor) {
        return declaration(symbol, machine, anchor)
                .flatMap((PropertyDescriptor property) -> read(property, machine, returned -> true, "a value", anchor));
    }

    /** The state reader and writer of {@code machine}: the reader returns its states, the writer accepts them. */
    Optional<StateAccess> state(TypeMirror symbol, MachineTypes machine, DiagnosticAnchor anchor) {
        Optional<PropertyDescriptor> declaration = declaration(symbol, machine, anchor);
        if (declaration.isEmpty()) {
            return Optional.empty();
        }
        Optional<Property> reader = read(declaration.get(), machine,
                (TypeMirror returned) -> signatures.same(returned, machine.states()), machine.statesName(), anchor);
        Optional<Property> writer = write(declaration.get(), machine, machine.states(), anchor);
        return reader.isEmpty() || writer.isEmpty()
                ? Optional.empty()
                : Optional.of(new StateAccess(reader.get(), writer.get()));
    }

    /** The reader of {@code machine} that returns the peers a watch of {@code peer} follows. */
    Optional<Property> peers(TypeMirror symbol, MachineTypes machine, MachineTypes peer, DiagnosticAnchor anchor) {
        TypeMirror collection = signatures.declared(COLLECTION, signatures.extendsWildcard(peer.entity()));
        return declaration(symbol, machine, anchor).flatMap((PropertyDescriptor property) -> read(property, machine,
                (TypeMirror returned) -> signatures.assignable(returned, collection),
                "Collection<? extends " + peer.entityName() + ">", anchor));
    }

    /** The reader of the peer entity that returns the owner a watch reevaluates, directly or as an Optional. */
    Optional<Property> owner(TypeMirror symbol, MachineTypes machine, MachineTypes peer, DiagnosticAnchor anchor) {
        return declaration(symbol, peer, anchor).flatMap((PropertyDescriptor property) -> read(property, peer,
                (TypeMirror returned) -> signatures.same(returned, signatures.declared(OPTIONAL, machine.entity()))
                        || signatures.assignable(returned, machine.entity()),
                "Optional<" + machine.entityName() + "> or " + machine.entityName(), anchor));
    }

    /**
     * The property {@code symbol} names on the entity of {@code host}, or empty after reporting {@code SG605}
     * when it is not a property symbol of this compilation or names another entity.
     */
    private Optional<PropertyDescriptor> declaration(TypeMirror symbol, MachineTypes host, DiagnosticAnchor anchor) {
        Optional<SymbolResolver.Reference> reference = SymbolResolver.reference(symbol, SymbolKind.PROPERTY,
                anchor, symbols, annotations, diagnostics);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        SymbolDescriptor descriptor = reference.get().descriptor();
        if (!signatures.same(reference.get().host(), host.entity())) {
            anchor.report(diagnostics, WRONG_HOST, descriptor.qualifiedName() + " is a property of "
                    + descriptor.host() + ", not of " + host.entityName());
            return Optional.empty();
        }
        if (descriptor.property().isEmpty()) {
            anchor.report(diagnostics, WRONG_HOST, descriptor.qualifiedName() + " names no property declaration");
        }
        return descriptor.property();
    }

    /**
     * The reader of {@code property} whose returned type {@code accepts}, or empty after reporting
     * {@code SG503}. A marked getter is the only reader tried; a marked field is read by the conventions.
     */
    private Optional<Property> read(PropertyDescriptor property, MachineTypes host, Predicate<TypeMirror> accepts,
            String expectation, DiagnosticAnchor anchor) {
        for (Property candidate : readers(property, host.entity())) {
            TypeMirror returned = candidate.accessor().effectiveType().getReturnType();
            if (returned.getKind() != TypeKind.VOID && accepts.test(returned)) {
                return Optional.of(candidate);
            }
        }
        anchor.report(diagnostics, NO_ACCESSOR, host.entityName() + " has no public reader for property '"
                + property.logicalName() + "' returning " + expectation + describe(property));
        return Optional.empty();
    }

    /** The readers to try, in order: the marked getter alone, or the conventional names of a marked field. */
    private List<Property> readers(PropertyDescriptor property, DeclaredType entity) {
        List<Property> candidates = new ArrayList<>();
        if (property.directGetter()) {
            marked(property, entity).ifPresent((ExecutableElement method) ->
                    candidates.add(accessors.property(entity, method)));
            return candidates;
        }
        for (String name : Accessors.readerNames(property.logicalName())) {
            for (ExecutableElement method : accessors.methods(entity, name, 0)) {
                candidates.add(accessors.property(entity, method));
            }
        }
        return candidates;
    }

    /**
     * The writer of {@code property} that accepts {@code value}: the {@code setP} alternative before the
     * {@code p} one, and within an alternative the most specific parameter. Empty after reporting
     * {@code SG503} when none accepts it, or when two of one alternative are not ordered by specificity.
     */
    private Optional<Property> write(PropertyDescriptor property, MachineTypes host, TypeMirror value,
            DiagnosticAnchor anchor) {
        for (String name : Accessors.writerNames(property.logicalName())) {
            List<Property> fitting = new ArrayList<>();
            for (ExecutableElement method : accessors.methods(host.entity(), name, 1)) {
                Property candidate = accessors.property(host.entity(), method);
                if (signatures.assignable(value, parameter(candidate))) {
                    fitting.add(candidate);
                }
            }
            if (fitting.isEmpty()) {
                continue;
            }
            Optional<Property> selected = mostSpecific(fitting);
            if (selected.isEmpty()) {
                anchor.report(diagnostics, NO_ACCESSOR, host.entityName() + '.' + name + " is ambiguous for property '"
                        + property.logicalName() + "': " + parameters(fitting) + " all accept " + value
                        + " and none is more specific than the others");
            }
            return selected;
        }
        anchor.report(diagnostics, NO_ACCESSOR, host.entityName() + " has no public writer for property '"
                + property.logicalName() + "': expected set" + Accessors.capitalize(property.logicalName())
                + '(' + value + ") or " + property.logicalName() + '(' + value + ')');
        return Optional.empty();
    }

    /** The one writer whose parameter every other writer's parameter is a supertype of, when there is one. */
    private Optional<Property> mostSpecific(List<Property> writers) {
        for (Property candidate : writers) {
            boolean specific = true;
            for (Property other : writers) {
                specific &= signatures.assignable(parameter(candidate), parameter(other));
            }
            if (specific) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The declaration a marked getter names, read as the member of {@code entity} that answers a call to it.
     * A getter the generated code could not call — one that is not public, or that is static and so is not
     * called on an entity at all — is no reader: the marker names it, so no other one stands in for it.
     */
    private Optional<ExecutableElement> marked(PropertyDescriptor property, DeclaredType entity) {
        TypeElement declaring = signatures.elements().getTypeElement(property.declaringType());
        if (declaring == null) {
            return Optional.empty();
        }
        for (Element member : declaring.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD
                    && member.getSimpleName().contentEquals(property.declarationName())
                    && ((ExecutableElement) member).getParameters().isEmpty()) {
                ExecutableElement method = (ExecutableElement) member;
                return Accessors.isPublicInstance(method)
                        ? Optional.of(effective(method, entity))
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The member of {@code entity} a call to {@code declaration} runs: itself, or the override that hides it. */
    private ExecutableElement effective(ExecutableElement declaration, DeclaredType entity) {
        TypeElement element = (TypeElement) entity.asElement();
        for (Element member : signatures.elements().getAllMembers(element)) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.equals(declaration) || signatures.elements().overrides(method, declaration, element)) {
                return method;
            }
        }
        return declaration;
    }

    private static TypeMirror parameter(Property writer) {
        return writer.accessor().effectiveType().getParameterTypes().get(0);
    }

    private static String parameters(List<Property> writers) {
        List<String> types = new ArrayList<>();
        for (Property writer : writers) {
            types.add(parameter(writer).toString());
        }
        return String.join(" and ", types);
    }

    /** Names the declaration the marker chose, so a reader that was not searched for is not looked for twice. */
    private static String describe(PropertyDescriptor property) {
        return property.directGetter()
                ? ": the marker names " + property.declaringType() + '.' + property.declarationName() + "()"
                : "";
    }
}
