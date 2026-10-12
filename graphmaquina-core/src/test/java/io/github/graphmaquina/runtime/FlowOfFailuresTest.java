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
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.PoisonedUnitOfWorkException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Pins what the documentation says of the flow a failure carries, and of a write made once the commit began: a
 * poisoned call does not roll back a step whose failure its edge expects, whether the action returns or throws it,
 * unless a COLLECT collects that poison and rolls the whole element back; only the failure the call throws carries the
 * call's flow; and a commit callback that writes through a retained context writes into the flow the call hands out.
 */
class FlowOfFailuresTest {

    private static final FlowKey<String> NOTE = FlowKey.of("note", String.class);
    private static final FlowKey<String> CHILD_NOTE = FlowKey.of("child-note", String.class);
    private static final InstantSource EPOCH = InstantSource.fixed(Instant.EPOCH);

    /** The doc graph, with {@code submit} and {@code publish} decorating those edges. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> publish) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        graph = publish.apply(graph.transition(PUBLISH, REVIEW, PUBLISHED)).endTransition();
        return graph.transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    @Test
    void aPoisonedStepWhoseFailureItsEdgeExpectsKeepsItsWriteAndItsHop() {
        Doc root = new Doc("root", DRAFT);
        Doc refused = new Doc("refused", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "written by the failing step");
                            try {
                                ctx.cascadeAll(DOCS, List.of(refused), PUBLISH, CascadePolicy.COLLECT);
                            } catch (CascadeException poisoned) {
                                // the call is poisoned from here on
                            }
                            return ActionOutcome.failed("route it");
                        }).onFailure(DISCARD),
                UnaryOperator.identity());
        CascadeException thrown = assertThrows(CascadeException.class,
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).recordTrail(EPOCH).build()
                        .execute(root, SUBMIT));
        assertEquals(Optional.of("written by the failing step"), thrown.flow().get(NOTE));
        List<FlowEntry> trail = thrown.flow().trail();
        assertEquals(2, trail.size());
        assertEquals(SUBMIT, assertInstanceOf(FlowEntry.Hop.class, trail.get(0)).hop().transition());
        assertInstanceOf(FlowEntry.Failed.class, trail.get(1));
        assertEquals(DRAFT, root.status());
    }

    @Test
    void aPoisonedStepThatThrowsAFailureItsEdgeExpectsKeepsItsWriteAndItsHop() {
        Doc root = new Doc("root", DRAFT);
        Doc refused = new Doc("refused", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "written by the failing step");
                            try {
                                ctx.cascadeAll(DOCS, List.of(refused), PUBLISH, CascadePolicy.COLLECT);
                            } catch (CascadeException poisoned) {
                                // the call is poisoned from here on
                            }
                            throw new IllegalArgumentException("route it");
                        }).failsOn(IllegalArgumentException.class).onFailure(DISCARD),
                UnaryOperator.identity());
        CascadeException thrown = assertThrows(CascadeException.class,
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).recordTrail(EPOCH).build()
                        .execute(root, SUBMIT));
        assertEquals(Optional.of("written by the failing step"), thrown.flow().get(NOTE));
        List<FlowEntry> trail = thrown.flow().trail();
        assertEquals(2, trail.size());
        assertEquals(SUBMIT, assertInstanceOf(FlowEntry.Hop.class, trail.get(0)).hop().transition());
        assertInstanceOf(FlowEntry.Failed.class, trail.get(1));
        assertEquals(DRAFT, root.status());
    }

    /** The doc graph whose root collects {@code child}, and whose child returns a failure once {@code poison} ran. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> collecting(
            Doc root, Doc child, Consumer<TransitionContext<Doc, DocStatus, DocTransition>> poison) {
        return docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == root) {
                                ctx.flow().put(NOTE, "root");
                                ctx.cascadeAll(DOCS, List.of(child), SUBMIT, CascadePolicy.COLLECT);
                                return ActionOutcome.ok();
                            }
                            ctx.flow().put(CHILD_NOTE, "child");
                            try {
                                poison.accept(ctx);
                            } catch (TransitionAbortedException poisoned) {
                                // the call is poisoned from here on
                            }
                            return ActionOutcome.failed("route it");
                        }).onFailure(DISCARD),
                UnaryOperator.identity());
    }

    private static List<String> described(List<FlowEntry> trail) {
        List<String> described = new ArrayList<>();
        for (FlowEntry entry : trail) {
            described.add(entry instanceof FlowEntry.Hop hop ? "Hop " + ((Doc) hop.hop().entity()).id()
                    : entry.getClass().getSimpleName());
        }
        return described;
    }

    @Test
    void aCollectElementPoisonedByAFailureOfTheCallIsRolledBackWithItsWritesAndItsHop() {
        Doc root = new Doc("root", DRAFT);
        Doc child = new Doc("child", DRAFT);
        Doc grandchild = new Doc("grandchild", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = collecting(root, child,
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, grandchild, PUBLISH));
        CascadeException thrown = assertThrows(CascadeException.class,
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)
                        .recordTrail(EPOCH).build().execute(root, SUBMIT));
        assertEquals(Optional.of("root"), thrown.flow().get(NOTE));
        assertEquals(Optional.empty(), thrown.flow().get(CHILD_NOTE));
        assertEquals(List.of("Hop root", "Failed"), described(thrown.flow().trail()));
        assertEquals(List.of(DRAFT, DRAFT, DRAFT), List.of(root.status(), child.status(), grandchild.status()));
    }

    @Test
    void aCollectElementPoisonedByAFailureOfNoCallKeepsItsWritesAndItsHop() {
        Doc root = new Doc("root", DRAFT);
        Doc child = new Doc("child", DRAFT);
        Doc unreadable = new Doc("unreadable", REVIEW);
        IllegalStateException unread = new IllegalStateException("the state cannot be read");
        GraphDefinition<Doc, DocStatus, DocTransition> graph = collecting(root, child,
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, unreadable, PUBLISH));
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(graph).state((Doc doc) -> {
                    if (doc == unreadable) {
                        throw unread;
                    }
                    return doc.status();
                }, Doc::setStatus).identity(Doc::id).recordTrail(EPOCH).build().execute(root, SUBMIT));
        assertSame(unread, thrown.getCause());
        assertEquals(Optional.of("child"), thrown.flow().get(CHILD_NOTE));
        assertEquals(List.of("Hop root", "Hop child", "Failed"), described(thrown.flow().trail()));
        assertEquals(List.of(DRAFT, DRAFT, REVIEW), List.of(root.status(), child.status(), unreadable.status()));
    }

    @Test
    void onlyTheFailureTheCallThrowsCarriesTheCallsFlow() {
        Doc root = new Doc("root", DRAFT);
        Doc child = new Doc("child", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "parent");
                            ctx.cascadeAll(DOCS, List.of(child), PUBLISH, CascadePolicy.COLLECT);
                            return ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.action("Publish",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.failed("no route")));
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).identity(Doc::id).recordTrail(EPOCH)).build()) {
            CascadeException thrown = assertThrows(CascadeException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertSame(root, thrown.flow().entity().orElseThrow());
            assertEquals(Optional.of("parent"), thrown.flow().get(NOTE));
            UnhandledFailureException nested = assertInstanceOf(UnhandledFailureException.class,
                    thrown.errors().get(0));
            assertSame(FlowView.empty(), nested.flow());
        }
    }

    @Test
    void aPoisonedUnitOfWorkExceptionAContextThrewHoldsTheEmptyFlow() {
        Doc root = new Doc("root", DRAFT);
        Doc refused = new Doc("refused", DRAFT);
        List<PoisonedUnitOfWorkException> refusals = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "root");
                            try {
                                ctx.cascadeAll(DOCS, List.of(refused), PUBLISH, CascadePolicy.COLLECT);
                            } catch (CascadeException poisoned) {
                                // the call is poisoned from here on
                            }
                            try {
                                ctx.peer(DOCS);
                            } catch (PoisonedUnitOfWorkException refusal) {
                                refusals.add(refusal);
                            }
                            return ActionOutcome.ok();
                        }),
                UnaryOperator.identity());
        CascadeException thrown = assertThrows(CascadeException.class,
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).build().execute(root, SUBMIT));
        assertEquals(Optional.of("root"), thrown.flow().get(NOTE));
        assertEquals(1, refusals.size());
        assertSame(thrown, refusals.get(0).original());
        assertSame(FlowView.empty(), refusals.get(0).flow());
    }

    @Test
    void aCommitSetterThatWritesThroughARetainedContextWritesIntoTheCallsFlow() {
        Doc root = new Doc("root", DRAFT);
        List<TransitionContext<Doc, DocStatus, DocTransition>> retained = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            retained.add(ctx);
                            return ActionOutcome.ok();
                        }),
                UnaryOperator.identity());
        Outcome<Doc, DocStatus, DocTransition> outcome = StateMachines.from(graph)
                .state(Doc::status, (Doc doc, DocStatus state) -> {
                    retained.get(0).flow().put(NOTE, "written by the setter");
                    doc.setStatus(state);
                }).build().execute(root, SUBMIT);
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(Optional.of("written by the setter"), outcome.flow().get(NOTE));
    }
}
