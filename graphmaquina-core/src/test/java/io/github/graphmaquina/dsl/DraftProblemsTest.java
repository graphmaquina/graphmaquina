package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * What {@code build()} reports for the declarations of one graph, one code at a time: duplicates (SG102), blank
 * reasons (SG103), guards declared twice and names that are missing or blank (SG105 to SG107), the initial state
 * (SG201), forbidden failsOn types (SG302), watches (SG403, SG404), edge declarations that need an action, that are
 * repeated or that are never closed (SG507), non-positive timeouts (SG511), and the order the problems come in.
 */
class DraftProblemsTest {

    /** A synchronous action that always succeeds. */
    static final class Publish implements TransitionAction<Doc, DocStatus, DocTransition> {
        @Override public ActionOutcome process(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
            return ActionOutcome.ok();
        }
    }

    /** A guard that always passes. */
    static final class Approved implements TransitionGuard<Doc, DocStatus, DocTransition> {
        @Override public GuardResult test(GuardContext<Doc, DocStatus, DocTransition> ctx) { return GuardResult.pass(); }
    }

    /** The rest of a valid machine, so each test only supplies the declaration under scrutiny. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> tail(GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return builder
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
    }

    private static GraphBuilder<Doc, DocStatus, DocTransition> head() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published or sent back")
                .statePolicy(PUBLISHED, "published only archives");
    }

    private static InvalidGraphException failing(GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return assertThrows(InvalidGraphException.class, builder::build);
    }

    @Test
    void sg102DuplicateKeyAndDuplicatePolicyOrRejection() {
        InvalidGraphException duplicateKey = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SUBMIT, DRAFT, PUBLISHED).endTransition()));
        assertEquals(List.of("SG102"), duplicateKey.codes());
        assertTrue(duplicateKey.problems().get(0).message().contains("duplicate transition DRAFT/SUBMIT"));

        InvalidGraphException duplicatePolicy = failing(tail(head().statePolicy(DRAFT, "again")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()));
        assertEquals(List.of("SG102"), duplicatePolicy.codes());

        InvalidGraphException duplicateRejection = failing(tail(head()
                .reject(DRAFT, PUBLISH, "not yet").reject(DRAFT, PUBLISH, "still not")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()));
        assertEquals(List.of("SG102"), duplicateRejection.codes());
    }

    @Test
    void sg103BlankReason() {
        assertEquals(List.of("SG103"), failing(tail(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .allowsCycle(REVIEW)
                .statePolicy(DRAFT, " ")
                .statePolicy(REVIEW, "in review it is published or sent back")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition())).codes());

        assertEquals(List.of("SG103"), failing(tail(head().reject(DRAFT, PUBLISH, "  ")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition())).codes());
    }

    @Test
    void sg105SameGuardTwice() {
        Approved approved = new Approved();
        assertEquals(List.of("SG105"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).guard(approved).guard(approved).endTransition())).codes());
    }

    @Test
    void sg106ALambdaWithoutAName() {
        assertEquals(List.of("SG106"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                    .endTransition())).codes());
    }

    @Test
    void sg107BlankExplicitName() {
        assertEquals(List.of("SG107"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).guard(" ", new Approved()).endTransition())).codes());
    }

    @Test
    void sg201InitialMissingOrDoubled() {
        InvalidGraphException missing = failing(tail(StateGraph.define(DOCS).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published or sent back")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()));
        assertEquals(List.of("SG201"), missing.codes());
        assertEquals(List.of("SG201"), failing(tail(head().initial(REVIEW)
                .transition(SUBMIT, DRAFT, REVIEW).endTransition())).codes());
    }

    @Test
    void sg302ForbiddenFailsOnTypes() {
        for (Class<? extends Throwable> type : List.of(RuntimeException.class, StateMachineException.class,
                OutOfMemoryError.class)) {
            assertEquals(List.of("SG302"), failing(tail(head()
                    .transition(SUBMIT, DRAFT, REVIEW).action(new Publish()).failsOn(type).endTransition())).codes(),
                    type.getSimpleName());
        }
    }

    /** A cancellation all the same: refused like its parent. */
    static final class Cancelled extends CancellationException { }

    /** Not the wrapper itself, so never unwrapped: the engine matches it like any foreign exception. */
    static final class Completed extends CompletionException { }

    /** Not the wrapper itself, so never unwrapped: the engine matches it like any foreign exception. */
    static final class Executed extends ExecutionException { }

