package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The types a guard's {@code propagates} lists follow the rule of {@code failsOn} (SG302): only throwable types foreign
 * to the library, never one too broad, never an Error or a CancellationException, never CompletionException or
 * ExecutionException themselves. An admitted type, a checked one included, is kept on the guard's reference.
 */
class GuardPropagatesValidationTest {

    private static final TransitionGuard<Doc, DocStatus, DocTransition> OPEN =
            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass();

    private static GraphBuilder<Doc, DocStatus, DocTransition> declaring(List<Class<? extends Throwable>> types) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).guard("kycReady", OPEN, types).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
    }

    static Stream<Class<? extends Throwable>> forbidden() {
        return Stream.of(Throwable.class, Exception.class, RuntimeException.class, AssertionError.class,
                TransitionAbortedException.class, CancellationException.class, CompletionException.class,
                ExecutionException.class);
    }

    @ParameterizedTest
    @MethodSource("forbidden")
    void aForbiddenTypeIsSg302(Class<? extends Throwable> type) {
        InvalidGraphException refused = assertThrows(InvalidGraphException.class,
                () -> declaring(List.of(type)).build());

        assertEquals(List.of(DiagnosticCode.SG302.name()), refused.codes());
        assertTrue(refused.problems().get(0).message().contains("propagates " + type.getSimpleName()),
                refused.problems().get(0).message());
    }

    @Test
    void anAdmittedTypeIsKeptOnTheGuard() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph =
                declaring(List.of(IllegalStateException.class, IOException.class)).build();

        GuardRef<Doc, DocStatus, DocTransition> guard =
                graph.findTransition(DRAFT, SUBMIT).orElseThrow().guards().get(0);

        assertEquals(List.of(IllegalStateException.class, IOException.class), guard.propagates());
    }

    @Test
    void aGuardWithoutPropagatesDeclaresNone() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = declaring(List.of()).build();

        assertEquals(List.of(), graph.findTransition(DRAFT, SUBMIT).orElseThrow().guards().get(0).propagates());
    }
}
