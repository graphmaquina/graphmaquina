package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runtime entry points refuse a null argument with a NullPointerException whose message names the parameter, bare or,
 * where Lombok checks it, as {@code <name> is marked non-null but is null}: the machine builder, a machine's
 * {@code execute}, its input included, {@code advance}, {@code transitionsFrom} and {@code supplyAsync}, the
 * {@code execute}, its input included, {@code reevaluate} and {@code advance} of its {@code async()} view, all on the
 * calling thread, and the registry builder and its lookup. An operation that a submitted call runs refuses its null
 * argument on the executor's thread instead, which fails the future.
 */
class RuntimeNullBoundariesTest {

    private static final GraphDefinition<Doc, DocStatus, DocTransition> GRAPH = StateGraph.define(DOCS)
            .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
            .statePolicy(DocStatus.DRAFT, "x").statePolicy(DocStatus.REVIEW, "x").statePolicy(DocStatus.PUBLISHED, "x")
            .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).endTransition()
            .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
            .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
            .build();

    static Stream<Arguments> entries() {
        StateMachine<Doc, DocStatus, DocTransition> machine =
                StateMachines.from(GRAPH).state(Doc::status, Doc::setStatus).build();
        return Stream.of(
                Arguments.of("definition is marked non-null but is null", (Executable) () -> StateMachines.from(null)),
                Arguments.of("getter", (Executable) () -> StateMachines.from(GRAPH).state(null, Doc::setStatus)),
                Arguments.of("setter", (Executable) () -> StateMachines.from(GRAPH).state(Doc::status, null)),
                Arguments.of("identity", (Executable) () -> StateMachines.from(GRAPH).identity(null)),
                Arguments.of("timeout", (Executable) () -> StateMachines.from(GRAPH).asyncTimeout(null)),
                Arguments.of("entity is marked non-null but is null", (Executable) () ->
                        machine.execute(null, DocTransition.SUBMIT)),
                Arguments.of("transition is marked non-null but is null", (Executable) () ->
                        machine.execute(new Doc("1", DocStatus.DRAFT), null)),
                Arguments.of("input is marked non-null but is null", (Executable) () ->
                        machine.execute(new Doc("1", DocStatus.DRAFT), DocTransition.SUBMIT, null)),
                Arguments.of("state is marked non-null but is null", (Executable) () -> machine.transitionsFrom(null)),
                Arguments.of("builder", (Executable) () -> MachineRegistry.builder().add(null)),
                Arguments.of("key is marked non-null but is null", (Executable) () ->
                        MachineRegistry.builder().build().of(null)),
                Arguments.of("observer", (Executable) () -> MachineRegistry.builder().observer(null)),
                Arguments.of("facade", (Executable) () -> StateMachines.from(GRAPH).as(null)),
                Arguments.of("call is marked non-null but is null", (Executable) () -> machine.supplyAsync(null)),
                Arguments.of("entity is marked non-null but is null", (Executable) () ->
                        machine.async().execute(null, DocTransition.SUBMIT)),
                Arguments.of("transition is marked non-null but is null", (Executable) () ->
                        machine.async().execute(new Doc("1", DocStatus.DRAFT), null)),
                Arguments.of("input is marked non-null but is null", (Executable) () ->
                        machine.async().execute(new Doc("1", DocStatus.DRAFT), DocTransition.SUBMIT, null)),
                Arguments.of("entity is marked non-null but is null", (Executable) () ->
                        machine.async().reevaluate(null)),
                Arguments.of("entity is marked non-null but is null", (Executable) () -> machine.advance(null)),
                Arguments.of("entity is marked non-null but is null", (Executable) () ->
                        machine.async().advance(null)));
    }

    @ParameterizedTest
    @MethodSource("entries")
    void rejectsNullWithParameterName(String parameter, Executable call) {
        NullPointerException error = assertThrows(NullPointerException.class, call);
        assertEquals(parameter, error.getMessage());
    }

    /** The execute that a call submitted through supplyAsync runs refuses its null entity there: the future fails. */
    @Test
    void aNullArgumentOfASubmittedOperationFailsItsFuture() {
        StateMachine<Doc, DocStatus, DocTransition> machine =
                StateMachines.from(GRAPH).state(Doc::status, Doc::setStatus).build();
        CompletableFuture<Outcome<Doc, DocStatus, DocTransition>> pending =
                machine.supplyAsync(() -> machine.execute(null, DocTransition.SUBMIT));
        CompletionException error = assertThrows(CompletionException.class, pending::join);
        NullPointerException cause = assertInstanceOf(NullPointerException.class, error.getCause());
        assertEquals("entity is marked non-null but is null", cause.getMessage());
    }
}
