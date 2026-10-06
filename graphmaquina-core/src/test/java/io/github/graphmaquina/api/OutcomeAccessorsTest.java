package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
