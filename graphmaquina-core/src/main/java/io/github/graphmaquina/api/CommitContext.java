package io.github.graphmaquina.api;

import io.github.graphmaquina.internal.UnboundFlow;
import lombok.NonNull;

/**
 * What a post-commit hook receives: the metadata of the committed hop and the flow of the call that committed it.
 * {@code isTerminal} describes {@code to}, using the graph's terminal declarations.
 * In a cascade the entity may already hold a later state; {@code from} and {@code to} still describe
 * this hop. The context may be retained after the hook returns, and its metadata stays readable, but the flow the
 * runtime hands a hook works only while that hook runs and only on the thread running it: afterwards every operation
 * of that flow throws {@code StaleContextException}, and on another thread {@code WrongThreadException}. Once the hook
 * returned or threw, that flow no longer holds the call, so a retained context keeps none of the call's values in
 * memory. A hook
 * declared with the entity alone, through {@link HookRef#of}, never sees the context, so never the flow. Two contexts
 * are equal when their components are: the flow the runtime hands a hook is equal only to itself, and two flows of the
 * five-argument constructor are equal when they hold equal values.
 *
 * @param <S> the machine's state type
 * @param <T> the machine's transition identifier type
 * @param from the state the hop left
 * @param transition the identifier of the committed transition
 * @param to the state the hop committed
 * @param kind whether the committed transition ran an action
 * @param isTerminal whether {@code to} is one of the graph's terminal states
 * @param flow the flow of the call: the values its steps and its earlier hooks put, the root call's entity, which
 *             {@link FlowView#entity()} describes, and, when the call records its trail, every hop and start it
 *             committed, as {@link FlowView#trail()} describes. Each hook receives a handle of its own and can write
 *             through it; its writes are never undone, since the call committed before its hooks run, and the later
 *             hooks of the call read them.
 */
public record CommitContext<S extends Enum<S>, T extends Enum<T>>(@NonNull S from, @NonNull T transition, @NonNull S to,
                                                                   @NonNull TransitionKind kind, boolean isTerminal,
                                                                   @NonNull FlowContext flow) {

    /**
     * A context whose flow belongs to no call: an empty {@link UnboundFlow}, without an entity, that any thread can use
     * and that never goes stale. For calling a hook by hand, in a unit test, and reading back what it wrote.
     */
    public CommitContext(S from, T transition, S to, TransitionKind kind, boolean isTerminal) {
        this(from, transition, to, kind, isTerminal, new UnboundFlow());
    }
}
