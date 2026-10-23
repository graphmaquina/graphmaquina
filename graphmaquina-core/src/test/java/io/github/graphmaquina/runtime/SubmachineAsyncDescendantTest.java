package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.AsyncDeadlines.GENEROUS;
import static io.github.graphmaquina.fixtures.AsyncDeadlines.HOUR;
import static io.github.graphmaquina.fixtures.AsyncDeadlines.SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Asynchronous exits of descendants that no call of their own starts (spec R13), in graphs of their own. A
 * synchronous root whose own entry starts a child whose initial node holds a completed leaf runs that leaf's exit
 * implicitly: the registry refuses the root without an asyncTimeout, and with one the exit waits under the entering
 * root's deadline, writes nothing when it times out and leaves the user's stage alive. Machine keys that print alike
 * stay distinct: the later asynchronous exit is not hidden by the first parent, and a refusal consumes no builder.
 * <p>No test sleeps: a wait that must time out has a deadline of 100 ms against one of an hour.
 */
class SubmachineAsyncDescendantTest {

    /** The root enters a child whose initial node holds the completed leaf. */
    enum ParentState { OPEN, BOUND, DONE }

    /** Root entry and its reserved completion identifier. */
    enum ParentId { ENTER, FINISH }

    /** The child waits for its initial leaf, then completes. */
    enum ChildState { WAIT, DONE }

    /** The child's asynchronous exit selected by the leaf. */
    enum ChildId { FINISH }

    /** A leaf that completes as soon as it starts. */
    enum LeafState { DONE }

    /** The leaf has no declared edge. */
    enum LeafId { UNUSED }

    /** One aggregate with three independent state fields and a count of actual setter calls. */
    static final class Nested {
        private ParentState parent = ParentState.OPEN;
        private ChildState child = ChildState.DONE;
        private LeafState leaf = LeafState.DONE;
        private int writes;
    }

    private static final MachineKey<Nested, ParentState, ParentId> PARENTS =
            MachineKey.of(Nested.class, ParentState.class, ParentId.class);
    private static final MachineKey<Nested, ChildState, ChildId> CHILDREN =
            MachineKey.of(Nested.class, ChildState.class, ChildId.class);
    private static final MachineKey<Nested, LeafState, LeafId> LEAVES =
            MachineKey.of(Nested.class, LeafState.class, LeafId.class);

    /** The pending child exit and the reusable registry builders of the pure DSL three-level graph. */
    private record InitialCompletion(MachineRegistry.Builder registry,
                                      StateMachines.Builder<Nested, ParentState, ParentId> root,
                                      CompletableFuture<ActionOutcome> stage) { }

    private static InitialCompletion initialCompletion() {
        CompletableFuture<ActionOutcome> stage = new CompletableFuture<>();
        GraphDefinition<Nested, LeafState, LeafId> leaf = StateGraph.define(LEAVES)
                .initial(LeafState.DONE).terminal(LeafState.DONE).build();
        GraphDefinition<Nested, ChildState, ChildId> child = StateGraph.define(CHILDREN)
                .initial(ChildState.WAIT).terminal(ChildState.DONE)
                .statePolicy(ChildState.WAIT, "wait for the initial leaf")
                .transition(ChildId.FINISH, ChildState.WAIT, ChildState.DONE)
                    .asyncAction("FinishChild", (TransitionContext<Nested, ChildState, ChildId> ctx) -> stage)
                    .endTransition()
                .submachine(ChildState.WAIT, leaf).exit(LeafState.DONE, ChildId.FINISH).endSubmachine().build();
        GraphDefinition<Nested, ParentState, ParentId> parent = StateGraph.define(PARENTS)
                .initial(ParentState.OPEN).terminal(ParentState.DONE)
                .statePolicy(ParentState.OPEN, "enter the child")
                .statePolicy(ParentState.BOUND, "wait for the child")
                .transition(ParentId.ENTER, ParentState.OPEN, ParentState.BOUND).endTransition()
                .transition(ParentId.FINISH, ParentState.BOUND, ParentState.DONE).endTransition()
                .submachine(ParentState.BOUND, child).exit(ChildState.DONE, ParentId.FINISH).endSubmachine().build();
        StateMachines.Builder<Nested, ParentState, ParentId> root = StateMachines.from(parent)
                .state((Nested entity) -> entity.parent, (Nested entity, ParentState state) -> {
                    entity.writes++;
                    entity.parent = state;
                }).identity((Nested entity) -> "one");
        MachineRegistry.Builder registry = MachineRegistry.builder().add(root)
                .add(StateMachines.from(child).state((Nested entity) -> entity.child,
                        (Nested entity, ChildState state) -> {
                            entity.writes++;
                            entity.child = state;
                        }).identity((Nested entity) -> "one").asyncTimeout(HOUR))
                .add(StateMachines.from(leaf).state((Nested entity) -> entity.leaf,
                        (Nested entity, LeafState state) -> {
                            entity.writes++;
                            entity.leaf = state;
                        }).identity((Nested entity) -> "one").asyncTimeout(HOUR));
        return new InitialCompletion(registry, root, stage);
    }

