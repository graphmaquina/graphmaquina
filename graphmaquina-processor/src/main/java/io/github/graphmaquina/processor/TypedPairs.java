package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.processor.model.MachineTypes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * The pairs a machine declares with {@code @GraphPeers}, and the identity of the vocabulary generated for each of
 * them, which the machine's watches and sub-machines are declared with.
 *
 * <p>The vocabulary of a pair belongs to an ordered pair, not to a single machine, so identity is checked on both
 * halves of it: the annotation must have been generated for this very facade, and the peer it names must be one the
 * facade declares with {@code @GraphPeers}. The pair is therefore never repeated in a declaration and can never be
 * inferred from the states, which two machines may well share.</p>
 *
 * <p>The declaration of the pairs and their uses must agree in both directions. A peer that no watch and no
 * sub-machine uses is a declaration that generated vocabulary for nothing, and a peer declared twice would generate it
 * twice; both are refused as {@code SG608} rather than silently ignored. The generator refuses the second already, with
 * that code, so the processor meets it only in a declaration edited since the generation ran.</p>
 */
final class TypedPairs {

    private final StructuralChecks structure;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    TypedPairs(StructuralChecks structure, Signatures signatures, Diagnostics diagnostics) {
        this.structure = structure;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /** The peers {@code @GraphPeers} declares: where it was written, the ones it names and the usable ones. */
    record Peers(Optional<AnnotationMirror> mirror, Map<String, TypeElement> declared, Set<String> usable) {

        /** The machine of the declared peer {@code name}, when that peer is usable. */
        Optional<TypeElement> usablePeer(String name) {
            return usable.contains(name) ? Optional.ofNullable(declared.get(name)) : Optional.empty();
        }
    }

    /**
     * The peers of {@code facade}, with {@code SG608} for one it names twice and for one not marked
     * {@code @GenerateGraphApi}: the vocabulary of a pair is typed with the peer's own states, which only such a
     * machine's generated API declares.
     */
    Peers peers(TypeElement facade) {
        Optional<AnnotationMirror> mirror = annotations.find(facade, GraphPeers.class);
        Map<String, TypeElement> declared = new LinkedHashMap<>();
        Set<String> usable = new LinkedHashSet<>();
        if (mirror.isEmpty()) {
            return new Peers(mirror, Map.of(), Set.of());
        }
        for (AnnotationValue value : annotations.array(mirror.get(), "value")) {
            Optional<TypeElement> peer = element(value);
            if (peer.isEmpty()) {
                // javac has already refused a value it could not read as the machine the element declares.
                continue;
            }
            String name = peer.get().getQualifiedName().toString();
            DiagnosticAnchor anchor = DiagnosticAnchor.of(facade, mirror.get(), value);
            if (declared.containsKey(name)) {
                anchor.report(diagnostics, DiagnosticCode.SG608,
                        name + " is declared twice as a peer of " + facade.getSimpleName());
                continue;
            }
            declared.put(name, peer.get());
            if (annotations.find(peer.get(), GenerateGraphApi.class).isEmpty()) {
                anchor.report(diagnostics, DiagnosticCode.SG608,
                        name + " is not a @GenerateGraphApi machine: the vocabulary of a pair"
                        + " is typed with the peer's own states, which only a generated API declares");
            } else {
                usable.add(name);
            }
        }
        return new Peers(mirror, Collections.unmodifiableMap(declared), Collections.unmodifiableSet(usable));
    }

    /**
     * The peer the pair of {@code declared} names, or empty after reporting {@code SG606} when the vocabulary was
     * generated for another machine, and {@code SG608} when the facade declares no such peer, saying that the
     * facade {@code uses} it, as in {@code watches}.
     */
    Optional<String> peer(TypeElement facade, AnnotationMirror declared, Peers peers, String uses) {
        TypeElement declaration = (TypeElement) declared.getAnnotationType().asElement();
        if (!generatedFor(declared, facade)) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, DiagnosticCode.SG606,
                    declaration.getQualifiedName()
                    + " is the vocabulary of a pair of " + machine(declared) + ", not of " + facade.getQualifiedName());
            return Optional.empty();
        }
        Optional<TypeElement> peer = peerOf(declared);
        if (peer.isEmpty()) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, DiagnosticCode.SG606,
                    declaration.getQualifiedName()
                    + " names no single peer machine, so it is not the vocabulary of a pair");
            return Optional.empty();
        }
        String name = peer.get().getQualifiedName().toString();
        if (!peers.declared().containsKey(name)) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, DiagnosticCode.SG608,
                    facade.getSimpleName() + " " + uses + " "
                    + name + ", which it does not declare with @GraphPeers");
            return Optional.empty();
        }
        return Optional.of(name);
    }

    /** {@code SG608} at {@code @GraphPeers} for every peer {@code facade} declares that is not one of {@code used}. */
    void checkUsed(TypeElement facade, Peers peers, Set<String> used) {
        for (String declared : peers.declared().keySet()) {
            if (!used.contains(declared)) {
                DiagnosticAnchor.of(facade, peers.mirror().orElseThrow()).report(diagnostics, DiagnosticCode.SG608,
                        declared
                        + " is declared as a peer of " + facade.getSimpleName() + " but no watch or sub-machine of it"
                        + " uses that pair; declare the peers this machine watches or binds, and only those");
            }
        }
    }

    /**
     * The types of {@code peer}, which {@code declared} on {@code facade} uses, or empty after {@code SG509} when
     * {@code peer} does not resolve them or declares a parameterized entity, which {@code what} names the use that
     * cannot take, as in {@code a watch cannot follow}.
     */
    Optional<MachineTypes> peerTypes(TypeElement facade, AnnotationMirror declared, TypeElement peer, String what) {
        Optional<MachineTypes> resolved = structure.machineTypes(peer);
        if (resolved.isEmpty()) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, DiagnosticCode.SG509, peer.getQualifiedName()
                    + " must be an interface extending Machine<E, S, T> with a concrete entity type and two"
                    + " distinct enum types for its states and its transition identifiers");
            return Optional.empty();
        }
        if (StructuralChecks.hasParameterizedEntity(resolved.get())) {
            DiagnosticAnchor.of(facade, declared).report(diagnostics, DiagnosticCode.SG509, peer.getQualifiedName()
                    + " declares its entity as " + resolved.get().entity() + ", a parameterized type, which " + what);
            return Optional.empty();
        }
        return resolved;
    }

    /** Whether {@code declared} is vocabulary generated for {@code machine}. Reports nothing. */
    boolean generatedFor(AnnotationMirror declared, TypeElement machine) {
        return machine(declared) instanceof DeclaredType owner && owner.asElement().equals(machine);
    }

    /** The one peer the pair of {@code declared} names, or empty when it names none or several. Reports nothing. */
    Optional<TypeElement> peerOf(AnnotationMirror declared) {
        List<AnnotationValue> pair = annotations.array(metadata(declared), "peer");
        return pair.size() == 1 ? element(pair.get(0)) : Optional.empty();
    }

    /** The machine the vocabulary {@code declared} was generated for, as its metadata names it. */
    private TypeMirror machine(AnnotationMirror declared) {
        return TypedValues.classLiteral(annotations.value(metadata(declared), "machine"));
    }

    private AnnotationMirror metadata(AnnotationMirror declared) {
        TypeElement declaration = (TypeElement) declared.getAnnotationType().asElement();
        return annotations.find(declaration, GeneratedVocabulary.class).orElseThrow();
    }

    /** The type {@code value} names, or empty when javac could not read it as a declared type. */
    private static Optional<TypeElement> element(AnnotationValue value) {
        return value.getValue() instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                ? Optional.of(element)
                : Optional.empty();
    }
}
