package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.Property;
import io.github.graphmaquina.processor.model.WatchOwner;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import lombok.RequiredArgsConstructor;

/**
 * Reads how a {@code Watch} finds the owner of a peer: {@code owner}, a property of the peer's entity that returns an
 * {@code Optional} of the owner or the owner itself, or {@code ownerLookup}, an exported lookup
 * {@code Optional<Owner> name(Peer)}, static or instance.
 *
 * <p>An element is declared when it holds at least one value, so {@code owner = {}} declares nothing. A watch declares
 * exactly one of the two: neither or both is {@code SG405} at the watch, and then neither element is resolved, so no
 * diagnostic of either follows from that one problem. Otherwise the declared element holds one value at most
 * ({@code SG607}), and that value is resolved as any property or lookup is ({@code SG605}, {@code SG503},
 * {@code SG505}).</p>
 */
@RequiredArgsConstructor
final class WatchOwnerReader {

    private static final String OWNER = "owner";
    private static final String OWNER_LOOKUP = "ownerLookup";

    private final PropertyResolver properties;
    private final SymbolResolver symbols;
    private final Signatures signatures;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    /**
     * How the watch of {@code state} that {@code facade} declares over {@code peer} finds the owner of a peer, or empty
     * when it has a problem, which was reported, or holds a value javac could not read, which javac reported.
     */
    Optional<WatchOwner> read(TypeElement facade, AnnotationMirror watch, String state, MachineTypes machine,
            MachineTypes peer) {
        boolean owner = declares(watch, OWNER);
        if (owner == declares(watch, OWNER_LOOKUP)) {
            DiagnosticAnchor.of(facade, watch).report(diagnostics, DiagnosticCode.SG405, "the watch of " + state
                    + " declares " + (owner ? "both owner and ownerLookup" : "neither owner nor ownerLookup")
                    + ": it declares exactly one of them");
            return Optional.empty();
        }
        String name = owner ? OWNER : OWNER_LOOKUP;
        Optional<AnnotationValue> value = TypedValues.optional(watch, name, annotations,
                DiagnosticAnchor.of(facade, watch, annotations.value(watch, name)), diagnostics);
        Optional<TypeMirror> symbol = value.flatMap(WatchOwnerReader::symbol);
        if (symbol.isEmpty()) {
            return Optional.empty();
        }
        DiagnosticAnchor anchor = DiagnosticAnchor.of(facade, watch, value.get());
        return owner ? read(symbol.get(), machine, peer, anchor) : lookup(symbol.get(), machine, peer, anchor);
    }

    /** The owner read from a property of the peer's entity, which returns an {@code Optional} of it or itself. */
    private Optional<WatchOwner> read(TypeMirror symbol, MachineTypes machine, MachineTypes peer,
            DiagnosticAnchor anchor) {
        return properties.owner(symbol, machine, peer, anchor).map((Property property) -> new WatchOwner.Read(
                property, signatures.same(property.accessor().effectiveType().getReturnType(),
                        signatures.declared(Signatures.OPTIONAL, machine.entity()))));
    }

    /** The owner found by an exported lookup that takes the peer's entity and returns an {@code Optional} of it. */
    private Optional<WatchOwner> lookup(TypeMirror symbol, MachineTypes machine, MachineTypes peer,
            DiagnosticAnchor anchor) {
        return symbols.resolve(symbol, Bindings.Role.LOOKUP, machine, Optional.of(peer), anchor)
                .map((Binding binding) -> new WatchOwner.Lookup(binding));
    }

    /** Whether the watch declares at least one value in the element {@code name}. */
    private boolean declares(AnnotationMirror watch, String name) {
        return annotations.find(watch, name).isPresent() && !annotations.array(watch, name).isEmpty();
    }

    /** The symbol {@code value} names, or empty when javac could not read it as a class literal. */
    private static Optional<TypeMirror> symbol(AnnotationValue value) {
        return value.getValue() instanceof TypeMirror type ? Optional.of(type) : Optional.empty();
    }
}
