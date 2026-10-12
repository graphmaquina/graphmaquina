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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * A guard's or an action's context may be kept after its call, and so may the peer views and the flow it handed out:
 * all of them are stale from then on, and keeping any of them must not keep the call reachable, its unit of work and
 * every value its steps put in the flow. The outcome, which holds the flow frozen, is dropped, so only what the test
 * keeps could hold the call. Checked on a JVM that collects on request.
 */
class ContextRetentionTest {

    private static final FlowKey<Object> LARGE = FlowKey.of("large", Object.class);

    /** What a call left the test: what its guard and its action handed out, and a weak reference to its large value. */
    private record Kept(List<Object> kept, WeakReference<Object> large) { }

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

    /** Skips the test on a JVM whose System.gc() does not collect an unreachable value. */
    private static void assumeCollectingJvm() throws InterruptedException {
        WeakReference<Object> control = new WeakReference<>(new byte[8_000_000]);
        assumeTrue(collected(control), "this JVM does not collect an unreachable value on System.gc()");
    }

    /**
     * Runs one SUBMIT whose guard keeps what {@code fromGuard} returns and whose action keeps what {@code fromAction}
     * returns, then puts a large value in the call's flow; the outcome is dropped.
     */
    private static Kept submitKeeping(Function<GuardContext<Doc, DocStatus, DocTransition>, Object> fromGuard,
                                      Function<TransitionContext<Doc, DocStatus, DocTransition>, Object> fromAction) {
        List<Object> kept = new ArrayList<>();
        List<WeakReference<Object>> written = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .guard("Keep", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                        kept.add(fromGuard.apply(ctx));
                        return GuardResult.pass();
                    })
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        kept.add(fromAction.apply(ctx));
                        Object large = new byte[8_000_000];
                        written.add(new WeakReference<>(large));
                        ctx.flow().put(LARGE, large);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachines.from(graph).state(Doc::status, Doc::setStatus).build().execute(new Doc("1", DRAFT), SUBMIT);
        return new Kept(kept, written.get(0));
    }

    @Test
    void anActionContextKeptAfterItsCallLetsTheCallsValuesBeCollected() throws InterruptedException {
        assumeCollectingJvm();
        Kept call = submitKeeping((GuardContext<Doc, DocStatus, DocTransition> ctx) -> "nothing",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx);
        @SuppressWarnings("unchecked")
        TransitionContext<Doc, DocStatus, DocTransition> context =
                (TransitionContext<Doc, DocStatus, DocTransition>) call.kept().get(1);
        assertThrows(StaleContextException.class, context::flow);
        assertTrue(collected(call.large()), "the call's flow is still reachable through the kept TransitionContext");
    }

    @Test
    void aFlowKeptAfterItsCallLetsTheCallsValuesBeCollected() throws InterruptedException {
        assumeCollectingJvm();
        Kept call = submitKeeping((GuardContext<Doc, DocStatus, DocTransition> ctx) -> "nothing",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.flow());
        FlowContext flow = (FlowContext) call.kept().get(1);
        assertThrows(StaleContextException.class, () -> flow.get(LARGE));
        assertTrue(collected(call.large()), "the call's flow is still reachable through the kept FlowContext");
    }

    @Test
    void aPeerViewKeptAfterItsCallLetsTheCallsValuesBeCollected() throws InterruptedException {
        assumeCollectingJvm();
        Kept call = submitKeeping((GuardContext<Doc, DocStatus, DocTransition> ctx) -> "nothing",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.peer(DOCS));
        @SuppressWarnings("unchecked")
        PeerView<Doc, DocStatus, DocTransition> peers = (PeerView<Doc, DocStatus, DocTransition>) call.kept().get(1);
        assertThrows(StaleContextException.class, () -> peers.stateOf(new Doc("2", DRAFT)));
        assertTrue(collected(call.large()), "the call's flow is still reachable through the kept PeerView");
    }

    @Test
    void aGuardContextAndItsViewsKeptAfterTheirCallLetTheCallsValuesBeCollected() throws InterruptedException {
        assumeCollectingJvm();
        Kept call = submitKeeping(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> List.of(ctx, ctx.flow(), ctx.peer(DOCS)),
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> "nothing");
        List<?> fromGuard = (List<?>) call.kept().get(0);
        @SuppressWarnings("unchecked")
        GuardContext<Doc, DocStatus, DocTransition> context =
                (GuardContext<Doc, DocStatus, DocTransition>) fromGuard.get(0);
        FlowView flow = (FlowView) fromGuard.get(1);
        @SuppressWarnings("unchecked")
        PeerView<Doc, DocStatus, DocTransition> peers = (PeerView<Doc, DocStatus, DocTransition>) fromGuard.get(2);
        assertThrows(StaleContextException.class, context::flow);
        assertThrows(StaleContextException.class, () -> flow.get(LARGE));
        assertThrows(StaleContextException.class, () -> peers.stateOf(new Doc("2", DRAFT)));
        assertTrue(collected(call.large()), "the call's flow is still reachable through the kept GuardContext");
    }
}
