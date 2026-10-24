package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.test.GraphAssertionError;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;

/**
 * Comparisons shared by every typed assertion, raising {@link GraphAssertionError} on mismatch.
 * A view calls {@code subject} before dereferencing the value under test, so that a null subject fails
 * as an inspectable assertion instead of a bare {@link NullPointerException}, and a view that holds the
 * value under test in an {@link Optional} calls {@code present}, which fails the same way, with a null
 * actual value, when the optional is empty; {@code equal} and {@code same} instead treat a null expected
 * value as a programming error in the calling test and raise {@link NullPointerException} from
 * {@link Objects#requireNonNull}. A view validates its own required arguments before it guards the
 * subject, so a null expected value stays a programming error even when the subject is null too.
 */
public final class Checks {

    private Checks() { }

    public static <T> T subject(String context, String property, Object expected, T actual) {
        if (actual == null) {
            fail(context, property, expected, null);
        }
        return actual;
    }

    public static <T> T present(String context, String property, Object expected, Optional<T> actual) {
        return actual.orElseThrow(() -> new GraphAssertionError(context, property, expected, null));
    }

    public static void equal(String context, String property, @NonNull Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            fail(context, property, expected, actual);
        }
    }

    public static void same(String context, String property, @NonNull Object expected, Object actual) {
        if (expected != actual) {
            fail(context, property, expected, actual);
        }
    }

    public static void fail(String context, String property, Object expected, Object actual) {
        throw new GraphAssertionError(context, property, expected, actual);
    }
}
