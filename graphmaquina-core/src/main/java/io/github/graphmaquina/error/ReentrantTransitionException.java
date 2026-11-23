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
 * binding lookup, or cascaded through a retained action context of the call that check is nested in. Its message says
 * which of these it was, one message for each {@link Refusal}.
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
 *   <li>A nested check's scope is its own step, from the read of its entity's identity to its last guard, with its
 *       state read, its binding lookups, its guards and everything they run, and that step's TRACE lines and the
 *       texts they and the step's failures print. Code in that scope cannot read a held entity through a context's
 *       peer view or a sub-machine binding lookup either: such a read, by stateOf, allIn or anyIn of any live
 *       context's view on this thread, an enclosing guard's or action's included, or by a binding lookup, throws this
 *       exception, whose {@code entity()} is the entity read, into the code that made it, so a nested check never
 *       reads, through a context's peer view or a binding lookup, an entity that an enclosing call or check has in
 *       flight, or whose hop or start is pending there. Any other read is not refused, such as a guard's own call of
 *       a getter, or a read through a watch condition's view that it kept. A guard that lets this exception through
 *       fails the nested check with an {@code EligibilityCheckException}, and the guard that started that check fails
 *       in turn unless it catches it.</li>
 *   <li>Nor can code in that scope move an entity of the call the check is nested in: a cascade or a cascadeAll
 *       through a retained action context of that call throws this exception, whose {@code key()} and
 *       {@code entity()} are that action's, before it stages anything or poisons the call. With no nested check's
 *       scope around it, such a context cascades as its action could, and the steps it runs count no guard of the
 *       code that made the cascade, so they cannot start a check but from their own synchronous guards.</li>
 *   <li>The application code that the engine runs for a nested check outside that scope runs in the enclosing
 *       scope, under that scope's rules: the entity's identity function and, when an enclosing scope holds an entity
 *       of that machine, its identity's equals, before the check starts, as it tells whether the entity is held; the
 *       log's backend, as the check reads its levels and logs its start, verdict and failed lines; and the identity
 *       text and the failure reason that those lines and the check's {@code EligibilityCheckException} print. There a
 *       read or a cascade through a context of the enclosing call is as allowed as in the guard that started the
 *       check. It still cannot start a check.</li>
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

    /** What was refused, which picks the message; {@code key()} and {@code entity()} name what each refusal says. */
    public enum Refusal {

        /**
         * A call, or a check, made where none may start: {@code key()} and {@code entity()} are those of that call or
         * check.
         */
        CALL("execute/reevaluate/advance/choose/eligibility on ", " from within a transition, an eligibility check, a"
                + " watch evaluation or an observer callback already in progress on this thread: a guard, an action, a"
                + " sub-machine's childOf or parentOf, a watch's peersOf, ownerOf or condition, a state accessor, an"
                + " identity function or an observer cannot start another transition or eligibility check, except that"
                + " a synchronous guard can check an entity that no enclosing call or check holds; an action moves its"
                + " peers with ctx.cascade(...)"),

        /**
         * A synchronous guard's check of an entity that an enclosing call or check holds: {@code key()} and
         * {@code entity()} are those of the check.
         */
        HELD_CHECK("eligibility on ", " from a synchronous guard, of an entity that an enclosing call or check holds:"
                + " the root entity of an enclosing call or check, the entity of an enclosing cascade or exit step, or"
                + " an entity with a pending hop or sub-machine start in an enclosing call; a guard can check only an"
                + " entity that no enclosing call or check holds"),

        /**
         * A read, in the scope of a check that a guard started, of an entity that an enclosing call or check holds,
         * through a context's peer view or a binding lookup: {@code key()} and {@code entity()} are those of the
         * entity read.
         */
        HELD_READ("a read of an entity of ", " that an enclosing call or check holds, through a context's peer view or"
                + " a binding lookup, in the scope of a check that a guard started: a nested check cannot read an"
                + " entity that an enclosing call or check has in flight, or whose hop or start is pending there"),

        /**
         * A cascade, in the scope of a check that a guard started, through a retained action context of the call
         * that check is nested in: {@code key()} and {@code entity()} are those of that context's action.
         */
        KEPT_CASCADE("a cascade through a retained action context of ", ", in the scope of a check that a guard"
                + " started: a nested check cannot move an entity of the call it is nested in");

        private final String lead;
        private final String rest;

        Refusal(String lead, String rest) {
            this.lead = lead;
            this.rest = rest;
        }
    }

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    /** The refusal of {@code refusal}'s kind, whose message names {@code key}, of the call, check, read or cascade. */
    public ReentrantTransitionException(MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull Refusal refusal) {
        super(Objects.requireNonNull(refusal, "refusal").lead + Objects.requireNonNull(key, "key")
                + refusal.rest);
        this.key = key;
        this.entity = entity;
    }
}
