package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * automaticFailure(), automaticAbort() and automaticBlock() of outcomes built by hand: each answers the first matching
 * automatic step in the order the steps ran, the reactions of a commit first, each searched through its outcome, then
 * its automatic steps, each looked at itself and then through its outcome. A watch reaction's own result never
 * counts.
 */
class AutomaticSearchTest {

    private static final MachineKey<String, DayOfWeek, Month> KEY =
            MachineKey.of(String.class, DayOfWeek.class, Month.class);

    /** An applied outcome of {@code entity} with these reactions and automatic steps. */
    private static Outcome.Applied<String, DayOfWeek, Month> applied(String entity, List<Reaction> reactions,
                                                                    List<AutomaticStep> steps) {
        return new Outcome.Applied<>(entity, DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, List.of(), reactions,
                List.of(), FlowView.empty(), steps);
    }

    /** A failed outcome of {@code entity}, routed to WEDNESDAY, with these automatic steps. */
    private static Outcome.Failed<String, DayOfWeek, Month> failed(String entity, List<AutomaticStep> steps) {
        return new Outcome.Failed<>(entity, DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, Month.FEBRUARY,
                DayOfWeek.WEDNESDAY, "declined", Optional.empty(), List.of(), List.of(), Optional.empty(), List.of(),
                FlowView.empty(), steps);
    }

    /** An ignored outcome of {@code entity} for {@code reason}, with a rejection when the reason needs one. */
    private static Outcome.Ignored<String, DayOfWeek, Month> ignored(String entity, IgnoreReason reason) {
        List<Eligibility.Ineligible<String, DayOfWeek, Month>> rejections =
                reason == IgnoreReason.NO_CANDIDATE_ADMITTED
                        ? List.of(new Eligibility.Ineligible<>(entity, DayOfWeek.MONDAY, Month.MARCH, "gate closed",
                                RejectionKind.GUARD, Optional.of("gate")))
                        : List.of();
        return new Outcome.Ignored<>(entity, DayOfWeek.MONDAY, reason, rejections);
    }

    private static AutomaticStep.Done done(Outcome<String, DayOfWeek, Month> outcome) {
        return new AutomaticStep.Done(KEY, outcome.entity(), outcome);
    }

    private static AutomaticStep.Aborted aborted(String entity) {
        return new AutomaticStep.Aborted(KEY, entity, new IllegalStateException(entity + " aborted"));
    }

    private static Reaction.Done reacted(Outcome<String, DayOfWeek, Month> outcome) {
        return new Reaction.Done(KEY, outcome.entity(), outcome);
    }

    /** Without automatic steps anywhere, a reaction's outcome included, the three answer empty. */
    @Test
    void emptyWithoutSteps() {
        Outcome<String, DayOfWeek, Month> outcome = applied("root",
                List.of(reacted(applied("owner", List.of(), List.of()))), List.of());
        assertEquals(Optional.empty(), outcome.automaticFailure());
        assertEquals(Optional.empty(), outcome.automaticAbort());
        assertEquals(Optional.empty(), outcome.automaticBlock());
    }

    /**
     * A step nested in a reaction ran before the commit's own steps, so it comes first; a step comes before the
     * steps nested in its own outcome.
     */
    @Test
    void firstInExecutionOrder() {
        Outcome.Failed<String, DayOfWeek, Month> inReaction = failed("in-reaction", List.of());
        Outcome.Failed<String, DayOfWeek, Month> nested = failed("nested", List.of());
        Outcome.Failed<String, DayOfWeek, Month> outer = failed("outer", List.of(done(nested)));
        Outcome<String, DayOfWeek, Month> withReaction = applied("root",
                List.of(reacted(applied("owner", List.of(), List.of(done(inReaction))))), List.of(done(outer)));
        assertSame(inReaction, withReaction.automaticFailure().orElseThrow());
        Outcome<String, DayOfWeek, Month> withoutReaction = applied("root", List.of(), List.of(done(outer)));
        assertSame(outer, withoutReaction.automaticFailure().orElseThrow());
    }

