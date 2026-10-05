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

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnknownFacadeException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Facades;
import io.github.graphmaquina.fixtures.GatewayException;
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Probe;
import io.github.graphmaquina.fixtures.Sneaky;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A failure inside a cascade poisons the unit of work, and a poisoned unit of work never commits: not when the
 * parent action catches the failure, not through a failure route, and not when the failure is no engine exception.
 * Refusing a cascade's arguments and reading a peer stage nothing, so neither poisons.
 */
class PoisonTest {

    final Probe<Doc, DocStatus, DocTransition> submit = new Probe<>();
    final Probe<Doc, DocStatus, DocTransition> publish = new Probe<>();
    final Probe<Doc, DocStatus, DocTransition> discard = new Probe<>();
    final GuardProbe<Doc, DocStatus, DocTransition> inStock = new GuardProbe<>();

    /**
     * The root runs SUBMIT from DRAFT and cascades to peers in REVIEW, whose PUBLISH is guarded by {@code inStock}.
     * DISCARD is SUBMIT's failure route for a GatewayException; it runs the {@code discard} action when
     * {@code discardRuns}.
     */
    private StateMachine<Doc, DocStatus, DocTransition> machine(boolean discardRuns) {
        TransitionBuilder<Doc, DocStatus, DocTransition> discarding = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).action("Submit", submit)
                    .failsOn(GatewayException.class).onFailure(DISCARD).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action("Publish", publish).guard("InStock", inStock)
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED);
        if (discardRuns) {
            discarding = discarding.action("Discard", discard);
        }
        GraphDefinition<Doc, DocStatus, DocTransition> graph = discarding.endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    }

    /** The root swallows whatever {@code cascade} throws and returns ok, as if nothing had happened. */
    private void swallowing(AtomicReference<Throwable> caught,
                            Consumer<TransitionContext<Doc, DocStatus, DocTransition>> cascade) {
        submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            try {
                cascade.accept(ctx);
            } catch (Throwable thrown) {
                caught.set(thrown);
            }
            return ActionOutcome.ok();
        };
    }

    /** The peer's action throws an Error, which the engine never routes; the parent catches it and returns ok. */
    @Test
    void aNestedErrorPoisonsTheTransactionEvenWhenTheParentCatchesIt() {
        AssertionError broken = new AssertionError("invariant broken");
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw broken;
        };
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
        Doc doc = new Doc("1", DRAFT);
        Doc peer = new Doc("2", REVIEW);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        swallowing(caught, (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, peer, PUBLISH));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(broken, caught.get());
        assertSame(broken, error.getCause());
        assertEquals(DRAFT, doc.status());
        assertEquals(REVIEW, peer.status());
    }

    /** A checked exception from the peer's action is an abort, and an abort inside a cascade poisons. */
    @Test
    void aNestedCheckedExceptionPoisonsTheTransactionEvenWhenTheParentCatchesIt() {
        IOException shipping = new IOException("ship failed");
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw Sneaky.raise(shipping);
        };
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
        Doc doc = new Doc("1", DRAFT);
        Doc peer = new Doc("2", REVIEW);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        swallowing(caught, (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, peer, PUBLISH));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(caught.get(), error);
        assertSame(shipping, error.getCause());
        assertEquals(DRAFT, doc.status());
        assertEquals(REVIEW, peer.status());
    }

    /** Whatever the policy, an Error in one element poisons, and none of the elements that ran before it commits. */
    @Test
    void aNestedErrorInsideCascadeAllPoisonsTheTransaction() {
        AssertionError broken = new AssertionError("invariant broken");
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            if (ctx.entity().id().equals("p3")) {
                throw broken;
            }
            return ActionOutcome.ok();
        };
        List<String> ends = new ArrayList<>();
        for (CascadePolicy policy : CascadePolicy.values()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
            Doc doc = new Doc("1", DRAFT);
            List<Doc> peers = List.of(new Doc("p1", REVIEW), new Doc("p2", REVIEW), new Doc("p3", REVIEW));
            AtomicReference<Throwable> caught = new AtomicReference<>();
            swallowing(caught, (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                    ctx.cascadeAll(DOCS, peers, PUBLISH, policy));
            String end;
            try {
                end = machine.execute(doc, SUBMIT).getClass().getSimpleName();
            } catch (TransitionAbortedException error) {
                end = error.getCause() == broken ? "aborted by the Error" : "aborted by " + error.getCause();
            }
            ends.add(policy + ": " + end + ", parent caught " + (caught.get() == broken ? "the Error" : caught.get())
                    + ", " + doc.status() + ", " + peers.stream().map(Doc::status).toList());
        }
        assertEquals(List.of(
                "FAIL_FAST: aborted by the Error, parent caught the Error, DRAFT, [REVIEW, REVIEW, REVIEW]",
                "COLLECT: aborted by the Error, parent caught the Error, DRAFT, [REVIEW, REVIEW, REVIEW]"), ends);
    }

    /**
     * A checked exception from one element's action is an abort like any other: FAIL_FAST stops on it, COLLECT
     * aggregates it, and either way none of the elements commits.
     */
    @Test
    void aNestedCheckedExceptionInsideCascadeAllPoisonsTheTransaction() {
        IOException shipping = new IOException("ship failed");
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            if (ctx.entity().id().equals("p3")) {
                throw Sneaky.raise(shipping);
            }
            return ActionOutcome.ok();
        };
        List<String> ends = new ArrayList<>();
        for (CascadePolicy policy : CascadePolicy.values()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
            Doc doc = new Doc("1", DRAFT);
            List<Doc> peers = List.of(new Doc("p1", REVIEW), new Doc("p2", REVIEW), new Doc("p3", REVIEW));
            AtomicReference<Throwable> caught = new AtomicReference<>();
            swallowing(caught, (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                    ctx.cascadeAll(DOCS, peers, PUBLISH, policy));
            String end;
            try {
                end = machine.execute(doc, SUBMIT).getClass().getSimpleName();
            } catch (TransitionAbortedException error) {
                end = error.getClass().getSimpleName();
            }
            ends.add(policy + ": " + end + ", parent caught " + caught.get().getClass().getSimpleName() + ", "
                    + doc.status() + ", " + peers.stream().map(Doc::status).toList());
        }
        assertEquals(List.of(
                "FAIL_FAST: TransitionAbortedException, parent caught TransitionAbortedException, DRAFT,"
                        + " [REVIEW, REVIEW, REVIEW]",
                "COLLECT: CascadeException, parent caught CascadeException, DRAFT, [REVIEW, REVIEW, REVIEW]"), ends);
    }

    /**
     * The root catches the failed cascade, then throws an exception its failsOn lists. The failure route would commit
     * the poisoned unit of work, so the poison is thrown instead, and no recovery action runs.
     */
    @Test
    void aFailsOnExceptionAfterACaughtCascadeFailureThrowsThePoisonInsteadOfRecovering() {
        inStock.verdict = (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("out of stock");
        List<String> ends = new ArrayList<>();
        for (boolean discardRuns : List.of(false, true)) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(discardRuns);
            Doc doc = new Doc("1", DRAFT);
            Doc peer = new Doc("2", REVIEW);
            submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                try {
                    ctx.cascade(DOCS, peer, PUBLISH);
                } catch (TransitionAbortedException rejected) {
                    throw new GatewayException("declined");
                }
                return ActionOutcome.ok();
            };
            discard.calls.clear();
            String end;
            try {
                end = machine.execute(doc, SUBMIT).getClass().getSimpleName();
            } catch (TransitionAbortedException error) {
                end = error.getMessage().contains("rejected inside a cascade by guard InStock: out of stock")
                        ? "the poison" : error.getMessage();
            }
            ends.add("discard runs " + discardRuns + ": " + end + ", " + doc.status() + ", discard ran "
                    + discard.calls.size());
        }
        assertEquals(List.of("discard runs false: the poison, DRAFT, discard ran 0",
                "discard runs true: the poison, DRAFT, discard ran 0"), ends);
    }

    /** An unknown facade is the same wiring error as an unknown key: inside a cascade, both poison. */
    @Test
    void anUnknownFacadeInACascadePoisonsLikeAnUnknownKey() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
        Doc peer = new Doc("2", REVIEW);
        List<Consumer<TransitionContext<Doc, DocStatus, DocTransition>>> cascades = List.of(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(Facades.Docs.class, peer, PUBLISH),
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                        ctx.cascadeAll(Facades.Docs.class, List.of(peer), PUBLISH, CascadePolicy.FAIL_FAST));
        for (Consumer<TransitionContext<Doc, DocStatus, DocTransition>> cascade : cascades) {
            AtomicReference<Throwable> caught = new AtomicReference<>();
            swallowing(caught, cascade);
            Doc doc = new Doc("1", DRAFT);
            UnknownFacadeException error = assertThrows(UnknownFacadeException.class, () -> machine.execute(doc, SUBMIT));
            assertSame(caught.get(), error);
            assertEquals(DRAFT, doc.status());
            assertEquals(REVIEW, peer.status());
        }
    }

    /** Pins the other half of the policy: a peer read stages nothing, so an unknown facade there does not poison. */
    @Test
    void anUnknownFacadeInAPeerReadDoesNotPoison() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        swallowing(caught, (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.peer(Facades.Docs.class));
        Doc doc = new Doc("1", DRAFT);
        assertInstanceOf(Outcome.Applied.class, machine.execute(doc, SUBMIT));
        assertInstanceOf(UnknownFacadeException.class, caught.get());
        assertEquals(REVIEW, doc.status());
    }

    /**
     * An identifier from another machine's catalogue, which only code that bypasses generics can pass, is an argument
     * refusal: cascade and cascadeAll both refuse it before anything starts, so it stages nothing and poisons nothing.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aWrongCatalogueIdentifierIsRefusedWithoutPoisoning() {
        Doc peer = new Doc("2", REVIEW);
        Map<String, Consumer<TransitionContext<Doc, DocStatus, DocTransition>>> cascades = new LinkedHashMap<>();
        cascades.put("cascade", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                ((TransitionContext) ctx).cascade(DOCS, peer, ItemTransition.PACK));
        for (CascadePolicy policy : CascadePolicy.values()) {
            cascades.put("cascadeAll " + policy, (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                    ((TransitionContext) ctx).cascadeAll(DOCS, List.of(peer), ItemTransition.PACK, policy));
        }
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Map.Entry<String, Consumer<TransitionContext<Doc, DocStatus, DocTransition>>> cascade : cascades.entrySet()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
            AtomicReference<Throwable> caught = new AtomicReference<>();
            swallowing(caught, cascade.getValue());
            publish.calls.clear();
            Doc doc = new Doc("1", DRAFT);
            String end;
            try {
                end = machine.execute(doc, SUBMIT).getClass().getSimpleName();
            } catch (TransitionAbortedException error) {
                end = error.getClass().getSimpleName() + " (" + error.getMessage() + ")";
            }
            ends.add(cascade.getKey() + ": " + end + ", caught " + caught.get() + ", publish ran " + publish.calls.size()
                    + ", " + doc.status() + ", " + peer.status());
            expected.add(cascade.getKey() + ": Applied, caught java.lang.IllegalArgumentException: transition must"
                    + " belong to io.github.graphmaquina.fixtures.DocTransition: PACK, publish ran 0, REVIEW, REVIEW");
        }
        assertEquals(expected, ends);
    }

    /** A null element is refused like a null peer of cascade(...): up front, before any element runs or is staged. */
    @Test
    void cascadeAllRefusesANullElementBeforeAnyElementRuns() {
        List<String> ends = new ArrayList<>();
        for (CascadePolicy policy : CascadePolicy.values()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(false);
            Doc peer = new Doc("2", REVIEW);
            AtomicReference<Throwable> caught = new AtomicReference<>();
            swallowing(caught, (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                    ctx.cascadeAll(DOCS, Arrays.asList(peer, null), PUBLISH, policy));
            publish.calls.clear();
            Doc doc = new Doc("1", DRAFT);
            Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
            ends.add(policy + ": root " + outcome.getClass().getSimpleName() + ", caught " + caught.get()
                    + ", publish ran " + publish.calls.size() + ", doc " + doc.status() + ", peer " + peer.status());
        }
        assertEquals(List.of(
                "FAIL_FAST: root Applied, caught java.lang.NullPointerException: peers contains a null element,"
                        + " publish ran 0, doc REVIEW, peer REVIEW",
                "COLLECT: root Applied, caught java.lang.NullPointerException: peers contains a null element,"
                        + " publish ran 0, doc REVIEW, peer REVIEW"), ends);
    }
}
