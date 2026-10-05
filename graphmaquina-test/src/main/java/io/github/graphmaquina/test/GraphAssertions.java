package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.GuardCall;
import java.util.function.Function;

/**
 * Single entry point to the typed views: one {@code assertThat} per subject a test holds, each returning
 * the same view its own class exposes. The facade asserts nothing and validates nothing of its own, so a
 * null subject reaches the view untouched and fails, inspectably, on the first expectation called on it.
 *
 * <p>Entity and state views keep the entity and state types of their subjects, which is what makes a chain typed:
 * the state enum of the machine under test is the only one the compiler accepts anywhere along it.
 *
 * <p>Three shapes are deliberately absent. A list of transition records, a list of reactions and a journal
 * of action records all erase to the same parameter, so one facade cannot carry all three; every one of them
 * keeps its own factory, {@link TransitionRecordsAssert#assertThat(java.util.List)},
 * {@link ReactionsAssert#assertThat(java.util.List)} and {@link JournalAssert#assertThat(java.util.List)}.
 * Probes, snapshots, goldens and bounded waits are not subjects to assert over but tools to build one, and
 * stay where they are — {@link AsyncAssertions}, {@link GraphSnapshots}, {@link GraphGoldens} and the
 * {@code probe} package. Recorded guard and action calls are subjects for {@link CallInputAssert}.
 */
public final class GraphAssertions {

    private GraphAssertions() { }

    public static <E, S extends Enum<S>, T extends Enum<T>> GraphAssert<E, S, T> assertThat(
            GraphDefinition<E, S, T> actual) {
        return GraphAssert.assertThat(actual);
    }

    public static <E, S extends Enum<S>, T extends Enum<T>> OutcomeAssert<E, S, T> assertThat(
            Outcome<E, S, T> actual) {
        return OutcomeAssert.assertThat(actual);
    }

    public static ActionOutcomeAssert assertThat(ActionOutcome actual) {
        return ActionOutcomeAssert.assertThat(actual);
    }

    public static GuardResultAssert assertThat(GuardResult actual) {
        return GuardResultAssert.assertThat(actual);
    }

    /** Expectations over the input in a guard's recorded invocation. */
    public static CallInputAssert assertThat(GuardCall<?, ?, ?> actual) {
        return CallInputAssert.assertThat(actual);
    }

    /** Expectations over the input in an action's recorded invocation. */
    public static CallInputAssert assertThat(ActionCall<?, ?, ?> actual) {
        return CallInputAssert.assertThat(actual);
    }

    /**
     * Any throwable the engine raised, narrowed on the returned view. Overload selection is static, so this
     * is the overload a subject whose declared type is {@code Throwable} gets — which is what
     * {@link #captureFailure} returns; from there a graph the DSL refused to build is reached through
     * {@link TransitionFailureAssert#isInvalidGraph()}, not by casting. Only a subject whose declared type
     * is {@link InvalidGraphException} binds to the more specific overload below.
     */
    public static TransitionFailureAssert assertThat(Throwable actual) {
        return TransitionFailureAssert.assertThat(actual);
    }

    public static InvalidGraphAssert assertThat(InvalidGraphException actual) {
        return InvalidGraphAssert.assertThat(actual);
    }

    /** The state an entity is observed to be in, read through {@code getter} when the expectation runs. */
    public static <E, S extends Enum<S>> EntityStateAssert<E, S> assertThat(
            E actual, Function<? super E, ? extends S> getter) {
        return EntityStateAssert.assertThat(actual, getter);
    }

    /**
     * Runs {@code operation} and returns the exception it threw, for handing to {@link #assertThat(Throwable)}.
     * It catches no {@link Error}, so an {@link AssertionError} raised inside the body propagates untouched,
     * and a body that completes normally fails with a {@link GraphAssertionError}.
     */
    public static Throwable captureFailure(ThrowingRunnable operation) {
        return TransitionFailureAssert.captureFailure(operation);
    }
}
