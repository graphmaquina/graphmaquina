package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * execute, reevaluate, advance, choose or an eligibility check, of eligibility or chooseEligibility, was called on a
 * thread that is already running a root transaction or an eligibility check, evaluating a watch or notifying an
 * observer, from one of the callbacks that run there, where it may not start; or a check that a guard started read an
 * entity that an enclosing call or check holds.
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
 *   <li>A nested check, and everything it runs, cannot read a held entity either: a read through a peer view's
 *       stateOf, allIn or anyIn, the view of an enclosing guard's context included, or through a sub-machine binding
 *       lookup, throws this exception, whose {@code entity()} is the entity read, into the code that made it, so a
 *       nested check never answers from a state that the enclosing call is about to replace. A guard that lets it
 *       through fails the nested check with an {@code EligibilityCheckException}, and the guard that started that
 *       check fails in turn unless it catches it.</li>
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
                + ", or a read of it inside a nested check, from within a transition, an eligibility check, a watch"
                + " evaluation or an observer callback already in progress on this thread: a guard, an action, a"
                + " sub-machine's childOf or parentOf, a watch's peersOf, ownerOf or condition, a state accessor, an"
                + " identity function or an observer cannot start another transition or eligibility check, except that"
                + " a synchronous guard can check an entity that no enclosing call or check holds, and that check"
                + " cannot read a held entity; an action moves its peers with ctx.cascade(...)");
        this.key = key;
        this.entity = entity;
    }
}
