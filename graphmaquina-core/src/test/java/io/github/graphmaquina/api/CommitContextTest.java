package io.github.graphmaquina.api;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.internal.UnboundFlow;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A commit context is a record of the committed hop and the call's flow. Its five-argument constructor gives a flow
 * that belongs to no call, so a hook can be called by hand, in a unit test, and what it wrote read back. Two contexts
 * are equal when their components are, the flow included.
 */
class CommitContextTest {

    private static final FlowKey<String> NOTE = FlowKey.of("note", String.class);

    private static CommitContext<DocStatus, DocTransition> submitted() {
        return new CommitContext<>(DRAFT, SUBMIT, REVIEW, TransitionKind.MOVE, false);
    }

    @Test
    void theFiveArgumentConstructorGivesAFlowThatBelongsToNoCall() {
        CommitContext<DocStatus, DocTransition> context = submitted();
        assertInstanceOf(UnboundFlow.class, context.flow());
        assertEquals(Set.of(), context.flow().keys());
        assertEquals(Optional.empty(), context.flow().entity());
    }

    @Test
    void theCanonicalConstructorKeepsTheFlowItIsGiven() {
        FlowContext flow = new UnboundFlow();
        assertSame(flow, new CommitContext<>(DRAFT, SUBMIT, REVIEW, TransitionKind.MOVE, false, flow).flow());
    }

    @Test
    void aHookCalledByHandWritesToTheFlowOfItsContext() {
        HookRef<Doc, DocStatus, DocTransition> hook = new HookRef<>("Persist",
                (Doc doc, CommitContext<DocStatus, DocTransition> commit) ->
                        commit.flow().put(NOTE, doc.id() + ":" + commit.to()));
        CommitContext<DocStatus, DocTransition> context = submitted();
        hook.run(new Doc("1", REVIEW), context);
        assertEquals(Optional.of("1:REVIEW"), context.flow().get(NOTE));
    }

    @Test
    void twoContextsAreEqualWhenTheirMetadataAndTheirFlowsAre() {
        CommitContext<DocStatus, DocTransition> first = submitted();
        CommitContext<DocStatus, DocTransition> second = submitted();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        first.flow().put(NOTE, "n-1");
        assertNotEquals(first, second);
        second.flow().put(NOTE, "n-1");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(submitted(), new CommitContext<>(DRAFT, SUBMIT, REVIEW, TransitionKind.MOVE, true));
    }

    @Test
    void aNullFlowIsRefused() {
        NullPointerException refused = assertThrows(NullPointerException.class,
                () -> new CommitContext<>(DRAFT, SUBMIT, REVIEW, TransitionKind.MOVE, false, null));
        assertEquals("flow is marked non-null but is null", refused.getMessage());
    }
}
