package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * execute, reevaluate, advance, choose or an eligibility check, of eligibility or chooseEligibility, was called on a
 * thread that is already running a root transaction or an eligibility check, evaluating a watch or notifying an
 * observer, from one of the callbacks that run there, where it may not start; or code in the scope of a check that a
 * guard started read an entity that an enclosing call or check holds, through a context's peer view or a sub-machine
 * binding lookup, or cascaded through a retained action context of the call that check is nested in.
 * <ul>
 *   <li>The callbacks are: a guard, an action, a state accessor, an identity function or a sub-machine's childOf or
 *       parentOf inside the transaction, or any of them but the action inside the check, which runs none; a watch's
 *       peersOf, ownerOf or condition, or a state accessor or identity function the engine calls while it evaluates
 *       the watch; or a registry observer's callback. None of them can start a transition, whatever the machine or the
 *       entity of the second one, and a watch selects an edge without running one. Only an action can move other
 *       entities inside the transaction, through its context's cascade.</li>
 *   <li>The one thing that can start there is a check made by a synchronous guard, from its own code on the thread
 *       that called its test, while that guard runs for a root call, a cascade or exit step, an automatic attempt, a
 *       check or a nested check: it runs as a check of its own, nested in that call or check, unless its entity is
 *       held on the thread, which this exception refuses before the check starts. An entity is held when it has the
 *       machine key and an identity equal to those of the root entity of an enclosing call or check, of the entity of
 *       an enclosing cascade or exit step, or of an entity with a pending hop or sub-machine start in an enclosing
 *       call. An entity the enclosing call only registered is not held: a parent its binding check found, or a
 *       cascade target whose step has not run. The test call of an asynchronous guard cannot start a check, and
 *       neither can anything the engine runs for a guard, such as the state getter or the identity function of a peer
 *       it reads, or the log lines and the identity text of a check it starts.</li>
 *   <li>A nested check's scope is all it runs from its start to its answer: its own step, from the read of its
 *       entity's identity to its last guard, with its state read, its binding lookups, its guards and everything they
 *       run, and that step's TRACE lines and the texts they and the step's failures print. Code in that scope cannot
 *       read a held entity through the engine either: a read through a context's peer view, by stateOf, allIn or
 *       anyIn, the view of an enclosing guard's or action's context included, or through a sub-machine binding
 *       lookup, throws this exception, whose {@code entity()} is the entity read, into the code that made it, so a
 *       nested check never reads, through a peer view or a binding lookup, an entity that an enclosing call or check
 *       has in flight, or whose hop or start is pending there. A read the engine does not make, such as a guard's own
 *       call of a getter, is not refused. A guard that lets this exception through fails the nested check with an
 *       {@code EligibilityCheckException}, and the guard that started that check fails in turn unless it catches
 *       it.</li>
 *   <li>Nor can code in that scope move an entity of the call the check is nested in: a cascade or a cascadeAll
 *       through a retained action context of that call throws this exception, whose {@code key()} and
 *       {@code entity()} are that action's, before it stages anything or poisons the call. With no nested check's
 *       scope around it, such a context cascades as its action could, and the steps it runs count no guard of the
 *       code that made the cascade, so they cannot start a check but from their own synchronous guards.</li>
 *   <li>The application code that the engine runs for a nested check outside that scope runs in the enclosing
 *       scope, under that scope's rules: the entity's identity function and its identity's equals, before the check
 *       starts, as it tells whether the entity is held; the log's backend, as the check reads its levels and logs its
 *       start, verdict and failed lines; and the identity text and the failure reason that those lines and the
 *       check's {@code EligibilityCheckException} print. There a read or a cascade through a context of the enclosing
 *       call is as allowed as in the guard that started the check. It still cannot start a check.</li>
 * </ul>
 * After-commit hooks run once the transaction has released the thread, so they can start transitions and checks; a
 * watch reaction runs the edge its rule selected as a root of its own, once the watch's functions are done, and each
 * automatic attempt runs as a root of its own once the watch reactions of the commit that triggered it ran. Any of
 * these callbacks that submits a call through {@code async(...)} gets a separate call on the executor's thread, unless
 * the call runs on the callback's own thread, where it is refused or runs as it would if the callback made it itself;
 * a refused one fails the returned future with this exception as its cause. An executor that runs tasks inline runs it
 * there, and so can a wait of the callback that runs a ForkJoinPool's queued tasks, as joining a ForkJoinTask or
 * waiting for the pool's quiescence does. CompletableFuture's get and join never run such a call.
 */
@Getter
public final class ReentrantTransitionException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    public ReentrantTransitionException(MachineKey<?, ?, ?> key, @NonNull Object entity) {
        super("execute/reevaluate/advance/choose/eligibility on " + Objects.requireNonNull(key, "key")
                + ", or, in a nested check's scope, a read of it through a peer view or a binding lookup or a cascade"
                + " through its action's context, from within a transition, an eligibility check, a watch evaluation or"
                + " an observer callback already in progress on this thread: a guard, an action, a sub-machine's"
                + " childOf or parentOf, a watch's peersOf, ownerOf or condition, a state accessor, an identity"
                + " function or an observer cannot start another transition or eligibility check, except that a"
                + " synchronous guard can check an entity that no enclosing call or check holds, and in that check's"
                + " scope a held entity cannot be read through a peer view or a binding lookup, nor can an enclosing"
                + " action's context cascade; an action moves its peers with ctx.cascade(...)");
        this.key = key;
        this.entity = entity;
    }
}