    /**
     * The engine never routes a cancellation through failsOn, and it unwraps CompletionException and
     * ExecutionException before matching, so declaring one of them would leave the recovery route dead.
     */
    @Test
    void sg302RefusesCancellationAndTheWrappersUnwrappedBeforeMatching() {
        InvalidGraphException cancellation = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).action(new Publish()).failsOn(CancellationException.class)
                .endTransition()));
        assertEquals(List.of("SG302"), cancellation.codes());
        assertEquals("DRAFT -SUBMIT-> REVIEW: failsOn does not allow CancellationException; only throwable types"
                + " foreign to the library, and never Throwable, Exception, RuntimeException, an Error or a"
                + " CancellationException, which are never domain failures, nor CompletionException or"
                + " ExecutionException, which are unwrapped before matching: declare the type of their cause",
                cancellation.problems().get(0).message());
        for (Class<? extends Throwable> type : List.of(Cancelled.class, CompletionException.class,
                ExecutionException.class)) {
            assertEquals(List.of("SG302"), failing(tail(head()
                    .transition(SUBMIT, DRAFT, REVIEW).action(new Publish()).failsOn(type).endTransition())).codes(),
                    type.getSimpleName());
        }
    }

    /** Only the wrappers themselves are unwrapped: their subclasses, and IllegalStateException, stay matchable. */
    @Test
    void failsOnStillAcceptsTheWrappersSubclassesAndIllegalStateException() {
        assertEquals(List.of(IllegalStateException.class, Completed.class, Executed.class), tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).action(new Publish()).failsOn(IllegalStateException.class)
                .failsOn(Completed.class).failsOn(Executed.class).endTransition())
                .build().findTransition(DRAFT, SUBMIT).orElseThrow().failsOn());
    }

    @Test
    void sg403TwoWatchesOnOneState() {
        InvalidGraphException error = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .watch(DRAFT, DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                    .when(WatchCondition.allIn(REVIEW)).execute(SUBMIT).endWatch()
                .watch(DRAFT, DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                    .when(WatchCondition.allIn(REVIEW)).execute(SUBMIT).endWatch()));
        assertEquals(List.of("SG403"), error.codes());
    }

    @Test
    void sg404WatchWithoutRules() {
        InvalidGraphException error = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .watch(DRAFT, DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty()).endWatch()));
        assertEquals(List.of("SG404"), error.codes());
    }

    /** A valid machine whose DRAFT can leave through SUBMIT and RETOUCH, ready for a watch on DRAFT. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> watchable() {
        return tail(head().transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, DRAFT, ARCHIVED).endTransition());
    }

    private static WatchBuilder<Doc, DocStatus, DocTransition, Doc, DocStatus, DocTransition> watchOnDraft(
            GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return builder.watch(DRAFT, DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty());
    }

    /** A condition left without its execute(...) is a rule the author started: build() names it. */
    @Test
    void sg404AWhenLeftWithoutExecute() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = watchable();
        WatchBuilder<Doc, DocStatus, DocTransition, Doc, DocStatus, DocTransition> watch = watchOnDraft(builder);
        watch.when(WatchCondition.allIn(REVIEW)).execute(SUBMIT);
        watch.when(WatchCondition.anyIn(ARCHIVED));
        watch.endWatch();
        InvalidGraphException error = failing(builder);
        assertEquals(List.of("SG404"), error.codes());
        assertEquals("the watch of DRAFT declares when(anyIn ARCHIVED) without execute(...)",
                error.problems().get(0).message());
    }

    @Test
    void sg404ASecondWhenBeforeTheFirstOneExecutes() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = watchable();
        WatchBuilder<Doc, DocStatus, DocTransition, Doc, DocStatus, DocTransition> watch = watchOnDraft(builder);
        watch.when(WatchCondition.allIn(REVIEW));
        watch.when(WatchCondition.anyIn(ARCHIVED)).execute(RETOUCH);
        watch.endWatch();
        InvalidGraphException error = failing(builder);
        assertEquals(List.of("SG404"), error.codes());
        assertEquals("the watch of DRAFT declares when(allIn REVIEW) without execute(...)",
                error.problems().get(0).message());
    }

    @Test
    void sg404AnExecuteWithoutAPendingWhen() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = watchable();
        WatchRuleBuilder<Doc, DocStatus, DocTransition, Doc, DocStatus, DocTransition> rule =
                watchOnDraft(builder).when(WatchCondition.allIn(REVIEW));
        rule.execute(SUBMIT);
        rule.execute(RETOUCH).endWatch();
        InvalidGraphException error = failing(builder);
        assertEquals(List.of("SG404"), error.codes());
        assertEquals("the watch of DRAFT declares execute(RETOUCH) without when(...)",
                error.problems().get(0).message());
    }

    @Test
    void sg507ElementsThatNeedAnActionAndSg511ADurationThatIsNotPositive() {
        assertEquals(List.of("SG507"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).onFailure(RETOUCH).endTransition())).codes());
        assertEquals(List.of("SG507"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).timeout(Duration.ofSeconds(1)).endTransition())).codes());
        assertEquals(List.of("SG507"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).action(new Publish()).timeout(Duration.ofSeconds(1))
                .endTransition())).codes());
        assertEquals(List.of("SG511"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW)
                    .asyncAction("slow", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                            java.util.concurrent.CompletableFuture.completedFuture(ActionOutcome.ok()))
                    .timeout(Duration.ZERO).endTransition())).codes());
    }

    /**
     * An edge runs one action. A second one is reported rather than swapped in, so no edge can run one action
     * under the name another one was given.
     */
    @Test
    void sg507TwoActionsOnOneEdge() {
        InvalidGraphException sync = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).action("charge", new Publish()).action(new Publish())
                .endTransition()));
        assertEquals(List.of("SG507"), sync.codes());
        assertEquals("DRAFT -SUBMIT-> REVIEW declares 2 actions, but an edge runs at most one",
                sync.problems().get(0).message());

        assertEquals(List.of("SG507"), failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).asyncAction("slow", slow()).asyncAction("slower", slow())
                .endTransition())).codes());

        InvalidGraphException mixed = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).action(new Publish()).asyncAction("slow", slow()).endTransition()));
        assertEquals(List.of("SG507"), mixed.codes());
        assertEquals("DRAFT -SUBMIT-> REVIEW declares both a synchronous and an asynchronous action",
                mixed.problems().get(0).message());
    }

    @Test
    void sg507TwoOnFailureRoutes() {
        InvalidGraphException routes = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).action(new Publish()).onFailure(RETOUCH).onFailure(DISCARD)
                .endTransition()
                .transition(RETOUCH, DRAFT, ARCHIVED).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()));
        assertEquals(List.of("SG507"), routes.codes());
        assertEquals("DRAFT -SUBMIT-> REVIEW declares 2 onFailure routes, but an edge has at most one",
                routes.problems().get(0).message());
    }

    @Test
    void sg507TwoTimeouts() {
        InvalidGraphException timeouts = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).asyncAction("slow", slow())
                .timeout(Duration.ofSeconds(1)).timeout(Duration.ofSeconds(9)).endTransition()));
        assertEquals(List.of("SG507"), timeouts.codes());
        assertEquals("DRAFT -SUBMIT-> REVIEW declares 2 timeouts, but an edge has at most one",
                timeouts.problems().get(0).message());
    }

    private static AsyncTransitionAction<Doc, DocStatus, DocTransition> slow() {
        return (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                CompletableFuture.completedFuture(ActionOutcome.ok());
    }

    /** A draft never closed is an edge the author declared: build() names it instead of dropping it. */
    @Test
    void sg507ADraftNeverClosed() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder =
                tail(head().transition(SUBMIT, DRAFT, REVIEW).endTransition());
        builder.transition(RETOUCH, DRAFT, ARCHIVED).action(new Publish());
        InvalidGraphException alongside = failing(builder);
        assertEquals(List.of("SG507"), alongside.codes());
        assertEquals("the graph Doc/DocStatus has 1 problem(s):\n"
                + "  [SG507] DRAFT -RETOUCH-> ARCHIVED was never closed with endTransition()", alongside.getMessage());

        // The only edge out of DRAFT left open: its absence must not surface as SG202/SG203 about the shape.
        GraphBuilder<Doc, DocStatus, DocTransition> only = head();
        only.transition(SUBMIT, DRAFT, REVIEW);
        assertEquals(List.of("SG507"), failing(tail(only)).codes());
    }

    @Test
    void problemsAreSortedByCodeThenByOriginAndIdentifier() {
        Approved approved = new Approved();
        InvalidGraphException error = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).guard(" ", new Approved()).endTransition()
                .transition(RETOUCH, DRAFT, ARCHIVED).guard(approved).guard(approved).endTransition()));
        assertEquals(List.of("SG105", "SG107"), error.codes());
    }
}
