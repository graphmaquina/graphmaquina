package io.stategraph.runtime;

import static io.stategraph.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.stategraph.api.GraphDefinition;
import io.stategraph.api.StateMachine;
import io.stategraph.dsl.StateGraph;
import io.stategraph.fixtures.Doc;
import io.stategraph.fixtures.DocStatus;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuntimeNullBoundariesTest {

    private static final GraphDefinition<Doc, DocStatus> GRAPH = StateGraph.define(DOCS)
            .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
            .from(DocStatus.DRAFT).to(DocStatus.REVIEW).move().stay().forbidOthers("x")
            .from(DocStatus.REVIEW).to(DocStatus.PUBLISHED).move().stay().forbidOthers("x")
            .from(DocStatus.PUBLISHED).to(DocStatus.ARCHIVED).move().stay().forbidOthers("x")
            .from(DocStatus.ARCHIVED).stay().forbidOthers("x")
            .build();

    static Stream<Arguments> entries() {
        StateMachine<Doc, DocStatus> machine = StateMachines.from(GRAPH).state(Doc::status, Doc::setStatus).build();
        return Stream.of(
                Arguments.of("definition", (Executable) () -> StateMachines.from(null)),
                Arguments.of("getter", (Executable) () -> StateMachines.from(GRAPH).state(null, Doc::setStatus)),
                Arguments.of("setter", (Executable) () -> StateMachines.from(GRAPH).state(Doc::status, null)),
                Arguments.of("identity", (Executable) () -> StateMachines.from(GRAPH).identity(null)),
                Arguments.of("timeout", (Executable) () -> StateMachines.from(GRAPH).asyncTimeout(null)),
                Arguments.of("entity", (Executable) () -> machine.transitionTo(null, DocStatus.REVIEW)),
                Arguments.of("target", (Executable) () -> machine.transitionTo(new Doc("1", DocStatus.DRAFT), null)),
                Arguments.of("state", (Executable) () -> machine.allowedFrom(null)),
                Arguments.of("builder", (Executable) () -> MachineRegistry.builder().add(null)),
                Arguments.of("key", (Executable) () -> MachineRegistry.builder().build().of(null)));
    }

    @ParameterizedTest
    @MethodSource("entries")
    void rejectsNullWithParameterName(String parameter, Executable call) {
        NullPointerException error = assertThrows(NullPointerException.class, call);
        assertEquals(parameter, error.getMessage());
    }
}
