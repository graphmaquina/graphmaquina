package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Contracts of what every outcome answers without being narrowed: its code and status, the explanation only the
 * graph's author writes, the records and reactions only a commit carries, and the narrowing accessors; plus the
 * bijections that keep both enums in step with the variants and their kinds.
 */
class OutcomeAccessorsTest {

    private static final String ENTITY = "e";

    private static final MachineKey<String, DayOfWeek, Month> KEY =
            MachineKey.of(String.class, DayOfWeek.class, Month.class);

    @Test
    void everyVariantReportsItsCode() {
        assertEquals(OutcomeCode.APPLIED, applied().code());
        assertEquals(OutcomeCode.NO_TRANSITION, rejected(RejectionKind.NO_TRANSITION).code());
        assertEquals(OutcomeCode.GUARD_REJECTED, rejected(RejectionKind.GUARD).code());
        assertEquals(OutcomeCode.FAILURE_ROUTED, failed(Optional.empty()).code());
        assertEquals(OutcomeCode.FAILURE_ROUTED, failed(Optional.of(new IOException("down"))).code());
        assertEquals(OutcomeCode.NO_WATCH, ignored(IgnoreReason.NO_WATCH).code());
        assertEquals(OutcomeCode.NO_RULE_MATCHED, ignored(IgnoreReason.NO_RULE_MATCHED).code());
    }

    @Test
    void everyVariantReportsTheStatusOfItsCode() {
        assertEquals(OutcomeStatus.APPLIED, applied().status());
        assertEquals(OutcomeStatus.REJECTED, rejected(RejectionKind.NO_TRANSITION).status());
        assertEquals(OutcomeStatus.REJECTED, rejected(RejectionKind.GUARD).status());
        assertEquals(OutcomeStatus.FAILED, failed(Optional.empty()).status());
        assertEquals(OutcomeStatus.IGNORED, ignored(IgnoreReason.NO_WATCH).status());
        assertEquals(OutcomeStatus.IGNORED, ignored(IgnoreReason.NO_RULE_MATCHED).status());
    }

    @Test
    void everyCodeRefinesExactlyOneStatus() {
        assertEquals(OutcomeStatus.APPLIED, OutcomeCode.APPLIED.status());
        assertEquals(OutcomeStatus.REJECTED, OutcomeCode.NO_TRANSITION.status());
        assertEquals(OutcomeStatus.REJECTED, OutcomeCode.GUARD_REJECTED.status());
        assertEquals(OutcomeStatus.FAILED, OutcomeCode.FAILURE_ROUTED.status());
        assertEquals(OutcomeStatus.IGNORED, OutcomeCode.NO_WATCH.status());
        assertEquals(OutcomeStatus.IGNORED, OutcomeCode.NO_RULE_MATCHED.status());
    }

    /** A variant added without its status, or a status without a variant, breaks the one-to-one naming. */
    @Test
    void theStatusesNameThePermittedVariantsOneToOne() {
        Set<String> variants = new TreeSet<>();
        for (Class<?> variant : Outcome.class.getPermittedSubclasses()) {
            variants.add(variant.getSimpleName().toUpperCase(Locale.ROOT));
        }
        Set<String> statuses = new TreeSet<>();
        for (OutcomeStatus status : OutcomeStatus.values()) {
            statuses.add(status.name());
        }
        assertEquals(variants, statuses);
    }

    /** Classifying by code never hides a variant: every status has at least one code. */
    @Test
    void everyStatusHasACode() {
        Set<OutcomeStatus> covered = EnumSet.noneOf(OutcomeStatus.class);
        for (OutcomeCode code : OutcomeCode.values()) {
            covered.add(code.status());
        }
        assertEquals(EnumSet.allOf(OutcomeStatus.class), covered);
    }

    /**
     * A rejection kind or an ignore reason added without a code of its own would merge two causes into one code,
     * and a code nothing produces would promise a cause the engine never reports.
     */
    @Test
    void everyKindAndReasonHasItsOwnCodeAndEveryCodeHasACause() {
        Set<OutcomeCode> produced = EnumSet.of(applied().code(), failed(Optional.empty()).code());
        for (RejectionKind kind : RejectionKind.values()) {
            produced.add(rejected(kind).code());
        }
        for (IgnoreReason reason : IgnoreReason.values()) {
            produced.add(ignored(reason).code());
        }
        assertEquals(2 + RejectionKind.values().length + IgnoreReason.values().length, produced.size());
        assertEquals(EnumSet.allOf(OutcomeCode.class), produced);
    }

    /** Only author-written text comes out: a policy's or a guard's reason, and the reason an action returned. */
    @Test
    void theExplanationIsTheReasonTheAuthorWrote() {
        assertEquals(Optional.of("not from here"), rejected(RejectionKind.NO_TRANSITION).explanation());
        assertEquals(Optional.of("gate closed"), rejected(RejectionKind.GUARD).explanation());
        assertEquals(Optional.of("declined"), failed(Optional.empty()).explanation());
    }

    /** Nothing is fabricated: an applied or an ignored outcome carries no text of its own. */
    @Test
    void appliedAndIgnoredOutcomesExplainNothing() {
        assertEquals(Optional.empty(), applied().explanation());
        assertEquals(Optional.empty(), ignored(IgnoreReason.NO_WATCH).explanation());
        assertEquals(Optional.empty(), ignored(IgnoreReason.NO_RULE_MATCHED).explanation());
    }

