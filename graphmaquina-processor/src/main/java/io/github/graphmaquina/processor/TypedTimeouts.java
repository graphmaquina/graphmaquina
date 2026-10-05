package io.github.graphmaquina.processor;

import java.time.Duration;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;

/**
 * Reads the timeout of a transition as the two fields a {@link Duration} is built from: whole seconds and a
 * nanosecond adjustment.
 *
 * <p>Nothing is converted to a single unit first. Nanoseconds alone cannot hold the durations {@code Duration}
 * accepts, and milliseconds cannot hold the precision it keeps, so either conversion would refuse or round a
 * declaration the class itself represents exactly.</p>
 *
 * <p>The representation is checked before the duration is built, never by catching what building it throws:
 * the pair that overflows and the pair that is simply not a duration are the same failure to an author, and
 * both are reported as {@code SG511} at the declaration that wrote them. {@code Duration.ofSeconds} overflows only
 * when the nanosecond adjustment moves the seconds out of the range of a {@code long}, which an adjustment from 0
 * to 999,999,999 never does, so the range check of the adjustment refuses every pair it would overflow on.</p>
 */
final class TypedTimeouts {

    private static final String INVALID = "SG511";
    private static final int MAX_NANOS = 999_999_999;

    private final Annotations annotations;
    private final Diagnostics diagnostics;

    TypedTimeouts(Annotations annotations, Diagnostics diagnostics) {
        this.annotations = annotations;
        this.diagnostics = diagnostics;
    }

    /**
     * The duration {@code timeout} spells, or empty after reporting {@code SG511} when it is not a positive one;
     * empty too, with nothing reported, for a value javac has already refused.
     */
    Optional<Duration> read(AnnotationMirror timeout, DiagnosticAnchor anchor) {
        if (!(annotations.value(timeout, "seconds").getValue() instanceof Long seconds)
                || !(annotations.value(timeout, "nanos").getValue() instanceof Integer nanos)) {
            // javac has already refused a value it could not read as the number the element declares.
            return Optional.empty();
        }
        if (seconds < 0 || nanos < 0 || nanos > MAX_NANOS || (seconds == 0 && nanos == 0)) {
            anchor.report(diagnostics, INVALID, "timeout must be a positive duration: seconds " + seconds + " and nanos "
                    + nanos + " must not be negative, nanos must be at most " + MAX_NANOS + ", and the two must not both be zero");
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(seconds, nanos));
    }
}