    /** A synchronous root's own entry runs the initial descendant's exit implicitly and requires a root timeout. */
    @Test
    void anOwnEntryWithATerminalInitialDescendantRequiresTheRootsTimeout() {
        InitialCompletion completion = initialCompletion();
        IllegalStateException error = assertTimeoutPreemptively(GENEROUS,
                () -> assertThrows(IllegalStateException.class, completion.registry()::build));
        assertEquals("the machine Nested/ParentState can run the asynchronous edge Nested/ChildState.WAIT"
                + " -FINISH-> implicitly and does not declare asyncTimeout(...)", error.getMessage());
        completion.root().asyncTimeout(SHORT);
        try (MachineRegistry registry = completion.registry().build()) {
            assertEquals(Set.of(PARENTS, CHILDREN, LEAVES), registry.keys());
        }
    }

    /** The descendant exit uses the entering root's short deadline, writes nothing, and leaves the user stage alive. */
    @Test
    void anInitialDescendantExitWaitsUnderTheEnteringRootsDeadline() {
        InitialCompletion completion = initialCompletion();
        completion.root().asyncTimeout(SHORT);
        Nested entity = new Nested();
        try (MachineRegistry registry = completion.registry().build()) {
            TransitionAbortedException error = assertTimeoutPreemptively(GENEROUS,
                    () -> assertThrows(TransitionAbortedException.class,
                            () -> registry.of(PARENTS).execute(entity, ParentId.ENTER)));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertEquals("transition aborted in Nested/ChildState WAIT -FINISH-> DONE: waiting for the action"
                    + " FinishChild timed out [route Nested/ChildState#one]", error.getMessage());
            assertEquals(ParentState.OPEN, entity.parent);
            assertEquals(ChildState.DONE, entity.child);
            assertEquals(LeafState.DONE, entity.leaf);
            assertEquals(0, entity.writes);
            assertFalse(completion.stage().isDone());
            assertFalse(completion.stage().isCancelled());
        }
    }

    /** The first parent uses the same simple type names as the second parent but distinct actual classes. */
    static final class First {
        /** The first parent's bound and completed states. */
        enum State { BOUND, DONE }

        /** The first parent's synchronous exit. */
        enum Id { FINISH }

        /** The first parent's entity. */
        static final class Entity {
            private State state = State.BOUND;
        }
    }

    /** A distinct parent whose diagnostic labels collide with the first parent's. */
    static final class Second {
        /** The second parent's bound and completed states. */
        enum State { BOUND, DONE }

        /** The second parent's asynchronous exit. */
        enum Id { FINISH }

        /** The second parent's entity. */
        static final class Entity {
            private State state = State.BOUND;
        }
    }

    /** One child graph bound by both same-label parents. */
    static final class Child {
        private ChildState state = ChildState.WAIT;
    }

