package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contracts of the eligibility result: the invariants of the ineligible variant, which repeat those of a rejection,
 * what each variant explains, the narrowing accessors, and the null components both variants refuse.
 */
class EligibilityTest {

    /** The states of the fixture machine. */
    enum S { OPEN, DONE }

    /** The transition identifiers of the fixture machine. */
    enum T { GO }

    private static final String ENTITY = "e";

    @Test
    void ineligibleRefusesABlankReason() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Eligibility.Ineligible<>(ENTITY, S.OPEN, T.GO, " ", RejectionKind.NO_TRANSITION,
                        Optional.empty()));
        assertEquals("reason must not be blank", refused.getMessage());
    }

    @Test
    void aGuardRejectionNamesItsGuard() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Eligibility.Ineligible<>(ENTITY, S.OPEN, T.GO, "needs review", RejectionKind.GUARD,
                        Optional.empty()));
        assertEquals("a GUARD rejection names the guard that rejected", refused.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = RejectionKind.class, names = "GUARD", mode = EnumSource.Mode.EXCLUDE)
    void onlyAGuardRejectionCarriesAGuard(RejectionKind kind) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Eligibility.Ineligible<>(ENTITY, S.OPEN, T.GO, "needs review", kind,
                        Optional.of("reviewed")));
        assertEquals("a " + kind + " rejection carries no guard", refused.getMessage());
    }

    @Test
    void aGuardRejectionKeepsTheGuardThatRejected() {
        Eligibility.Ineligible<String, S, T> ineligible =
                new Eligibility.Ineligible<>(ENTITY, S.OPEN, T.GO, "needs review", RejectionKind.GUARD,
                        Optional.of("reviewed"));
        assertEquals(RejectionKind.GUARD, ineligible.kind());
        assertEquals(Optional.of("reviewed"), ineligible.guard());
    }

    @ParameterizedTest
    @EnumSource(value = RejectionKind.class, names = "GUARD", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherKindIsIneligibleWithoutAGuard(RejectionKind kind) {
        Eligibility.Ineligible<String, S, T> ineligible =
                new Eligibility.Ineligible<>(ENTITY, S.OPEN, T.GO, "needs review", kind, Optional.empty());
        assertEquals(kind, ineligible.kind());
        assertEquals(Optional.empty(), ineligible.guard());
    }

    @Test
    void eligibleExplainsNothing() {
        Eligibility.Eligible<String, S, T> eligible = new Eligibility.Eligible<>(ENTITY, S.OPEN, T.GO);
        assertEquals(Optional.empty(), eligible.explanation());
        assertTrue(eligible.isEligible());
        assertTrue(eligible.asIneligible().isEmpty());
    }

    @Test
    void ineligibleExplainsItsReason() {
        Eligibility.Ineligible<String, S, T> ineligible = ineligible();
        assertEquals(Optional.of("needs review"), ineligible.explanation());
        assertFalse(ineligible.isEligible());
        assertSame(ineligible, ineligible.asIneligible().orElseThrow());
    }

    @Test
    void eachVariantNarrowsOnlyToItself() {
        Eligibility.Eligible<String, S, T> eligible = new Eligibility.Eligible<>(ENTITY, S.OPEN, T.GO);
        assertSame(eligible, eligible.asEligible().orElseThrow());
        assertTrue(ineligible().asEligible().isEmpty());
    }

    @Test
    void bothVariantsReportTheEntityTheStateAndTheTransitionAsked() {
        Eligibility<String, S, T> eligible = new Eligibility.Eligible<>(ENTITY, S.DONE, T.GO);
        Eligibility<String, S, T> ineligible = ineligible();
        assertEquals(ENTITY, eligible.entity());
        assertEquals(S.DONE, eligible.from());
        assertEquals(T.GO, eligible.transition());
        assertEquals(ENTITY, ineligible.entity());
        assertEquals(S.OPEN, ineligible.from());
        assertEquals(T.GO, ineligible.transition());
    }

    @Test
    void eligibleRefusesNullComponents() {
        assertEquals("entity is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new Eligibility.Eligible<String, S, T>(null, S.OPEN, T.GO)).getMessage());
        assertEquals("from is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new Eligibility.Eligible<String, S, T>(ENTITY, null, T.GO)).getMessage());
        assertEquals("transition is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new Eligibility.Eligible<String, S, T>(ENTITY, S.OPEN, null)).getMessage());
    }

    @Test
    void ineligibleRefusesNullComponents() {
        assertEquals("reason is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new Eligibility.Ineligible<String, S, T>(ENTITY, S.OPEN, T.GO, null,
                        RejectionKind.NO_TRANSITION, Optional.empty())).getMessage());
        assertEquals("kind is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new Eligibility.Ineligible<String, S, T>(ENTITY, S.OPEN, T.GO, "needs review", null,
                        Optional.empty())).getMessage());
        assertEquals("guard is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new Eligibility.Ineligible<String, S, T>(ENTITY, S.OPEN, T.GO, "needs review",
                        RejectionKind.NO_TRANSITION, null)).getMessage());
    }

    private static Eligibility.Ineligible<String, S, T> ineligible() {
        return new Eligibility.Ineligible<>(ENTITY, S.OPEN, T.GO, "needs review", RejectionKind.NO_TRANSITION,
                Optional.empty());
    }
}
