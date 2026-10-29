package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.CellChains;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The post-commit scopes of a thread: a commit reports to every scope open around it, compared as the commit log
 * compares entities; an identity that throws as a scope indexes it fails only the lookups of that identity; a scope
 * closes as the innermost open one; and a scope whose closing a StackOverflowError interrupted after its first store,
 * which marks it closed, takes no more reports and is dropped by the next scope the thread opens or closes, so it
 * neither grows nor makes the closing of the scope around it throw. A real overflow, wherever it strikes while scopes
 * open and close, leaves none open. A registry whose graphs have no automatic edge opens none for a call, and one with
 * an automatic edge opens one, which a hook of the call sees open.
 */
class PostCommitScopeTest {

    private static final long STACK = 256 * 1024;

    /**
     * An identity whose hashCode throws.
     *
     * @param value the id
     */
    record Touchy(String value) {

        @Override
        public int hashCode() {
            throw new IllegalStateException("no hash for " + value);
        }
    }

    /** Opens a scope inside each scope it opened, closing each as a post-commit does, until the stack overflows. */
    private static void nest() {
        PostCommitScope scope = PostCommitScope.open();
        try {
            nest();
        } finally {
            scope.closed = true;
            scope.close();
        }
    }

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

    /**
     * The doc machine with a hook on SUBMIT, in a registry of its own; {@code withAutomaticEdge} marks ARCHIVE
     * automatic, an edge the test never reaches, which is enough for the registry to open a scope for each call.
     */
    private static StateMachine<Doc, DocStatus, DocTransition> docsWatchedBy(
            List<Boolean> seen, boolean withAutomaticEdge) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DocStatus.DRAFT)
                .terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "a draft is submitted")
                .statePolicy(DocStatus.REVIEW, "a review is published")
                .statePolicy(DocStatus.PUBLISHED, "a publication is archived")
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW)
                    .afterCommit("seen", (Doc doc) -> seen.add(PostCommitScope.anyOpen())).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition();
        TransitionBuilder<Doc, DocStatus, DocTransition> archive =
                graph.transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED);
        graph = (withAutomaticEdge ? archive.automatic() : archive).endTransition();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph.build()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .build()
                .of(DOCS);
    }

    /** The scope is skipped when no graph of the registry has an automatic edge, and opened when one has. */
    @Test
    void aHookSeesAScopeOnlyInARegistryWithAnAutomaticEdge() {
        List<Boolean> without = new ArrayList<>();
        List<Boolean> with = new ArrayList<>();
        docsWatchedBy(without, false).execute(new Doc("d1", DocStatus.DRAFT), DocTransition.SUBMIT);
        docsWatchedBy(with, true).execute(new Doc("d2", DocStatus.DRAFT), DocTransition.SUBMIT);
        assertEquals(List.of(List.of(false), List.of(true)), List.of(without, with));
        assertFalse(PostCommitScope.anyOpen());
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

    /**
     * An entry whose identity throws as the scope indexes it is set aside: lookups of other entities answer as before,
     * however often they run, and only a lookup of that identity throws, from its own hashCode.
     */
    @Test
    void anIdentityThatThrowsAsItIsIndexedFailsOnlyItsOwnLookups() {
        Doc doc = new Doc("t1", DocStatus.DRAFT);
        PostCommitScope scope = PostCommitScope.open();
        PostCommitScope.report(List.of(new EntityRef(DOCS, new Touchy("t1"), doc), ref("d1")));
        scope.closed = true;
        scope.close();
        assertTrue(scope.movedBelow(ref("d1")));
        assertFalse(scope.movedBelow(ref("d2")));
        EntityRef touchy = new EntityRef(DOCS, new Touchy("t1"), doc);
        assertThrows(IllegalStateException.class, () -> scope.movedBelow(touchy));
        assertFalse(scope.movedBelow(ref("d3")));
    }

    /**
     * On a thread of a small stack, scopes open inside each other until the stack overflows, in rounds, inside a scope
     * opened first: the overflow, wherever it strikes, in an open, in a close or between them, reaches the round as
     * itself, and the first scope still takes reports and closes as the innermost one open. Where the overflow strikes
     * depends on the JIT, so the rounds prove nothing when they pass by chance; they fail when an overflow leaves a
     * scope open.
     */
    @Test
    void anOverflowWhileScopesOpenOrCloseLeavesNoneOpen() throws InterruptedException {
        Throwable thrown = CellChains.onStack(STACK, () -> {
            for (int round = 0; round < 20; round++) {
                PostCommitScope outer = PostCommitScope.open();
                assertThrows(StackOverflowError.class, PostCommitScopeTest::nest);
                PostCommitScope.report(List.of(ref("d" + round)));
                outer.closed = true;
                outer.close();
                assertTrue(outer.movedBelow(ref("d" + round)));
            }
        });
        assertEquals(null, thrown);
    }
}
