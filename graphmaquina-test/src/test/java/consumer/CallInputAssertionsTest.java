package consumer;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.test.CallInputAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.GuardCall;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link CallInputAssert}: the recorded per-invocation payload, read back by value, by identity
 * and by type, with every unmet expectation inspectable and every bad argument a programming error.
 */
class CallInputAssertionsTest {

    private final Entity entity = new Entity("e", State.NEW);

    @Test
    void equalityIdentityAndTypedChecksWorkForBothKindsOfCall() {
        String value = new String("eligible");
        for (CallInputAssert view : views(Optional.of(value))) {
            assertSame(view, view.hasInput("eligible").hasInputSameAs(value)
                    .inputSatisfies(CharSequence.class, input -> assertSame(value, input)));
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> view.hasInputSameAs(new String("eligible")));
            assertSame(value, error.actual());
            assertEquals("input identity", error.property());
        }
    }

    @Test
    void missingWrongAndUnexpectedInputsHaveInspectableFailures() {
        for (CallInputAssert view : views(Optional.empty())) {
            assertSame(view, view.hasNoInput());
            GraphAssertionError missing = assertThrows(GraphAssertionError.class, () -> view.hasInput("yes"));
            assertEquals("input", missing.property());
            assertEquals("yes", missing.expected());
            assertEquals(null, missing.actual());
            GraphAssertionError typed = assertThrows(GraphAssertionError.class,
                    () -> view.inputSatisfies(String.class, input -> { throw new AssertionError("unreached"); }));
            assertEquals("input type", typed.property());
            assertEquals(String.class, typed.expected());
        }
        for (CallInputAssert view : views(Optional.of(7))) {
            GraphAssertionError wrong = assertThrows(GraphAssertionError.class, () -> view.hasInput(8));
            assertEquals(7, wrong.actual());
            assertEquals(8, wrong.expected());
            GraphAssertionError unexpected = assertThrows(GraphAssertionError.class, view::hasNoInput);
            assertEquals("input", unexpected.property());
            assertEquals(7, unexpected.actual());
            GraphAssertionError type = assertThrows(GraphAssertionError.class,
                    () -> view.inputSatisfies(String.class, input -> { throw new AssertionError("unreached"); }));
            assertEquals(Integer.class, type.actual());
        }
    }

    @Test
    void nullSubjectsFailAsAssertionsAndBadArgumentsRemainProgrammingErrors() {
        CallInputAssert guard = assertThat((GuardCall<Entity, State, Id>) null);
        CallInputAssert action = assertThat((ActionCall<Entity, State, Id>) null);
        assertEquals("guard call", assertThrows(GraphAssertionError.class, guard::hasNoInput).context());
        assertEquals("action call", assertThrows(GraphAssertionError.class, action::hasNoInput).context());
        for (CallInputAssert view : List.of(guard, action, views(Optional.empty()).getFirst())) {
            assertThrows(NullPointerException.class, () -> view.hasInput(null));
            assertThrows(NullPointerException.class, () -> view.hasInputSameAs(null));
            assertThrows(NullPointerException.class, () -> view.inputSatisfies(null, input -> { }));
            assertThrows(NullPointerException.class, () -> view.inputSatisfies(Object.class, null));
            assertThrows(IllegalArgumentException.class, () -> view.inputSatisfies(int.class, input -> { }));
        }
    }

    @Test
    void nestedAssertionFailuresPropagateUnchanged() {
        AssertionError failure = new AssertionError("domain verdict mismatch");
        for (CallInputAssert view : views(Optional.of("input"))) {
            assertSame(failure, assertThrows(AssertionError.class,
                    () -> view.inputSatisfies(String.class, input -> { throw failure; })));
        }
    }

    private List<CallInputAssert> views(Optional<?> input) {
        return List.of(assertThat(new GuardCall<>(entity, State.NEW, Id.PREPARE, State.READY, input)),
                assertThat(new ActionCall<>(entity, State.NEW, Id.PREPARE, State.READY, 0, input)));
    }
}
