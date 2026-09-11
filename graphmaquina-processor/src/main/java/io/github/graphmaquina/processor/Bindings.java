package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.processor.model.MachineTypes;
import java.util.List;
import java.util.Optional;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import lombok.RequiredArgsConstructor;

/**
 * What a method must look like to serve one role of a machine: which parameters the call passes it, which
 * result the engine understands, and how the pair is named when neither holds.
 *
 * <p>Nothing here searches for a method. The declaration to call is chosen by {@link SymbolResolver}, from the
 * exports the symbol names; these rules only say whether a chosen declaration suits the machine that uses
 * it.</p>
 */
@RequiredArgsConstructor
final class Bindings {

    /** What the method is used for; it fixes the expected signature. */
    enum Role { ACTION, GUARD, HOOK, CONDITION }

    private static final String TRANSITION_CONTEXT = "io.github.graphmaquina.api.TransitionContext";
    private static final String COMMIT_CONTEXT = "io.github.graphmaquina.api.CommitContext";
    private static final String GUARD_CONTEXT = "io.github.graphmaquina.api.GuardContext";
    private static final String ACTION_OUTCOME = "io.github.graphmaquina.api.ActionOutcome";
    private static final String GUARD_RESULT = "io.github.graphmaquina.api.GuardResult";
    private static final String PEER_VIEW = "io.github.graphmaquina.api.PeerView";
    private static final String COLLECTION = "java.util.Collection";
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";

    private final Signatures signatures;

    /**
     * Whether a method taking {@code parameters} and returning {@code returned} is usable as {@code role} of
     * {@code machine}. The types are the ones the method has where it is called, so a contract of a generic
     * base is judged with the host's own entity and states.
     */
    boolean fits(List<? extends TypeMirror> parameters, TypeMirror returned, Role role, MachineTypes machine, Optional<MachineTypes> peer) {
        return switch (role) {
            case ACTION -> parameters.size() == 1
                    && signatures.same(parameters.get(0), signatures.declared(TRANSITION_CONTEXT, machine.entity(), machine.states()))
                    && (signatures.same(returned, signatures.declared(ACTION_OUTCOME))
                        || signatures.assignable(returned, signatures.declared(COMPLETION_STAGE, signatures.declared(ACTION_OUTCOME))));
            case GUARD -> parameters.size() == 1
                    && signatures.same(parameters.get(0), signatures.declared(GUARD_CONTEXT, machine.entity(), machine.states()))
                    && (signatures.same(returned, signatures.declared(GUARD_RESULT))
                        || signatures.assignable(returned, signatures.declared(COMPLETION_STAGE, signatures.declared(GUARD_RESULT))));
            case HOOK -> (parameters.size() == 1 || parameters.size() == 2
                    && signatures.same(parameters.get(1), signatures.declared(COMMIT_CONTEXT, machine.states())))
                    && signatures.same(parameters.get(0), machine.entity()) && returned.getKind() == TypeKind.VOID;
            case CONDITION -> peer.isPresent() && parameters.size() == 3
                    && signatures.same(parameters.get(0), machine.entity())
                    && signatures.assignable(signatures.declared(COLLECTION, signatures.extendsWildcard(peer.get().entity())), parameters.get(1))
                    && signatures.same(parameters.get(2), signatures.declared(PEER_VIEW, peer.get().entity(), peer.get().states()))
                    && returned.getKind() == TypeKind.BOOLEAN;
        };
    }

    /**
     * The static types of the arguments the generated code passes to a {@code role} of {@code machine}: the
     * parameters of the SPI's own functional interface, which the emitter compares the selected method's
     * parameters against.
     */
    List<TypeMirror> arguments(Role role, MachineTypes machine, Optional<MachineTypes> peer, int arity) {
        return switch (role) {
            case ACTION -> List.of(signatures.declared(TRANSITION_CONTEXT, machine.entity(), machine.states()));
            case GUARD -> List.of(signatures.declared(GUARD_CONTEXT, machine.entity(), machine.states()));
            case HOOK -> arity == 1 ? List.of(machine.entity())
                    : List.of(machine.entity(), signatures.declared(COMMIT_CONTEXT, machine.states()));
            case CONDITION -> peer.<List<TypeMirror>>map((MachineTypes found) -> List.of(
                    machine.entity(),
                    signatures.declared(COLLECTION, signatures.extendsWildcard(found.entity())),
                    signatures.declared(PEER_VIEW, found.entity(), found.states()))).orElseGet(List::of);
        };
    }

    /** Whether the call is asynchronous, which only an action or a guard can be. */
    boolean isAsync(TypeMirror returned, Role role) {
        if (role != Role.ACTION && role != Role.GUARD) {
            return false;
        }
        return signatures.erasureIs(returned, COMPLETION_STAGE) || signatures.subtype(returned, COMPLETION_STAGE);
    }

    /** The exported category a role is served by; one symbol may export several of them. */
    static SymbolKind kind(Role role) {
        return switch (role) {
            case ACTION -> SymbolKind.ACTION;
            case GUARD -> SymbolKind.GUARD;
            case HOOK -> SymbolKind.HOOK;
            case CONDITION -> SymbolKind.CONDITION;
        };
    }

    static String expected(String name, Role role, MachineTypes machine, Optional<MachineTypes> peer) {
        String e = machine.entityName();
        String s = machine.statesName();
        return switch (role) {
            case ACTION -> "ActionOutcome " + name + "(TransitionContext<" + e + ", " + s + ">) or CompletionStage<ActionOutcome> " + name
                    + "(TransitionContext<" + e + ", " + s + ">)";
            case GUARD -> "GuardResult " + name + "(GuardContext<" + e + ", " + s + ">) or CompletionStage<GuardResult> " + name
                    + "(GuardContext<" + e + ", " + s + ">)";
            case HOOK -> "void " + name + "(" + e + ") or void " + name + "(" + e + ", CommitContext<" + s + ">)";
            case CONDITION -> "boolean " + name + "(" + e + ", Collection<? extends " + peer.map(MachineTypes::entityName).orElse("P") + ">, PeerView<"
                    + peer.map(MachineTypes::entityName).orElse("P") + ", " + peer.map(MachineTypes::statesName).orElse("Q") + ">)";
        };
    }
}
