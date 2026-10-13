package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.NamedCondition;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.InvalidGraphException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The DSL and the public spec values name the states and identifiers of the application's enums by {@code name()},
 * never through a {@code toString()} the application may override: one that throws cannot refuse a valid declaration,
 * and one that answers a display text changes no message.
 */
class ConstantNamesTest {

    /** The entity of these graphs; nothing ever reads it. */
    static final class Box { }

    /** A failure kind the recovery rules name. */
    record Jammed() implements FailureKind { }

    /** States whose {@code toString()} cannot print them. */
    enum Opaque {
        START, WAIT, END;

        @Override
        public String toString() {
            throw new IllegalStateException("an Opaque constant cannot be printed");
        }
    }

    /** Identifiers whose {@code toString()} cannot print them. */
    enum OpaqueMove {
        GO, BACK, FINISH;

        @Override
        public String toString() {
            throw new IllegalStateException("an OpaqueMove constant cannot be printed");
        }
    }

    /** States of the child machine, whose {@code toString()} cannot print them. */
    enum OpaqueStep {
        RUN, DONE;

        @Override
        public String toString() {
            throw new IllegalStateException("an OpaqueStep constant cannot be printed");
        }
    }

    /** Identifiers of the child machine, whose {@code toString()} cannot print them. */
    enum OpaqueStepMove {
        STEP;

        @Override
        public String toString() {
            throw new IllegalStateException("an OpaqueStepMove constant cannot be printed");
        }
    }

    /** States printed as a display text. */
    enum Shown {
        START, END;