    private static GraphDefinition<Child, ChildState, ChildId> finishable(
            MachineKey<Child, ChildState, ChildId> childKey) {
        return StateGraph.define(childKey)
                .initial(ChildState.WAIT).terminal(ChildState.DONE)
                .statePolicy(ChildState.WAIT, "finish the child")
                .transition(ChildId.FINISH, ChildState.WAIT, ChildState.DONE).endTransition().build();
    }

    /** Diagnostic collisions must not hide the later asynchronous exit or consume builders on refusal. */
    @Test
    void distinctMachineKeysWithEqualDiagnosticsRetainEveryImplicitExit() {
        MachineKey<Child, ChildState, ChildId> childKey =
                MachineKey.of(Child.class, ChildState.class, ChildId.class);
        GraphDefinition<Child, ChildState, ChildId> child = finishable(childKey);
        MachineKey<First.Entity, First.State, First.Id> firstKey =
                MachineKey.of(First.Entity.class, First.State.class, First.Id.class);
        MachineKey<Second.Entity, Second.State, Second.Id> secondKey =
                MachineKey.of(Second.Entity.class, Second.State.class, Second.Id.class);
        assertFalse(firstKey.equals(secondKey));
        assertEquals(firstKey.toString(), secondKey.toString());
        assertEquals(firstKey + "." + First.State.BOUND + " -" + First.Id.FINISH + "->",
                secondKey + "." + Second.State.BOUND + " -" + Second.Id.FINISH + "->");
        GraphDefinition<First.Entity, First.State, First.Id> first = StateGraph.define(firstKey)
                .initial(First.State.BOUND).terminal(First.State.DONE)
                .statePolicy(First.State.BOUND, "wait for the child")
                .transition(First.Id.FINISH, First.State.BOUND, First.State.DONE).endTransition()
                .submachine(First.State.BOUND, child, (First.Entity entity) -> Optional.<Child>empty(),
                        (Child entity) -> Optional.<First.Entity>empty())
                    .exit(ChildState.DONE, First.Id.FINISH).endSubmachine().build();
        GraphDefinition<Second.Entity, Second.State, Second.Id> second = StateGraph.define(secondKey)
                .initial(Second.State.BOUND).terminal(Second.State.DONE)
                .statePolicy(Second.State.BOUND, "wait for the child")
                .transition(Second.Id.FINISH, Second.State.BOUND, Second.State.DONE)
                    .asyncAction("Finish", (TransitionContext<Second.Entity, Second.State, Second.Id> ctx) ->
                            CompletableFuture.completedFuture(ActionOutcome.ok()))
                    .endTransition()
                .submachine(Second.State.BOUND, child, (Second.Entity entity) -> Optional.<Child>empty(),
                        (Child entity) -> Optional.<Second.Entity>empty())
                    .exit(ChildState.DONE, Second.Id.FINISH).endSubmachine().build();
        StateMachines.Builder<Child, ChildState, ChildId> childMachine = StateMachines.from(child)
                .state((Child entity) -> entity.state, (Child entity, ChildState state) -> entity.state = state)
                .identity((Child entity) -> "child");
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(StateMachines.from(first).state((First.Entity entity) -> entity.state,
                        (First.Entity entity, First.State state) -> entity.state = state)
                        .identity((First.Entity entity) -> "first"))
                .add(StateMachines.from(second).state((Second.Entity entity) -> entity.state,
                        (Second.Entity entity, Second.State state) -> entity.state = state)
                        .identity((Second.Entity entity) -> "second").asyncTimeout(HOUR))
                .add(childMachine);
        IllegalStateException error = assertTimeoutPreemptively(GENEROUS,
                () -> assertThrows(IllegalStateException.class, builder::build));
        assertEquals("the machine Child/ChildState can run the asynchronous edge Entity/State.BOUND -FINISH->"
                + " implicitly and does not declare asyncTimeout(...)", error.getMessage());
        childMachine.asyncTimeout(HOUR);
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(firstKey, secondKey, childKey), registry.keys());
        }
    }
}
