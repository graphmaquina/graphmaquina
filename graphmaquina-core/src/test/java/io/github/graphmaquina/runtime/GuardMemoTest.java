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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MemoKey;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * {@code memo(key, supplier)} computes a value once per request and entity and hands the same one to every later guard
 * of that request that asks about that entity: the guards of one edge, the candidates of a choice and the candidate
 * checks of a choice check. Another entity of the request, a step of a cascade or the entity of a check a guard starts,
 * computes its own, and two calls compute twice. A null value aborts as a null verdict does, a supplier that throws is
 * the guard throwing, and the memo works only on the calling thread.
 */
class GuardMemoTest {

    private static final MemoKey<Integer> KYC = MemoKey.named("kyc");
    private static final MemoKey<String> OWNER = MemoKey.named("owner");

    /** A failure of the KYC service that a guard declares it propagates. */
    static final class KycUnavailable extends RuntimeException {
        KycUnavailable() {
            super("kyc is down");
        }
    }

    private final AtomicInteger computed = new AtomicInteger();

    private GuardResult kycPasses(GuardContext<Doc, DocStatus, DocTransition> ctx) {
        return ctx.memo(KYC, computed::incrementAndGet) > 0 ? GuardResult.pass() : GuardResult.reject("no kyc");
    }

    private GuardResult kycRejects(GuardContext<Doc, DocStatus, DocTransition> ctx) {
        ctx.memo(KYC, computed::incrementAndGet);
        return GuardResult.reject("the doc is held");
    }

    /** Passes when what {@link #OWNER} keeps for this guard's doc is that doc's id; each computation is counted. */
    private GuardResult readsItsOwnValue(GuardContext<Doc, DocStatus, DocTransition> ctx) {
        String kept = ctx.memo(OWNER, () -> {
            computed.incrementAndGet();
            return ctx.entity().id();
        });
        return kept.equals(ctx.entity().id()) ? GuardResult.pass() : GuardResult.reject("read the value of " + kept);
    }

