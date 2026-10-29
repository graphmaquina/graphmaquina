package io.github.graphmaquina.test;

import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The identifiers of the automatic edges that leave one state, in the order an advance tries them, obtained from
 * {@link GraphAssert#automaticFrom(Enum)}. The order is part of the contract: a state with two or more automatic edges
 * declares it, and a state with one lists that one. Nothing here runs a guard or an action.
 *
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class AutomaticEdgesAssert<T extends Enum<T>> {

    private final String context;
    private final List<T> actual;

    /**
     * Compares the identifiers with {@code expected}, exactly and in order, keeping duplicates; no argument matches a
     * state that has no automatic edge. A null among them is never equal to an identifier, so it fails as an expectation.
     */
    @SafeVarargs
    public final AutomaticEdgesAssert<T> containsExactly(T... expected) {
        List<T> wanted = new ArrayList<>();
        for (T transition : expected) {
            wanted.add(transition);
        }
        List<T> edges = Checks.subject(context, "automatic transitions", wanted, actual);
        Checks.equal(context, "automatic transitions", wanted, edges);
        return this;
    }
}