        @Override
        public String toString() {
            return "shown " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Identifiers printed as a display text. */
    enum ShownMove {
        GO, BACK;

        @Override
        public String toString() {
            return "move " + name().toLowerCase(Locale.ROOT);
        }
    }

    private static final MachineKey<Box, Shown, ShownMove> SHOWN =
            MachineKey.of(Box.class, Shown.class, ShownMove.class);

    @Test
    void aGraphWhoseConstantsCannotBePrintedBuilds() {
        MachineKey<Box, OpaqueStep, OpaqueStepMove> childKey =
                MachineKey.of(Box.class, OpaqueStep.class, OpaqueStepMove.class);
        GraphDefinition<Box, OpaqueStep, OpaqueStepMove> child = StateGraph.define(childKey)
                .initial(OpaqueStep.RUN).terminal(OpaqueStep.DONE).statePolicy(OpaqueStep.RUN, "runs")
                .transition(OpaqueStepMove.STEP, OpaqueStep.RUN, OpaqueStep.DONE).endTransition()
                .build();
        MachineKey<Box, Opaque, OpaqueMove> key = MachineKey.of(Box.class, Opaque.class, OpaqueMove.class);
        GraphDefinition<Box, Opaque, OpaqueMove> graph = StateGraph.define(key)
                .initial(Opaque.START).terminal(Opaque.END)
                .statePolicy(Opaque.START, "start moves on").statePolicy(Opaque.WAIT, "waits for its child")
                .reject(Opaque.START, OpaqueMove.FINISH, "nothing to finish yet")
                .transition(OpaqueMove.GO, Opaque.START, Opaque.WAIT)
                    .guard("open", (GuardContext<Box, Opaque, OpaqueMove> ctx) -> GuardResult.pass())
                    .action("go", (TransitionContext<Box, Opaque, OpaqueMove> ctx) -> ActionOutcome.ok())
                    .onFailure(OpaqueMove.BACK)
                    .endTransition()
                .transition(OpaqueMove.BACK, Opaque.START, Opaque.END)
                    .action("back", (TransitionContext<Box, Opaque, OpaqueMove> ctx) -> ActionOutcome.ok())
                    .recover(Jammed.class, OpaqueMove.GO)
                    .endTransition()
                .transition(OpaqueMove.FINISH, Opaque.WAIT, Opaque.END).endTransition()
                .watch(Opaque.START, childKey, (Box box) -> List.of(), (Box peer) -> Optional.<Box>empty())
                    .when("never", (Box box, java.util.Collection<? extends Box> peers,
                            PeerView<Box, OpaqueStep, OpaqueStepMove> view) -> false)
                    .execute(OpaqueMove.GO)
                    .endWatch()
                .submachine(Opaque.WAIT, child).exit(OpaqueStep.DONE, OpaqueMove.FINISH).endSubmachine()
                .build();
        assertEquals(3, graph.transitions().size());
    }

    @Test
    void specValuesWhoseConstantsCannotBePrintedConstruct() {
        RecoveryRule<OpaqueMove> rule = new RecoveryRule<>(Jammed.class, OpaqueMove.BACK, List.of());
        TransitionSpec<Box, Opaque, OpaqueMove> spec = new TransitionSpec<>(Opaque.START, OpaqueMove.GO, Opaque.END,
                Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        StatePolicy<Opaque, OpaqueMove> policy = new StatePolicy<>(Opaque.START, "start moves on",
                Map.of(OpaqueMove.FINISH, "nothing to finish yet"));
        WatchSpec<Box, Opaque, OpaqueMove, Box, Opaque, OpaqueMove> watch = new WatchSpec<>(Opaque.START,
                MachineKey.of(Box.class, Opaque.class, OpaqueMove.class), (Box box) -> List.of(),
                (Box peer) -> Optional.<Box>empty(), List.of(new WatchRule<>(new NamedCondition<>("never",
                        (Box box, java.util.Collection<? extends Box> peers,
                                PeerView<Box, Opaque, OpaqueMove> view) -> false), OpaqueMove.GO)));
        assertEquals(OpaqueMove.BACK, rule.transition());
        assertEquals(OpaqueMove.GO, spec.transition());
        assertEquals("start moves on", policy.reasonFor(OpaqueMove.GO));
        assertEquals(Opaque.START, watch.state());
    }

    @Test
    void draftProblemsNameConstantsByName() {
        GraphBuilder<Box, Shown, ShownMove> builder = StateGraph.define(SHOWN)
                .initial(Shown.START).terminal(Shown.END)
                .statePolicy(Shown.START, "start moves on").statePolicy(Shown.START, "again")
                .reject(Shown.START, ShownMove.BACK, "no").reject(Shown.START, ShownMove.BACK, "still no")
                .transition(ShownMove.GO, Shown.START, Shown.END).onFailure(ShownMove.BACK).endTransition();
        // A condition left without its execute(...): the chain is dropped and the graph built from its builder.
        builder.watch(Shown.START, SHOWN, (Box box) -> List.of(), (Box peer) -> Optional.<Box>empty())
                .when("pending", ConstantNamesTest::never);
        builder.watch(Shown.START, SHOWN, (Box box) -> List.of(), (Box peer) -> Optional.<Box>empty()).endWatch();
        InvalidGraphException error = assertThrows(InvalidGraphException.class, builder::build);
        assertEquals(List.of(
                        "the state START declares two policies",
                        "the state START rejects BACK twice",
                        "the state START declares two watches",
                        "the watch of START declares when(pending) without execute(...)",
                        "the watch of START declares no when(...).execute(...) rule",
                        "START -GO-> END declares onFailure or failsOn without an action"),
                error.problems().stream().map((InvalidGraphException.Problem problem) -> problem.message())
                        .sorted((String left, String right) -> order(left) - order(right)).toList());
    }

    @Test
    void lateCallsNameConstantsByName() {
        GraphBuilder<Box, Shown, ShownMove> builder = StateGraph.define(SHOWN).initial(Shown.START)
                .terminal(Shown.END).statePolicy(Shown.START, "start moves on");
        TransitionBuilder<Box, Shown, ShownMove> edge = builder.transition(ShownMove.GO, Shown.START, Shown.END);
        WatchBuilder<Box, Shown, ShownMove, Box, Shown, ShownMove> watch = builder.watch(Shown.START, SHOWN,
                (Box box) -> List.of(), (Box peer) -> Optional.<Box>empty());
        // A failed build() spends the builder too, so the open edge and the empty watch are reported and then refused.
        InvalidGraphException failed = assertThrows(InvalidGraphException.class, builder::build);
        assertEquals(List.of("the watch of START declares no when(...).execute(...) rule",
                        "START -GO-> END was never closed with endTransition()"),
                failed.problems().stream().map((InvalidGraphException.Problem problem) -> problem.message()).toList());
        assertEquals("build() was already called on the graph Box/Shown, so START -GO-> END can no longer change",
                assertThrows(IllegalStateException.class, () -> edge.endTransition()).getMessage());
        assertEquals("build() was already called on the graph Box/Shown, so the watch of START can no longer change",
                assertThrows(IllegalStateException.class, () -> watch.when("late", ConstantNamesTest::never))
                        .getMessage());
    }

    @Test
    void specRefusalsNameConstantsByName() {
        assertEquals("START -GO-> END: timeout is only allowed on an asynchronous action",
                assertThrows(IllegalArgumentException.class, () -> new TransitionSpec<Box, Shown, ShownMove>(
                        Shown.START, ShownMove.GO, Shown.END, Optional.empty(), List.of(), Optional.empty(),
                        List.of(), Optional.of(Duration.ofSeconds(1)), Optional.empty())).getMessage());
        assertEquals("recover(" + Jammed.class.getName() + ", BACK) declares java.lang.IllegalStateException more"
                        + " than once",
                assertThrows(IllegalArgumentException.class, () -> new RecoveryRule<>(Jammed.class, ShownMove.BACK,
                        List.of(IllegalStateException.class, IllegalStateException.class))).getMessage());
        assertEquals("the policy of START has a blank reason",
                assertThrows(IllegalArgumentException.class, () -> new StatePolicy<Shown, ShownMove>(Shown.START,
                        " ", Map.of())).getMessage());
        assertEquals("the watch of START has no rules",
                assertThrows(IllegalArgumentException.class, () -> new WatchSpec<Box, Shown, ShownMove, Box, Shown,
                        ShownMove>(Shown.START, SHOWN, (Box box) -> List.of(), (Box peer) -> Optional.<Box>empty(),
                        List.of())).getMessage());
    }

    private static boolean never(Box box, java.util.Collection<? extends Box> peers,
            PeerView<Box, Shown, ShownMove> view) {
        return false;
    }

    /** The test's own order of the expected messages, so it does not depend on how problems are sorted. */
    private static int order(String message) {
        List<String> prefixes = List.of("the state START declares two policies", "the state START rejects",
                "the state START declares two watches", "the watch of START declares when",
                "the watch of START declares no", "START -GO-> END");
        for (int index = 0; index < prefixes.size(); index++) {
            if (message.startsWith(prefixes.get(index))) {
                return index;
            }
        }
        return prefixes.size();
    }
}