    /** DRAFT submits and discards, REVIEW publishes, PUBLISHED archives; {@code edges} adds the edges under test. */
    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            UnaryOperator<GraphBuilder<Doc, DocStatus, DocTransition>> edges) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = edges.apply(StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition())
                .build();
        return DocMachines.registry(graph).of(DOCS);
    }

    /** SUBMIT guarded by {@code submit}, DISCARD by {@code discard}. */
    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            TransitionGuard<Doc, DocStatus, DocTransition> submit,
            TransitionGuard<Doc, DocStatus, DocTransition> discard) {
        return machine((GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                .transition(SUBMIT, DRAFT, REVIEW).guard("submit", submit).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).guard("discard", discard).endTransition());
    }

    /** Many keys kept for one entity: each computes once, and a later guard reads the very value of each. */
    @Test
    void manyKeysOfOneEntityEachComputeOnce() {
        List<MemoKey<Object>> keys = new ArrayList<>();
        for (int key = 0; key < 40; key++) {
            keys.add(MemoKey.named("key" + key));
        }
        List<Object> first = new ArrayList<>();
        List<Object> second = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SUBMIT, DRAFT, REVIEW).guard("first", keeping(keys, first))
                        .guard("second", keeping(keys, second)).endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).endTransition());

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("1", DRAFT), SUBMIT).status());
        assertEquals(40, computed.get());
        assertEquals(40, second.size());
        for (int key = 0; key < 40; key++) {
            assertSame(first.get(key), second.get(key));
        }
    }

    /** A guard that adds to {@code seen} what each of {@code keys} keeps, computing a new object when it keeps none. */
    private TransitionGuard<Doc, DocStatus, DocTransition> keeping(List<MemoKey<Object>> keys, List<Object> seen) {
        return (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            for (MemoKey<Object> key : keys) {
                seen.add(ctx.memo(key, () -> {
                    computed.incrementAndGet();
                    return new Object();
                }));
            }
            return GuardResult.pass();
        };
    }

    @Test
    void twoGuardsOfOneEdgeComputeOnce() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SUBMIT, DRAFT, REVIEW).guard("first", this::kycPasses)
                        .guard("second", this::kycPasses).endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).endTransition());

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("1", DRAFT), SUBMIT).status());
        assertEquals(1, computed.get());
    }

    @Test
    void candidatesOfAChoiceComputeOnce() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(this::kycPasses, this::kycRejects);

        assertEquals(SUBMIT, machine.choose(new Doc("1", DRAFT), List.of(DISCARD, SUBMIT))
                .requestedTransition().orElseThrow());
        assertEquals(1, computed.get());
    }

    @Test
    void candidateChecksOfAChoiceCheckComputeOnce() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(this::kycPasses, this::kycRejects);

        assertEquals(SUBMIT, machine.chooseEligibility(new Doc("1", DRAFT), List.of(DISCARD, SUBMIT))
                .chosen().orElseThrow());
        assertEquals(1, computed.get());
    }

    @Test
    void aNestedCheckOfAnotherEntityKeepsItsOwnValue() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc other = new Doc("9", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> readsItsOwnValue(ctx) instanceof GuardResult.Pass
                        && self.get().eligibility(other, DISCARD).isEligible()
                        ? GuardResult.pass() : GuardResult.reject("the other doc is held"),
                this::readsItsOwnValue);
        self.set(machine);

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("1", DRAFT), SUBMIT).status());
        assertEquals(2, computed.get());
    }

    @Test
    void aCascadeOverACollectionKeepsAValuePerEntity() {
        Doc two = new Doc("2", DRAFT);
        Doc three = new Doc("3", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SUBMIT, DRAFT, REVIEW).guard("own", this::readsItsOwnValue)
                        .action("discardOthers", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascadeAll(DOCS, List.of(two, three), DISCARD, CascadePolicy.FAIL_FAST);
                            return ActionOutcome.ok();
                        }).endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).guard("own", this::readsItsOwnValue).endTransition());

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("1", DRAFT), SUBMIT).status());
        assertEquals(List.of(ARCHIVED, ARCHIVED), List.of(two.status(), three.status()));
        assertEquals(3, computed.get());
    }

    @Test
    void twoCallsComputeTwice() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(this::kycPasses, this::kycPasses);

        machine.execute(new Doc("1", DRAFT), SUBMIT);
        machine.execute(new Doc("2", DRAFT), SUBMIT);

        assertEquals(2, computed.get());
    }

    @Test
    void aNullValueAbortsAsANullVerdict() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> ctx.memo(KYC, () -> null) > 0
                        ? GuardResult.pass() : GuardResult.reject("no kyc"),
                this::kycPasses);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertTrue(abort.guardFailure().isEmpty());
        assertTrue(abort.getMessage().contains("the guard submit kept null for the memo key kyc"), abort.getMessage());
    }

    @Test
    void aThrowingSupplierIsTheGuardThrowing() {
        KycUnavailable down = new KycUnavailable();
        TransitionGuard<Doc, DocStatus, DocTransition> failing = (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                ctx.memo(KYC, () -> {
                    throw down;
                }) > 0 ? GuardResult.pass() : GuardResult.reject("no kyc");
        StateMachine<Doc, DocStatus, DocTransition> wrapped = machine(failing, this::kycPasses);
        StateMachine<Doc, DocStatus, DocTransition> propagating = machine(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SUBMIT, DRAFT, REVIEW).guard("submit", failing, List.of(KycUnavailable.class))
                        .endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).endTransition());

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> wrapped.execute(new Doc("1", DRAFT), SUBMIT));

        assertSame(down, abort.guardFailure().orElseThrow());
        assertSame(down, assertThrows(KycUnavailable.class, () -> propagating.execute(new Doc("2", DRAFT), SUBMIT)));
    }

    @Test
    void memoOffTheCallingThreadThrowsWrongThreadException() {
        AtomicReference<Throwable> elsewhere = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    Thread other = Thread.ofPlatform().start(() -> {
                        try {
                            ctx.memo(KYC, computed::incrementAndGet);
                        } catch (WrongThreadException refused) {
                            elsewhere.set(refused);
                        }
                    });
                    joined(other);
                    return GuardResult.pass();
                },
                this::kycPasses);

        assertEquals(OutcomeStatus.APPLIED, machine.execute(new Doc("1", DRAFT), SUBMIT).status());
        assertInstanceOf(WrongThreadException.class, elsewhere.get());
        assertEquals(0, computed.get());
    }

    private static void joined(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
