package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.processor.model.MachineTypes;
import java.util.List;
import java.util.Optional;
import javax.lang.model.type.DeclaredType;
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
    enum Role { ACTION, GUARD, HOOK, CONDITION, LOOKUP }

    static final String TRANSITION_CONTEXT = TransitionContext.class.getName();
    static final String COMMIT_CONTEXT = CommitContext.class.getName();
    static final String GUARD_CONTEXT = GuardContext.class.getName();
    static final String ACTION_OUTCOME = ActionOutcome.class.getName();
    static final String GUARD_RESULT = GuardResult.class.getName();
    static final String PEER_VIEW = PeerView.class.getName();

    private final Signatures signatures;

    /**
     * Whether a method taking {@code parameters} and returning {@code returned} is usable as {@code role} of
     * {@code machine}. The types are the ones the method has where it is called, so a contract of a generic
     * base is judged with the host's own entity and states. An asynchronous result is a subtype of the completion
     * stage of the verdict, never a raw completion stage, which only an unchecked conversion would make one.
     */
    boolean fits(List<? extends TypeMirror> parameters, TypeMirror returned, Role role, MachineTypes machine,
                 Optional<MachineTypes> peer) {
        return switch (role) {
            case ACTION -> fitsVerdict(parameters, returned, machine, TRANSITION_CONTEXT, ACTION_OUTCOME);
            case GUARD -> fitsVerdict(parameters, returned, machine, GUARD_CONTEXT, GUARD_RESULT);
            case HOOK -> fitsHook(parameters, returned, machine);
            case CONDITION -> peer.isPresent() && fitsCondition(parameters, returned, machine, peer.get());
            case LOOKUP -> peer.isPresent() && parameters.size() == 1
                    && signatures.same(parameters.get(0), peer.get().entity())
                    && signatures.same(returned, signatures.declared(Signatures.OPTIONAL, machine.entity()));
        };
    }

    /**
     * Whether the method fits an action or a guard of {@code machine}: it takes the {@code context} of the machine
     * alone and returns {@code verdict}, or a completion stage of it.
     */
    private boolean fitsVerdict(List<? extends TypeMirror> parameters, TypeMirror returned, MachineTypes machine,
                                String context, String verdict) {
        return parameters.size() == 1
                && signatures.same(parameters.get(0), parameterized(context, machine))
                && (signatures.same(returned, signatures.declared(verdict))
                    || signatures.subtypeOf(returned,
                            signatures.declared(Signatures.COMPLETION_STAGE, signatures.declared(verdict))));
    }

    /** Whether the method fits a hook of {@code machine}: it takes the entity, then optionally the commit context. */
    private boolean fitsHook(List<? extends TypeMirror> parameters, TypeMirror returned, MachineTypes machine) {
        return (parameters.size() == 1 || (parameters.size() == 2
                && signatures.same(parameters.get(1),
                        signatures.declared(COMMIT_CONTEXT, machine.states(), machine.transitions()))))
                && signatures.same(parameters.get(0), machine.entity()) && returned.getKind() == TypeKind.VOID;
    }

    /**
     * Whether the method fits a condition of {@code machine} over {@code peer}: it takes the entity, the peers and the
     * view of the peer machine, and returns a {@code boolean}.
     */
    private boolean fitsCondition(List<? extends TypeMirror> parameters, TypeMirror returned, MachineTypes machine,
                                  MachineTypes peer) {
        return parameters.size() == 3
                && signatures.same(parameters.get(0), machine.entity())
                && signatures.assignable(
                        signatures.declared(Signatures.COLLECTION, signatures.extendsWildcard(peer.entity())),
                        parameters.get(1))
                && signatures.same(parameters.get(2), parameterized(PEER_VIEW, peer))
                && returned.getKind() == TypeKind.BOOLEAN;
    }

    /** {@code type<E, S, T>} over the entity, the states and the transitions of {@code machine}. */
    private DeclaredType parameterized(String type, MachineTypes machine) {
        return signatures.declared(type, machine.entity(), machine.states(), machine.transitions());
    }

    /**
     * The static types of the arguments the generated code passes to a {@code role} of {@code machine}: the
     * parameters of the functional interface the DSL takes for that role, which the emitter compares the selected
     * method's parameters against. For a hook, {@code arity} is the selected method's parameter count and picks the
     * entity-only or the entity-plus-context form. A condition or a lookup without a peer gets none.
     */
    List<TypeMirror> arguments(Role role, MachineTypes machine, Optional<MachineTypes> peer, int arity) {
        return switch (role) {
            case ACTION -> List.of(parameterized(TRANSITION_CONTEXT, machine));
            case GUARD -> List.of(parameterized(GUARD_CONTEXT, machine));
            case HOOK -> arity == 1 ? List.of(machine.entity())
                    : List.of(machine.entity(),
                            signatures.declared(COMMIT_CONTEXT, machine.states(), machine.transitions()));
            case CONDITION -> peer.<List<TypeMirror>>map((MachineTypes found) -> List.of(
                    machine.entity(),
                    signatures.declared(Signatures.COLLECTION, signatures.extendsWildcard(found.entity())),
                    parameterized(PEER_VIEW, found))).orElseGet(List::of);
            case LOOKUP -> peer.<List<TypeMirror>>map((MachineTypes found) -> List.of(found.entity()))
                    .orElseGet(List::of);
        };
    }

    /** Whether the call is asynchronous, which only an action or a guard can be. */
    boolean isAsync(TypeMirror returned, Role role) {
        if (role != Role.ACTION && role != Role.GUARD) {
            return false;
        }
        return signatures.erasureIs(returned, Signatures.COMPLETION_STAGE)
                || signatures.subtype(returned, Signatures.COMPLETION_STAGE);
    }

    /** The exported category a role is served by; one symbol may export several of them. */
    static SymbolKind kind(Role role) {
        return switch (role) {
            case ACTION -> SymbolKind.ACTION;
            case GUARD -> SymbolKind.GUARD;
            case HOOK -> SymbolKind.HOOK;
            case CONDITION -> SymbolKind.CONDITION;
            case LOOKUP -> SymbolKind.LOOKUP;
        };
    }

    static String expected(String name, Role role, MachineTypes machine, Optional<MachineTypes> peer) {
        String e = machine.entityName();
        String s = machine.statesName();
        String t = machine.transitionsName();
        String p = peer.map(MachineTypes::entityName).orElse("P");
        String q = peer.map(MachineTypes::statesName).orElse("Q");
        String u = peer.map(MachineTypes::transitionsName).orElse("U");
        return switch (role) {
            case ACTION -> "ActionOutcome " + name + "(TransitionContext<" + e + ", " + s + ", " + t
                    + ">) or CompletionStage<ActionOutcome> " + name + "(TransitionContext<" + e + ", " + s + ", " + t
                    + ">)";
            case GUARD -> "GuardResult " + name + "(GuardContext<" + e + ", " + s + ", " + t
                    + ">) or CompletionStage<GuardResult> " + name + "(GuardContext<" + e + ", " + s + ", " + t + ">)";
            case HOOK -> "void " + name + "(" + e + ") or void " + name + "(" + e + ", CommitContext<" + s + ", " + t
                    + ">)";
            case CONDITION -> "boolean " + name + "(" + e + ", Collection<? extends " + p + ">, PeerView<"
                    + p + ", " + q + ", " + u + ">)";
            case LOOKUP -> "Optional<" + e + "> " + name + "(" + p + ")";
        };
    }
}
