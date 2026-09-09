package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DraftProblemsTest {

    static final class Publish implements TransitionAction<Doc, DocStatus> {
        @Override public ActionOutcome process(TransitionContext<Doc, DocStatus> ctx) { return ActionOutcome.ok(); }
    }

    static final class Approved implements TransitionGuard<Doc, DocStatus> {
        @Override public GuardResult test(GuardContext<Doc, DocStatus> ctx) { return GuardResult.pass(); }
    }

    /** REVIEW, PUBLISHED and ARCHIVED rows are valid; each test supplies the DRAFT row. */
    private static GraphBuilder<Doc, DocStatus> tail(RowBuilder<Doc, DocStatus> draftRow, String draftReason) {
        return draftRow.forbidOthers(draftReason)
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x");
    }

    private static GraphBuilder<Doc, DocStatus> head() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW);
    }

    private static InvalidGraphException failing(GraphBuilder<Doc, DocStatus> builder) {
        return assertThrows(InvalidGraphException.class, builder::build);
    }

    @Test
    void sg101ListsMissingCellsWithPasteableBlock() {
        InvalidGraphException error = assertThrows(InvalidGraphException.class, () -> head()
                .from(DRAFT).to(REVIEW).move().stay()
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build());
        assertEquals(List.of("SG101"), error.codes());
        String message = error.problems().get(0).message();
        assertTrue(message.contains("missing 2 of 16 cells"));
        assertTrue(message.contains(".from(DRAFT)"));
        assertTrue(message.contains(".to(PUBLISHED).forbid(\"TODO why not\")"));
        assertTrue(message.contains(".to(ARCHIVED).forbid(\"TODO why not\")"));
    }

    @Test
    void sg102DuplicateCellAndDuplicateForbidOthers() {
        InvalidGraphException duplicateCell = failing(tail(head().from(DRAFT).to(REVIEW).move().to(REVIEW).move().stay(), "x"));
        assertEquals(List.of("SG102"), duplicateCell.codes());
        InvalidGraphException duplicateRow = assertThrows(InvalidGraphException.class, () -> head()
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(DRAFT).forbidOthers("y")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build());
        assertEquals(List.of("SG102"), duplicateRow.codes());
    }

    @Test
    void sg103BlankReason() {
        assertEquals(List.of("SG103"), failing(tail(head().from(DRAFT).to(REVIEW).move().to(PUBLISHED).forbid(" ").stay(), "x")).codes());
        assertEquals(List.of("SG103"), failing(tail(head().from(DRAFT).to(REVIEW).move().stay(), "  ")).codes());
    }

    @Test
    void sg104MoveSelfLoop() {
        assertEquals(List.of("SG104"), failing(tail(head().from(DRAFT).to(REVIEW).move().to(DRAFT).move(), "x")).codes());
    }

    @Test
    void sg105SameGuardTwice() {
        Approved approved = new Approved();
        assertEquals(List.of("SG105"),
                failing(tail(head().from(DRAFT).to(REVIEW).move().guard(approved).guard(approved).stay(), "x")).codes());
    }

    @Test
    void sg107BlankExplicitName() {
        assertEquals(List.of("SG107"), failing(tail(head().from(DRAFT).to(REVIEW).move().guard(" ", new Approved()).stay(), "x")).codes());
    }

    @Test
    void sg201InitialMissingOrDoubled() {
        InvalidGraphException missing = assertThrows(InvalidGraphException.class, () -> StateGraph.define(DOCS)
                .terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build());
        assertEquals(List.of("SG201"), missing.codes());
        assertEquals(List.of("SG201"), failing(tail(head().initial(REVIEW).from(DRAFT).to(REVIEW).move().stay(), "x")).codes());
    }

    @Test
    void sg302ForbiddenFailsOnTypes() {
        assertEquals(List.of("SG302"), failing(tail(head()
                .from(DRAFT).to(REVIEW).action(new Publish()).failsOn(RuntimeException.class).stay(), "x")).codes());
        assertEquals(List.of("SG302"), failing(tail(head()
                .from(DRAFT).to(REVIEW).action(new Publish()).failsOn(StateMachineException.class).stay(), "x")).codes());
        assertEquals(List.of("SG302"), failing(tail(head()
                .from(DRAFT).to(REVIEW).action(new Publish()).failsOn(OutOfMemoryError.class).stay(), "x")).codes());
    }

    @Test
    void sg403TwoWatchesOnOneState() {
        InvalidGraphException error = assertThrows(InvalidGraphException.class, () -> head()
                .from(DRAFT).to(REVIEW).move().stay()
                    .watches(DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                        .when(WatchCondition.allIn(REVIEW)).goTo(REVIEW)
                    .watches(DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                        .when(WatchCondition.allIn(REVIEW)).goTo(REVIEW)
                    .forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build());
        assertEquals(List.of("SG403"), error.codes());
    }

    @Test
    void sg404WatchWithoutRules() {
        InvalidGraphException error = assertThrows(InvalidGraphException.class, () -> head()
                .from(DRAFT).to(REVIEW).move().stay()
                    .watches(DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                    .forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build());
        assertEquals(List.of("SG404"), error.codes());
    }

    @Test
    void problemsAreSortedByCodeThenCell() {
        InvalidGraphException error = assertThrows(InvalidGraphException.class, () -> head()
                .from(DRAFT).to(REVIEW).move().to(PUBLISHED).forbid(" ").to(DRAFT).move()
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build());
        assertEquals(List.of("SG101", "SG103", "SG104"), error.codes());
    }
}
