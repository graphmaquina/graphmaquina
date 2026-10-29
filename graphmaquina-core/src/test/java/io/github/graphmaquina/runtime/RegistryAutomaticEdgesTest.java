package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.plain;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A registry reads the automatic edges of every state of its graphs once, as it is built. When none has one, its calls
 * run no automatic chain: whatever they commit, they read no automatic edge.
 */
class RegistryAutomaticEdgesTest {

    /**
     * {@code graph} as a definition written by hand, which adds to {@code reads} each state whose automatic edges it
     * is asked for.
     */
    @SuppressWarnings("unchecked")
    private static GraphDefinition<Doc, DocStatus, DocTransition> counting(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, List<DocStatus> reads) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            if (method.getName().equals("automaticFrom")) {
                reads.add((DocStatus) args[0]);
            }
            return method.invoke(graph, args);
        };
        return (GraphDefinition<Doc, DocStatus, DocTransition>) Proxy.newProxyInstance(
                GraphDefinition.class.getClassLoader(), new Class<?>[] {GraphDefinition.class}, handler);
    }

    @Test
    void aRegistryWithoutAutomaticEdgesReadsThemOnlyAsItIsBuilt() {
        List<DocStatus> reads = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> docs = StateMachines.from(counting(plain(), reads))
                .state(Doc::status, Doc::setStatus).identity(Doc::id).build();
        assertEquals(List.of(DocStatus.values()), reads);
        reads.clear();
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = docs.execute(doc, SUBMIT);
        assertEquals(List.of(OutcomeCode.APPLIED, List.of()), List.of(outcome.code(), outcome.automaticSteps()));
        assertEquals(REVIEW, doc.status());
        assertEquals(List.of(), reads);
    }
}
