package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The refusals of a registry's build, and of a call given an identifier of another enum, name the states and
 * identifiers of the application's enums by {@code name()}, never through a {@code toString()} the application may
 * override, as the engine's and the DSL's messages do.
 */
class RegistryConstantNamesTest {

    /** A box with a stage and, for its sub-machine, a step. */
    static final class Box {
        Stage stage = Stage.START;
        Step step = Step.RUN;
    }

    /** States of a box, printed as a display text. */
    enum Stage {
        START, WAIT, END;

        @Override
        public String toString() {
            return "stage " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Identifiers of a box, printed as a display text. */
    enum Move {
        GO, FINISH;

        @Override
        public String toString() {
            return "move " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** States of a box's sub-machine, printed as a display text. */
    enum Step {
        RUN, DONE;

        @Override
        public String toString() {
            return "step " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Identifiers of a box's sub-machine, printed as a display text. */
    enum StepMove {
        ADVANCE;

        @Override
        public String toString() {
            return "step move " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Identifiers of another machine, printed as a display text. */
    enum Foreign {
        ELSEWHERE;

        @Override
        public String toString() {
            return "foreign " + name().toLowerCase(Locale.ROOT);
        }
    }

    private static final MachineKey<Box, Stage, Move> BOXES = MachineKey.of(Box.class, Stage.class, Move.class);
    private static final MachineKey<Box, Step, StepMove> STEPS = MachineKey.of(Box.class, Step.class, StepMove.class);
    private static final Duration HOUR = Duration.ofHours(1);

    private static GraphDefinition<Box, Step, StepMove> steps() {
        return StateGraph.define(STEPS).initial(Step.RUN).terminal(Step.DONE).statePolicy(Step.RUN, "runs")
                .transition(StepMove.ADVANCE, Step.RUN, Step.DONE).endTransition()
                .build();
    }

    /** START -GO-> WAIT, which holds {@code child}, and the exit WAIT -FINISH-> END, asynchronous when asked. */
    private static GraphDefinition<Box, Stage, Move> boxes(GraphDefinition<Box, Step, StepMove> child,
            boolean asyncExit) {
        return asyncExit
                ? StateGraph.define(BOXES).initial(Stage.START).terminal(Stage.END)
                        .statePolicy(Stage.START, "starts").statePolicy(Stage.WAIT, "waits")
                        .transition(Move.GO, Stage.START, Stage.WAIT).endTransition()
                        .transition(Move.FINISH, Stage.WAIT, Stage.END)
                            .asyncAction("finish", (TransitionContext<Box, Stage, Move> ctx) ->
                                    CompletableFuture.completedFuture(ActionOutcome.ok()))
                            .endTransition()
                        .submachine(Stage.WAIT, child).exit(Step.DONE, Move.FINISH).endSubmachine()
                        .build()
                : StateGraph.define(BOXES).initial(Stage.START).terminal(Stage.END)
                        .statePolicy(Stage.START, "starts").statePolicy(Stage.WAIT, "waits")
                        .transition(Move.GO, Stage.START, Stage.WAIT).endTransition()
                        .transition(Move.FINISH, Stage.WAIT, Stage.END).endTransition()
                        .submachine(Stage.WAIT, child).exit(Step.DONE, Move.FINISH).endSubmachine()
                        .build();
    }

    private static StateMachines.Builder<Box, Stage, Move> boxMachine(GraphDefinition<Box, Stage, Move> graph) {
        return StateMachines.from(graph).state((Box box) -> box.stage, (Box box, Stage stage) -> box.stage = stage)
                .identity((Box box) -> box);
    }

    private static StateMachines.Builder<Box, Step, StepMove> stepMachine(GraphDefinition<Box, Step, StepMove> graph) {
        return StateMachines.from(graph).state((Box box) -> box.step, (Box box, Step step) -> box.step = step)
                .identity((Box box) -> box);
    }

    @Test
    void aWatchOfAMachineOutsideTheRegistryIsNamedByName() {
        GraphDefinition<Box, Stage, Move> graph = StateGraph.define(BOXES).initial(Stage.START).terminal(Stage.END)
                .statePolicy(Stage.START, "starts").statePolicy(Stage.WAIT, "waits")
                .transition(Move.GO, Stage.START, Stage.WAIT).endTransition()
                .transition(Move.FINISH, Stage.WAIT, Stage.END).endTransition()
                .watch(Stage.WAIT, STEPS, (Box box) -> List.<Box>of(), (Box peer) -> Optional.<Box>empty())
                    .when("never", (Box box, Collection<? extends Box> peers, PeerView<Box, Step, StepMove> view)
                            -> false)
                    .execute(Move.FINISH)
                    .endWatch()
                .build();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> boxMachine(graph).build());
        assertEquals("the watch of Box/Stage.WAIT points to Box/Step, which is not in the registry",
                error.getMessage());
    }

    @Test
    void aSubMachineOutsideTheRegistryIsNamedByName() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> boxMachine(boxes(steps(), false)).build());
        assertEquals("the sub-machine of Box/Stage.WAIT is Box/Step, which is not in the registry",
                error.getMessage());
    }

    @Test
    void aSubMachineOfAnotherDefinitionIsNamedByName() {
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(boxMachine(boxes(steps(), false))).add(stepMachine(steps()));
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the sub-machine of Box/Stage.WAIT was built from another definition of Box/Step than the"
                + " registered machine", error.getMessage());
    }

    @Test
    void anAsynchronousEdgeRunImplicitlyIsNamedByName() {
        GraphDefinition<Box, Step, StepMove> child = steps();
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(boxMachine(boxes(child, true)).asyncTimeout(HOUR)).add(stepMachine(child));
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Box/Step can run the asynchronous edge Box/Stage.WAIT -FINISH-> implicitly and does"
                + " not declare asyncTimeout(...)", error.getMessage());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void anIdentifierOfAnotherEnumIsNamedByName() {
        GraphDefinition<Box, Step, StepMove> child = steps();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(boxMachine(boxes(child, false))).add(stepMachine(child)).build()) {
            StateMachine raw = registry.machineFor(BOXES);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> raw.execute(new Box(), Foreign.ELSEWHERE));
            assertEquals("transition must belong to " + Move.class.getName() + ": ELSEWHERE", error.getMessage());
        }
    }
}
