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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A failed cascade poisons the transaction even when printing the failure or an entity in flight throws: the poison is
 * stored before anything reaches the action, so an action that catches what it receives cannot commit the root.
 * Printing that throws anything but an Error puts a marker in the abort's text; an Error first leaves a poison that
 * names the failure's class and each entity's machine alone, then propagates, unless the failure is itself an Error,
 * which reaches the action as itself with the Error of printing it suppressed.
 */
class UnprintableFailurePoisonTest {

    /** A failure whose message cannot be read. */
    static final class UnreadableMessage extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("the message cannot be read");
        }
    }

    /** A failure whose message read throws an Error. */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class FatalMessage extends RuntimeException {
        private final AssertionError fatal;

        @Override
        public String getMessage() {
            throw fatal;
        }
    }

    /** An Error whose message read throws {@link #printing}. */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class UnprintableError extends Error {
        private final AssertionError printing;

        @Override
        public String getMessage() {
            throw printing;
        }
    }

    /** An identity that compares by name and whose printing is decided by {@link #print}. */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class PrintedId {
        private final String name;
        private Runnable print = () -> { };

        @Override
        public boolean equals(Object other) {
            return other instanceof PrintedId that && that.name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            print.run();
            return name;
        }
    }

    /** The doc graph, with {@code submit} and {@code publish} decorating those edges. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> publish) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        graph = publish.apply(graph.transition(PUBLISH, REVIEW, PUBLISHED)).endTransition();
        return graph.transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition().build();
    }

    /** An action that runs {@code cascade} and keeps whatever it throws in {@code caught}, then returns ok. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> catching(
            Function<TransitionContext<Doc, DocStatus, DocTransition>, Object> cascade, List<Throwable> caught) {
        return (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    try {
                        cascade.apply(ctx);
                    } catch (RuntimeException | Error thrown) {
                        caught.add(thrown);
                    }
                    return ActionOutcome.ok();
                });
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph, Doc unreadable,
                                            RuntimeException failure) {
        return MachineRegistry.builder().add(StateMachines.from(graph).state((Doc doc) -> {
            if (doc == unreadable) {
                throw failure;
            }
            return doc.status();
        }, Doc::setStatus).identity(Doc::id)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cascade", "fail-fast", "collect"})
    void aFailureWhoseMessageCannotBeReadStillPoisons(String cascade) {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        UnreadableMessage failure = new UnreadableMessage();
        List<Throwable> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> switch (cascade) {
                    case "cascade" -> ctx.cascade(DOCS, peer, PUBLISH);
                    case "fail-fast" -> ctx.cascadeAll(DOCS, List.of(peer), PUBLISH, CascadePolicy.FAIL_FAST);
                    default -> ctx.cascadeAll(DOCS, List.of(peer), PUBLISH, CascadePolicy.COLLECT);
                }, caught),
                UnaryOperator.identity());
        try (MachineRegistry registry = registry(graph, peer, failure)) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertSame(failure, thrown.getCause());
            assertTrue(thrown.getMessage().contains(
                    "the cascade to Doc/DocStatus failed: <toString threw java.lang.IllegalStateException>"),
                    thrown.getMessage());
            assertEquals(List.of(thrown), caught);
        }
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), peer.status()));
    }

    @Test
    void aCollectWithAnUnreadableFailureCommitsNoneOfItsElements() {
        Doc root = new Doc("root", DRAFT);
        Doc good = new Doc("good", REVIEW);
        Doc unreadable = new Doc("unreadable", REVIEW);
        List<Throwable> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                        ctx.cascadeAll(DOCS, List.of(good, unreadable), PUBLISH, CascadePolicy.COLLECT), caught),
                UnaryOperator.identity());
        try (MachineRegistry registry = registry(graph, unreadable, new UnreadableMessage())) {
            assertThrows(CallFailedException.class, () -> registry.of(DOCS).execute(root, SUBMIT));
        }
        assertEquals(1, caught.size());
        assertEquals(List.of(DRAFT, REVIEW, REVIEW), List.of(root.status(), good.status(), unreadable.status()));
    }

    @Test
    void anErrorFromTheFailuresMessageLeavesAPoisonThatNamesItsClass() {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        AssertionError fatal = new AssertionError("the message read failed");
        FatalMessage failure = new FatalMessage(fatal);
        List<Throwable> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, peer, PUBLISH),
                        caught),
                UnaryOperator.identity());
        try (MachineRegistry registry = registry(graph, peer, failure)) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertSame(failure, thrown.getCause());
            String named = "the cascade to Doc/DocStatus failed: " + FatalMessage.class.getName();
            assertTrue(thrown.getMessage().contains(named), thrown.getMessage());
            assertEquals(StatesWritten.NONE, thrown.statesWritten());
        }
        assertEquals(List.of(fatal), caught);
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), peer.status()));
    }

    @Test
    void anErrorThatCannotBePrintedStillReachesTheActionAsItself() {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        AssertionError printing = new AssertionError("printing the failure failed");
        UnprintableError failure = new UnprintableError(printing);
        List<Throwable> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ctx.cascade(DOCS, peer, PUBLISH),
                        caught),
                UnaryOperator.identity());
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph).state((Doc doc) -> {
            if (doc == peer) {
                throw failure;
            }
            return doc.status();
        }, Doc::setStatus).identity(Doc::id)).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            // Comparisons by identity with fixed messages: printing the failure throws.
            assertTrue(thrown.getCause() == failure, "the poison carries the failure");
            String named = "the cascade to Doc/DocStatus failed: " + UnprintableError.class.getName();
            assertTrue(thrown.getMessage().contains(named), thrown.getMessage());
        }
        assertTrue(caught.size() == 1 && caught.get(0) == failure, "the action receives the Error as itself");
        assertTrue(failure.getSuppressed().length == 1 && failure.getSuppressed()[0] == printing,
                "the Error carries the Error of printing it as suppressed");
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), peer.status()));
    }

    @Test
    void anIdentityInFlightThatCannotBePrintedStillLetsTheFailedCascadePoison() {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        PrintedId rootId = new PrintedId("root");
        List<Throwable> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    rootId.print = () -> {
                        throw new IllegalStateException("the identity cannot be printed");
                    };
                    return ctx.cascade(DOCS, peer, PUBLISH);
                }, caught),
                UnaryOperator.identity());
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state((Doc doc) -> {
                    if (doc == peer) {
                        throw new IllegalArgumentException("the peer cannot be read");
                    }
                    return doc.status();
                }, Doc::setStatus).identity((Doc doc) -> doc == root ? rootId : doc.id())).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertEquals(List.of("Doc/DocStatus#<toString threw java.lang.IllegalStateException>"),
                    thrown.cascadePath());
            assertEquals(List.of(thrown), caught);
        }
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), peer.status()));
    }

    @Test
    void anErrorFromAnIdentityInFlightLeavesAPoisonThatNamesTheMachinesAlone() {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        PrintedId rootId = new PrintedId("root");
        AssertionError fatal = new AssertionError("the identity cannot be printed");
        List<Throwable> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    rootId.print = () -> {
                        throw fatal;
                    };
                    return ctx.cascade(DOCS, peer, PUBLISH);
                }, caught),
                UnaryOperator.identity());
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state((Doc doc) -> {
                    if (doc == peer) {
                        throw new IllegalStateException("the peer cannot be read");
                    }
                    return doc.status();
                }, Doc::setStatus).identity((Doc doc) -> doc == root ? rootId : doc.id())).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertEquals(List.of("Doc/DocStatus"), thrown.cascadePath());
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
        assertEquals(List.of(fatal), caught);
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), peer.status()));
    }

    @Test
    void anErrorWhileTheCollectAggregateIsDescribedLeavesTheAggregateAsThePoison() {
        Doc root = new Doc("root", DRAFT);
        Doc refused = new Doc("refused", REVIEW);
        PrintedId rootId = new PrintedId("root");
        AssertionError fatal = new AssertionError("the identity cannot be printed twice");
        List<Throwable> caught = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                        ctx.cascadeAll(DOCS, List.of(refused), PUBLISH, CascadePolicy.COLLECT), caught),
                (TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.guard("Refuse",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            int[] prints = {0};
                            rootId.print = () -> {
                                if (++prints[0] == 2) {
                                    throw fatal;
                                }
                            };
                            return GuardResult.reject("refused");
                        }));
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).identity((Doc doc) -> doc == root ? rootId : doc.id())).build()) {
            CascadeException thrown = assertThrows(CascadeException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertEquals(List.of("Doc/DocStatus"), thrown.cascadePath());
            assertEquals(1, thrown.errors().size());
        }
        assertEquals(List.of(fatal), caught);
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), refused.status()));
    }

    @Test
    void aTrailClockThatFailsUnreadablyOnAPeersHopStillPoisons() {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        List<String> ran = new ArrayList<>();
        List<Throwable> caught = new ArrayList<>();
        UnreadableMessage failure = new UnreadableMessage();
        int[] reads = {0};
        InstantSource clock = () -> {
            if (++reads[0] == 2) {
                throw failure;
            }
            return Instant.EPOCH;
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                catching((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ran.add("root");
                    return ctx.cascade(DOCS, peer, PUBLISH);
                }, caught),
                (TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.action("Publish",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ran.add("peer");
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).identity(Doc::id).recordTrail(clock)).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertSame(failure, thrown.getCause());
        }
        assertEquals(List.of("root"), ran);
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), peer.status()));
    }
}
