package io.github.graphmaquina.test;

import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Chainable assertion over an {@link InvalidGraphException} raised by {@code build()} of the DSL. It reads
 * the stable problem codes and how many problems were found, never the human-readable text: the wording of a
 * problem is not part of the contract, its code is.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class InvalidGraphAssert {

    private static final String CONTEXT = "invalid graph";

    private final InvalidGraphException actual;

    public static InvalidGraphAssert assertThat(InvalidGraphException actual) {
        return new InvalidGraphAssert(actual);
    }

    /** The codes in the order the exception reports them, with their multiplicity: one entry per problem. */
    public InvalidGraphAssert hasCodesExactly(String... expected) {
        List<String> wanted = List.of(expected);
        InvalidGraphException failure = Checks.subject(CONTEXT, "codes", wanted, actual);
        Checks.equal(CONTEXT, "codes", wanted, failure.codes());
        return this;
    }

    public InvalidGraphAssert hasProblemCount(int expected) {
        InvalidGraphException failure = Checks.subject(CONTEXT, "problem count", expected, actual);
        Checks.equal(CONTEXT, "problem count", expected, failure.problems().size());
        return this;
    }
}
