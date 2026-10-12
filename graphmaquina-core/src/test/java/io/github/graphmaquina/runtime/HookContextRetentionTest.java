package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A CommitContext may be kept after its hook, and its flow is stale from then on: keeping it must not keep the call
 * reachable, its unit of work and every value its steps put in the flow, removed ones included.
 */
class HookContextRetentionTest {

    private static final FlowKey<Object> LARGE = FlowKey.of("large", Object.class);

    /** Whether the referent of {@code weak} is collected within a few full collections. */
    private static boolean collected(WeakReference<Object> weak) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            System.gc();
            if (weak.get() == null) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void aCommitContextKeptAfterItsHookLetsTheCallsValuesBeCollected() throws InterruptedException {
        WeakReference<Object> control = new WeakReference<>(new byte[8_000_000]);
        assumeTrue(collected(control), "this JVM does not collect an unreachable value on System.gc()");
        List<WeakReference<Object>> written = new ArrayList<>();
        List<CommitContext<DocStatus, DocTransition>> kept = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        Object large = new byte[8_000_000];
                        written.add(new WeakReference<>(large));
                        ctx.flow().put(LARGE, large);
                        ctx.flow().remove(LARGE);
                        return ActionOutcome.ok();
                    })
                    .afterCommitWith("Keep",
                            (Doc doc, CommitContext<DocStatus, DocTransition> commit) -> kept.add(commit))
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachines.from(graph).state(Doc::status, Doc::setStatus).build().execute(new Doc("1", DRAFT), SUBMIT);
        assertEquals(1, kept.size());
        assertThrows(StaleContextException.class, () -> kept.get(0).flow().get(LARGE));
        assertTrue(collected(written.get(0)), "the call's flow is still reachable through the kept CommitContext");
    }
}
