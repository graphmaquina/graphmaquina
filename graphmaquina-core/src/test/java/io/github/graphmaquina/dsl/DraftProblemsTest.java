package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
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
import org.junit.jupiter.api.Test;

/** Problems a single draft carries: names, duplicates, forbidden failsOn types, watches and reasons. */
class DraftProblemsTest {

    static final class Publish implements TransitionAction<Doc, DocStatus, DocTransition> {
        @Override public ActionOutcome process(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
            return ActionOutcome.ok();
        }
    }

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

    @Test
    void problemsAreSortedByCodeThenByOriginAndIdentifier() {
        Approved approved = new Approved();
        InvalidGraphException error = failing(tail(head()
                .transition(SUBMIT, DRAFT, REVIEW).guard(" ", new Approved()).endTransition()
                .transition(RETOUCH, DRAFT, ARCHIVED).guard(approved).guard(approved).endTransition()));
        assertEquals(List.of("SG105", "SG107"), error.codes());
    }
}
