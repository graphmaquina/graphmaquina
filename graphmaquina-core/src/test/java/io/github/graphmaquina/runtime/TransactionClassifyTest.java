package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The last word on what a root throws: a failure of the call is kept as it is, and anything else that engine code let
 * through before the commit becomes an abort of the call, in its current phase, that carries it.
 */
class TransactionClassifyTest {

    private static Transaction transaction(Doc doc) {
        return new Transaction(null, new UnitOfWork(10, Long.MAX_VALUE), DOCS, doc, SUBMIT, false, Optional.empty());
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
