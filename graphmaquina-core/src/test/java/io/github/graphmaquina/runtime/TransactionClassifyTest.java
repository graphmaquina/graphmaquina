package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.DayOfWeek;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The last word on what a root throws: a failure of the call is kept as it is, and anything else that engine code let
 * through before the commit becomes an abort of the call, in its current phase, that carries it.
 */
class TransactionClassifyTest {

    private static Transaction transaction(Doc doc) {
        return transaction(doc, SUBMIT);
    }

    private static Transaction transaction(Doc doc, DocTransition requested) {
        return new Transaction(null, new UnitOfWork(10, Long.MAX_VALUE), DOCS, doc, Optional.of(requested), false,
                Optional.empty());
    }

    /**
     * DRAFT -SUBMIT-> REVIEW -PUBLISH-> PUBLISHED -ARCHIVE-> ARCHIVED, with DRAFT -ARCHIVE-> ARCHIVED too. SUBMIT's
     * action fails, and its failure route is ARCHIVE.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> graph() {
        return StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                .action("refuse", (TransitionContext<Doc, DocStatus, DocTransition> context) ->
                        ActionOutcome.failed("refused"))
                .onFailure(ARCHIVE).endTransition()
                .transition(ARCHIVE, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** {@code graph}, except that it finds no edge for ARCHIVE from DRAFT, which breaks its own failure route. */
    @SuppressWarnings("unchecked")
    private static GraphDefinition<Doc, DocStatus, DocTransition> withoutTheRoute(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return (GraphDefinition<Doc, DocStatus, DocTransition>) Proxy.newProxyInstance(
                GraphDefinition.class.getClassLoader(), new Class<?>[] {GraphDefinition.class},
                (Object proxy, Method method, Object[] arguments) -> method.getName().equals("findTransition")
                        && arguments[0] == DRAFT && arguments[1] == ARCHIVE
                        ? Optional.empty() : method.invoke(graph, arguments));
    }

    private static DefaultStateMachine<Doc, DocStatus, DocTransition> machine(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, Function<Doc, DocStatus> getter) {
        return (DefaultStateMachine<Doc, DocStatus, DocTransition>) StateMachines.from(graph)
                .state(getter, Doc::setStatus).build();
    }

    private static AbortDetails details(Doc doc) {
        return new AbortDetails(DOCS, doc, Optional.of(DRAFT), Optional.of(SUBMIT), Optional.of(REVIEW),
                Optional.empty(), List.of(), List.of());
    }

    @Test
    void aFailureOfTheCallIsKeptAsItIs() {
        Doc doc = new Doc("1", DRAFT);
        Transaction tx = transaction(doc);
        TransitionAbortedException own =
                new TransitionAbortedException(tx.call(), CallPhase.ACTION, details(doc), "no");
        assertSame(own, tx.classify(own));
    }

    @Test
    void anythingElseBecomesAnAbortOfTheCallInItsPhaseThatCarriesIt() {
        Doc doc = new Doc("1", DRAFT);
        Transaction tx = transaction(doc);
        tx.enter(CallPhase.ACTION);
        IllegalStateException boom = new IllegalStateException("hashCode failed");
        TransitionAbortedException abort = assertInstanceOf(TransitionAbortedException.class, tx.classify(boom));
        assertSame(boom, abort.getCause());
        assertSame(tx.call(), abort.call());
        assertEquals(CallPhase.ACTION, abort.phase());
        assertSame(doc, abort.entity());
        assertEquals(Optional.empty(), abort.from());
        assertEquals(Optional.of(SUBMIT), abort.transition());
        assertEquals(Optional.empty(), abort.to());
    }

    /**
     * A root step that throws once it has selected an edge records the state it read and that edge, and the abort
     * names them. Taking SUBMIT's failure route throws, since the definition finds no edge for the route it declared.
     */
    @Test
    void aRootStepThatThrowsAfterSelectingAnEdgeAbortsNamingTheStateItReadAndThatEdge() {
        Doc doc = new Doc("1", DRAFT);
        Transaction tx = transaction(doc);
        DefaultStateMachine<Doc, DocStatus, DocTransition> machine = machine(withoutTheRoute(graph()), Doc::status);
        NoSuchElementException thrown =
                assertThrows(NoSuchElementException.class, () -> tx.transition(machine, doc, SUBMIT, 0));
        TransitionAbortedException abort = assertInstanceOf(TransitionAbortedException.class, tx.classify(thrown));
        assertSame(thrown, abort.getCause());
        assertSame(tx.call(), abort.call());
        assertEquals(CallPhase.ACTION, abort.phase());
        assertEquals(Optional.of(DRAFT), abort.from());
        assertEquals(Optional.of(SUBMIT), abort.transition());
        assertEquals(Optional.of(REVIEW), abort.to());
    }

    /**
     * A root step that throws before it selects an edge records the state it read, and the abort names it with the
     * identifier the call requested. The getter broke its type through an unchecked cast, so findTransition refuses
     * the state it read.
     */
    @Test
    void aRootStepThatThrowsBeforeSelectingAnEdgeAbortsNamingTheStateItReadAndTheRequestedIdentifier() {
        Doc doc = new Doc("1", DRAFT);
        Transaction tx = transaction(doc, PUBLISH);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Function<Doc, DocStatus> broken = (Function) (Function<Doc, Object>) (Doc read) -> DayOfWeek.MONDAY;
        DefaultStateMachine<Doc, DocStatus, DocTransition> machine = machine(graph(), broken);
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> tx.transition(machine, doc, PUBLISH, 0));
        TransitionAbortedException abort = assertInstanceOf(TransitionAbortedException.class, tx.classify(thrown));
        assertSame(thrown, abort.getCause());
        assertEquals(CallPhase.RESOLVE, abort.phase());
        assertEquals(Optional.of(DayOfWeek.MONDAY), abort.from());
        assertEquals(Optional.of(PUBLISH), abort.transition());
        assertEquals(Optional.empty(), abort.to());
    }

    @Test
    void aFailureOfAnotherCallIsWrappedLikeAnythingElse() {
        Doc doc = new Doc("1", DRAFT);
        Transaction tx = transaction(doc);
        Doc otherDoc = new Doc("2", DRAFT);
        Transaction other = transaction(otherDoc);
        TransitionAbortedException foreign =
                new TransitionAbortedException(other.call(), CallPhase.GUARD, details(otherDoc), "no");
        tx.enter(CallPhase.GUARD);
        CallFailedException failure = tx.classify(foreign);
        assertSame(foreign, failure.getCause());
        assertSame(tx.call(), failure.call());
        assertEquals(CallPhase.GUARD, failure.phase());
    }
}