    /**
     * A reason failsOn composed from an exception may carry internal details, such as an address: it stays on the
     * variant and never reaches the explanation.
     */
    @Test
    void aFailureRoutedFromAnExceptionKeepsItsReasonOutOfTheExplanation() {
        Outcome.Failed<String, DayOfWeek, Month> routed = new Outcome.Failed<>(ENTITY, DayOfWeek.MONDAY,
                Month.JANUARY, DayOfWeek.TUESDAY, Month.MARCH, DayOfWeek.SUNDAY,
                "IOException: connection refused by 10.0.0.12",
                Optional.of(new IOException("connection refused by 10.0.0.12")), List.of(), List.of());
        assertEquals(Optional.empty(), routed.explanation());
        assertEquals("IOException: connection refused by 10.0.0.12", routed.reason());
    }

    /** Only a commit carries hops and reactions; the variants that committed nothing answer empty lists. */
    @Test
    void recordsAndReactionsComeFromTheCommitOnly() {
        TransitionRecord<String, DayOfWeek, Month> hop = new TransitionRecord<>(KEY, ENTITY, DayOfWeek.MONDAY,
                Month.JANUARY, DayOfWeek.TUESDAY, TransitionKind.MOVE, Optional.empty(), 0);
        Reaction reaction = new Reaction.Aborted(KEY, ENTITY, new IllegalStateException("watch down"));
        Outcome<String, DayOfWeek, Month> applied = new Outcome.Applied<>(ENTITY, DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, List.of(hop), List.of(reaction));
        Outcome<String, DayOfWeek, Month> failed = new Outcome.Failed<>(ENTITY, DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, Month.MARCH, DayOfWeek.SUNDAY, "declined", Optional.empty(), List.of(hop),
                List.of(reaction));
        assertEquals(List.of(hop), applied.records());
        assertEquals(List.of(reaction), applied.reactions());
        assertEquals(List.of(hop), failed.records());
        assertEquals(List.of(reaction), failed.reactions());
        for (Outcome<String, DayOfWeek, Month> nothing : List.of(rejected(RejectionKind.NO_TRANSITION),
                rejected(RejectionKind.GUARD), ignored(IgnoreReason.NO_WATCH), ignored(IgnoreReason.NO_RULE_MATCHED))) {
            assertEquals(List.of(), nothing.records());
            assertEquals(List.of(), nothing.reactions());
        }
    }

    /** Each variant narrows to itself, with the outcome's type arguments, and to none of the other three. */
    @Test
    void everyVariantNarrowsToItselfAndToNothingElse() {
        Outcome<String, DayOfWeek, Month> applied = applied();
        Outcome<String, DayOfWeek, Month> rejected = rejected(RejectionKind.GUARD);
        Outcome<String, DayOfWeek, Month> failed = failed(Optional.empty());
        Outcome<String, DayOfWeek, Month> ignored = ignored(IgnoreReason.NO_WATCH);
        assertSame(applied, applied.asApplied().orElseThrow());
        assertSame(rejected, rejected.asRejected().orElseThrow());
        assertSame(failed, failed.asFailed().orElseThrow());
        assertSame(ignored, ignored.asIgnored().orElseThrow());
        assertTrue(applied.asRejected().isEmpty());
        assertTrue(applied.asFailed().isEmpty());
        assertTrue(applied.asIgnored().isEmpty());
        assertTrue(rejected.asApplied().isEmpty());
        assertTrue(rejected.asFailed().isEmpty());
        assertTrue(rejected.asIgnored().isEmpty());
        assertTrue(failed.asApplied().isEmpty());
        assertTrue(failed.asRejected().isEmpty());
        assertTrue(failed.asIgnored().isEmpty());
        assertTrue(ignored.asApplied().isEmpty());
        assertTrue(ignored.asRejected().isEmpty());
        assertTrue(ignored.asFailed().isEmpty());
    }

    /** A field only one variant carries reads with its own type, without writing the type arguments. */
    @Test
    void aNarrowedVariantReadsItsOwnFieldsTyped() {
        Month recovery = failed(Optional.empty()).asFailed().map(Outcome.Failed::failureTransition).orElseThrow();
        assertEquals(Month.MARCH, recovery);
        DayOfWeek target = failed(Optional.empty()).asFailed().map(Outcome.Failed::to).orElseThrow();
        assertEquals(DayOfWeek.TUESDAY, target);
        assertEquals(Optional.of("gate"), rejected(RejectionKind.GUARD).asRejected().flatMap(Outcome.Rejected::guard));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static Outcome<String, DayOfWeek, Month> applied() {
        return new Outcome.Applied<>(ENTITY, DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, List.of(), List.of());
    }

    /** A guard rejection names its guard and an absent edge does not, as the record requires. */
    private static Outcome<String, DayOfWeek, Month> rejected(RejectionKind kind) {
        boolean guard = kind == RejectionKind.GUARD;
        return new Outcome.Rejected<>(ENTITY, DayOfWeek.MONDAY, Month.JANUARY, guard ? "gate closed" : "not from here",
                kind, guard ? Optional.of("gate") : Optional.empty());
    }

    private static Outcome<String, DayOfWeek, Month> failed(Optional<Throwable> cause) {
        return new Outcome.Failed<>(ENTITY, DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, Month.MARCH,
                DayOfWeek.SUNDAY, "declined", cause, List.of(), List.of());
    }

    private static Outcome<String, DayOfWeek, Month> ignored(IgnoreReason reason) {
        return new Outcome.Ignored<>(ENTITY, DayOfWeek.MONDAY, reason);
    }
}
