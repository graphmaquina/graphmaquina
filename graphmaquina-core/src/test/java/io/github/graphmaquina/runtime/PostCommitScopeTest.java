package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The post-commit scopes of a thread: a commit reports to every scope open around it, compared as the commit log
 * compares entities; a scope closes as the innermost open one; and a scope whose closing a StackOverflowError
 * interrupted after its first store, which marks it closed, takes no more reports and is dropped by the next scope
 * the thread opens or closes, so it neither grows nor makes the closing of the scope around it throw.
 */
class PostCommitScopeTest {

    private static EntityRef ref(String id) {
        return new EntityRef(DOCS, id, new Doc(id, DocStatus.DRAFT));
    }

    /** Each open scope takes the report; one closed before it does not. Entities compare by key and identity. */
    @Test
    void aCommitReportsToEveryOpenScope() {
        PostCommitScope outer = PostCommitScope.open();
        PostCommitScope inner = PostCommitScope.open();
        PostCommitScope.report(List.of(ref("d1")));
        inner.close();
        PostCommitScope.report(List.of(ref("d2")));
        outer.close();
        PostCommitScope.report(List.of(ref("d3")));
        assertEquals(List.of(true, false, true), List.of(inner.movedBelow(ref("d1")), inner.movedBelow(ref("d2")),
                outer.movedBelow(ref("d2"))));
        assertEquals(List.of(ref("d1"), ref("d2")), outer.moved());
        assertFalse(outer.movedBelow(ref("d3")));
    }

    /** Closing a scope while another one is open inside it is a misuse. */
    @Test
    void aScopeClosesOnlyAsTheInnermostOneOpen() {
        PostCommitScope outer = PostCommitScope.open();
        PostCommitScope inner = PostCommitScope.open();
        IllegalStateException misuse = assertThrows(IllegalStateException.class, outer::close);
        assertEquals("a post-commit scope closes as the innermost one open on its thread", misuse.getMessage());
        inner.close();
        outer.close();
    }

    /**
     * An inner scope marked closed, whose close() never ran, takes no report, and the outer scope still closes; an
     * outermost one left so is dropped when the thread opens its next scope.
     */
    @Test
    void aScopeLeftClosedByAnOverflowIsDropped() {
        PostCommitScope outer = PostCommitScope.open();
        PostCommitScope inner = PostCommitScope.open();
        inner.closed = true;
        PostCommitScope.report(List.of(ref("d1")));
        assertEquals(List.of(), inner.moved());
        outer.closed = true;
        outer.close();
        PostCommitScope stale = PostCommitScope.open();
        stale.closed = true;
        PostCommitScope next = PostCommitScope.open();
        PostCommitScope nested = PostCommitScope.open();
        PostCommitScope.report(List.of(ref("d2")));
        nested.close();
        next.close();
        assertTrue(next.movedBelow(ref("d2")));
        assertEquals(List.of(), stale.moved());
        PostCommitScope last = PostCommitScope.open();
        last.close();
    }
}
