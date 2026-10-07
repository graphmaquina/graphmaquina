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

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * A throwable that is neither an Exception nor an Error, thrown by user code after the actions ran: it gets the
 * treatment an Exception gets at every boundary, so the call ends in a failure of its own that carries it and its
 * observers receive exactly one end event.
 */
class RawThrowableTest {

    private static final Throwable RAW = new Throwable("raw");

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> RuntimeException sneaky(Throwable throwable) throws X {
        throw (X) throwable;
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return submit.apply(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                        .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                        .transition(SUBMIT, DRAFT, REVIEW))
                .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                            Function<Doc, DocStatus> getter, BiConsumer<Doc, DocStatus> setter,
                                            Function<Doc, Object> identity, MachineObserver observer) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(getter, setter).identity(identity)
                        .asyncTimeout(Duration.ofSeconds(5)))
                .observer(observer)
                .build();
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                            BiConsumer<Doc, DocStatus> setter, MachineObserver observer) {
        return registry(graph, Doc::status, setter, Doc::id, observer);
    }

    /** The call ended once, and what its observer received is the very instance the caller got. */
    private static void assertOneEnd(ObserverTest.Recorder recorder, Throwable thrown) {
        assertEquals(2, recorder.events.size(), recorder.events.toString());
        assertSame(thrown, recorder.delivered.get(1));
    }

    @Test
    void aSetterThatThrowsItEndsInCommitPartialWithItAsCause() {
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        try (MachineRegistry registry = registry(docs(UnaryOperator.identity()),
                (Doc doc, DocStatus status) -> {
                    throw sneaky(RAW);
                }, recorder)) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            assertEquals(CallPhase.COMMIT, error.phase());
            assertEquals(StatesWritten.PARTIAL, error.statesWritten());
            assertSame(RAW, error.getCause());
            assertOneEnd(recorder, error);
        }
    }

    @Test
    void aGetterThatThrowsItInThePreCheckEndsInCommitNone() {
        AtomicBoolean broken = new AtomicBoolean();
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            broken.set(true);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph, (Doc doc) -> {
            if (broken.get()) {
                throw sneaky(RAW);
            }
            return doc.status();
        }, Doc::setStatus, Doc::id, recorder)) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            assertEquals(CallPhase.COMMIT, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertSame(RAW, error.getCause());
            assertOneEnd(recorder, error);
        }
    }

    @Test
    void aHookThatThrowsItEndsInHookAll() {
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommit("Notify",
                        (Doc doc) -> {
                            throw sneaky(RAW);
                        }));
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            assertEquals(CallPhase.HOOK, error.phase());
            assertEquals(StatesWritten.ALL, error.statesWritten());
            assertSame(RAW, error.getCause());
            assertOneEnd(recorder, error);
        }
    }

    /** DRAFT moves to REVIEW; a watch on PUBLISHED lets the doc named {@code observer} react to {@code watched}. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> watching(Doc watched, Doc observer,
            Function<Doc, Optional<Doc>> owner,
            WatchCondition<Doc, Doc, DocStatus, DocTransition> condition) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(watched), owner::apply)
                    .when("rule", condition).execute(ARCHIVE)
                    .endWatch()
                .build();
    }

    @Test
    void anOwnerLookupThatThrowsItIsAnAbortedReactionCarryingIt() {
        Doc watched = new Doc("watched", DRAFT);
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = watching(watched, watched,
                (Doc moved) -> {
                    throw sneaky(RAW);
                },
                (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> true);
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class,
                    registry.of(DOCS).execute(watched, SUBMIT));
            Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
            UndeclaredThrowableException wrapper = assertInstanceOf(UndeclaredThrowableException.class,
                    aborted.cause());
            assertSame(RAW, wrapper.getCause());
            assertEquals(2, recorder.events.size(), recorder.events.toString());
        }
    }

    @Test
    void aReactionConditionThatThrowsItIsAnAbortedReactionWhoseResolveAbortCarriesIt() {
        Doc watched = new Doc("watched", DRAFT);
        Doc owner = new Doc("owner", PUBLISHED);
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = watching(watched, owner,
                (Doc moved) -> moved == watched ? Optional.of(owner) : Optional.<Doc>empty(),
                (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> {
                    throw sneaky(RAW);
                });
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class,
                    registry.of(DOCS).execute(watched, SUBMIT));
            Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
            TransitionAbortedException abort = assertInstanceOf(TransitionAbortedException.class, aborted.cause());
            assertEquals(CallPhase.RESOLVE, abort.phase());
            assertSame(RAW, abort.getCause());
            assertEquals(4, recorder.events.size(), recorder.events.toString());
            assertSame(abort, recorder.delivered.get(2));
        }
    }

    @Test
    void aDirectReevaluateWhoseConditionThrowsItEndsInAResolveAbortCarryingIt() {
        Doc doc = new Doc("d", PUBLISHED);
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = watching(doc, doc,
                (Doc moved) -> Optional.of(doc),
                (Doc d, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> {
                    throw sneaky(RAW);
                });
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).reevaluate(doc));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertSame(RAW, error.getCause());
            assertOneEnd(recorder, error);
        }
    }

    @Test
    void anObserverThatThrowsItIsContainedAndLoggedAndTheOutcomeIsUnchanged() {
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                throw sneaky(RAW);
            }
        };
        try (LibraryLog log = LibraryLog.capture();
                MachineRegistry registry = registry(docs(UnaryOperator.identity()), Doc::setStatus, observer)) {
            Doc doc = new Doc("1", DRAFT);
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(REVIEW, doc.status());
            List<LogRecord> records = log.records();
            assertEquals(1, records.size());
            assertSame(RAW, records.get(0).getThrown());
        }
    }

    @Test
    void aRootIdentityThatThrowsItEndsInAResolveAbortCarryingIt() {
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        try (MachineRegistry registry = registry(docs(UnaryOperator.identity()), Doc::status, Doc::setStatus,
                (Doc doc) -> {
                    throw sneaky(RAW);
                }, recorder)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertSame(RAW, error.getCause());
            assertOneEnd(recorder, error);
        }
    }

    @Test
    void aRootGetterThatThrowsItEndsInAResolveAbortCarryingIt() {
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        try (MachineRegistry registry = registry(docs(UnaryOperator.identity()), (Doc doc) -> {
            throw sneaky(RAW);
        }, Doc::setStatus, Doc::id, recorder)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertSame(RAW, error.getCause());
            assertOneEnd(recorder, error);
        }
    }
}
