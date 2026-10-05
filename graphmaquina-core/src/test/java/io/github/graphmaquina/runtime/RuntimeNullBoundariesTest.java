package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runtime entry points refuse a null argument with a NullPointerException whose message is the parameter's name: the
 * machine builder, a machine's {@code execute}, {@code transitionsFrom} and {@code supplyAsync}, and the registry
 * builder and its lookup.
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
                Arguments.of("definition", (Executable) () -> StateMachines.from(null)),
                Arguments.of("getter", (Executable) () -> StateMachines.from(GRAPH).state(null, Doc::setStatus)),
                Arguments.of("setter", (Executable) () -> StateMachines.from(GRAPH).state(Doc::status, null)),
                Arguments.of("identity", (Executable) () -> StateMachines.from(GRAPH).identity(null)),
                Arguments.of("timeout", (Executable) () -> StateMachines.from(GRAPH).asyncTimeout(null)),
                Arguments.of("entity", (Executable) () -> machine.execute(null, DocTransition.SUBMIT)),
                Arguments.of("transition", (Executable) () -> machine.execute(new Doc("1", DocStatus.DRAFT), null)),
                Arguments.of("state", (Executable) () -> machine.transitionsFrom(null)),
                Arguments.of("builder", (Executable) () -> MachineRegistry.builder().add(null)),
                Arguments.of("key", (Executable) () -> MachineRegistry.builder().build().of(null)),
                Arguments.of("listener", (Executable) () -> MachineRegistry.builder().listener(null)),
                Arguments.of("facade", (Executable) () -> StateMachines.from(GRAPH).as(null)),
                Arguments.of("call", (Executable) () -> machine.supplyAsync(null)));
    }

    @ParameterizedTest
    @MethodSource("entries")
    void rejectsNullWithParameterName(String parameter, Executable call) {
        NullPointerException error = assertThrows(NullPointerException.class, call);
        assertEquals(parameter, error.getMessage());
    }
}