    /** A step that an attempt's own commit triggered is found in that attempt's outcome, at any depth. */
    @Test
    void findsAStepNestedInAStepsOutcome() {
        AutomaticStep.Aborted deep = aborted("deep");
        Outcome.Ignored<String, DayOfWeek, Month> blocked = ignored("blocked", IgnoreReason.NO_CANDIDATE_ADMITTED);
        Outcome<String, DayOfWeek, Month> middle = applied("middle", List.of(), List.of(done(blocked), deep));
        Outcome<String, DayOfWeek, Month> outcome = applied("root", List.of(),
                List.of(done(applied("first", List.of(), List.of(done(middle))))));
        assertSame(deep, outcome.automaticAbort().orElseThrow());
        assertSame(blocked, outcome.automaticBlock().orElseThrow());
        assertEquals(Optional.empty(), outcome.automaticFailure());
    }

    /** A failure whose route continued the chain is still reported, though later steps applied. */
    @Test
    void aContinuedFailureIsStillReported() {
        Outcome.Failed<String, DayOfWeek, Month> continued = failed("account", List.of());
        Outcome<String, DayOfWeek, Month> outcome = applied("account", List.of(),
                List.of(done(applied("account", List.of(), List.of())), done(continued),
                        done(applied("account", List.of(), List.of()))));
        assertSame(continued, outcome.automaticFailure().orElseThrow());
        assertEquals(Optional.empty(), outcome.automaticAbort());
    }

    /**
     * A reaction that failed or aborted is the result of a watch, not of an automatic step: it is never answered,
     * although a step nested in the outcome of a failed reaction is.
     */
    @Test
    void ignoresAWatchReactionsOwnFailedAndAborted() {
        Reaction.Aborted abortedReaction = new Reaction.Aborted(KEY, "owner", new IllegalStateException("lookup"));
        Outcome<String, DayOfWeek, Month> outcome = applied("root",
                List.of(reacted(failed("owner", List.of())), abortedReaction), List.of());
        assertEquals(Optional.empty(), outcome.automaticFailure());
        assertEquals(Optional.empty(), outcome.automaticAbort());
        AutomaticStep.Aborted step = aborted("peer");
        Outcome<String, DayOfWeek, Month> nested = applied("root",
                List.of(reacted(failed("owner", List.of(step)))), List.of());
        assertSame(step, nested.automaticAbort().orElseThrow());
    }

    /** Only an attempt that admitted no candidate blocks: one whose state had no automatic edge does not. */
    @Test
    void blockIsOnlyNoCandidateAdmitted() {
        Outcome.Ignored<String, DayOfWeek, Month> blocked = ignored("b", IgnoreReason.NO_CANDIDATE_ADMITTED);
        Outcome<String, DayOfWeek, Month> outcome = applied("root", List.of(),
                List.of(done(ignored("a", IgnoreReason.NO_AUTOMATIC_TRANSITION)), done(blocked)));
        assertSame(blocked, outcome.automaticBlock().orElseThrow());
        Outcome<String, DayOfWeek, Month> unblocked = applied("root", List.of(),
                List.of(done(ignored("a", IgnoreReason.NO_AUTOMATIC_TRANSITION))));
        assertEquals(Optional.empty(), unblocked.automaticBlock());
    }

    /**
     * A rejected or ignored outcome has neither steps nor reactions; an ignored or failed outcome is never its own
     * answer: an advance's caller reads its first attempt directly.
     */
    @Test
    void rejectedAndIgnoredHaveNothing() {
        List<Outcome<String, DayOfWeek, Month>> outcomes = List.of(
                new Outcome.Rejected<>("r", DayOfWeek.MONDAY, Month.MARCH, "gate closed", RejectionKind.GUARD,
                        Optional.of("gate")),
                ignored("i", IgnoreReason.NO_CANDIDATE_ADMITTED), failed("f", List.of()));
        for (Outcome<String, DayOfWeek, Month> outcome : outcomes) {
            assertEquals(List.of(Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(outcome.automaticFailure(), outcome.automaticAbort(), outcome.automaticBlock()));
        }
    }
}
