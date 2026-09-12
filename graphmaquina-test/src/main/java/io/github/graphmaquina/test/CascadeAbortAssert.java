package io.github.graphmaquina.test;

import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;

/**
 * Chainable assertion over a {@link CascadeException}, the aggregate a COLLECT cascade raises after trying
 * every element. The elements that failed are kept in the order they were tried and are read one at a time
 * through {@link #error(int)}; the journal is the parent transaction's, not any single element's.
 */
public final class CascadeAbortAssert extends BaseFailureAssert<CascadeAbortAssert> {

    private final CascadeException cascade;

    CascadeAbortAssert(String context, CascadeException cascade) {
        super(context, cascade);
        this.cascade = cascade;
    }

    @Override
    protected CascadeAbortAssert self() {
        return this;
    }

    public CascadeAbortAssert hasErrorCount(int expected) {
        Checks.equal(context, "error count", expected, cascade.errors().size());
        return this;
    }

    /** One collected element, by its position among the failures rather than among the cascaded entities. */
    public AbortAssert error(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        List<TransitionAbortedException> errors = cascade.errors();
        if (index >= errors.size()) {
            Checks.fail(context, "index " + index + " present", true, false);
        }
        return new AbortAssert(context + "[" + index + "]", errors.get(index));
    }

    /** The parent transaction's journal: every action that ran, the collected elements' included. */
    public JournalAssert journal() {
        return JournalAssert.assertThat(cascade.journal());
    }
}
