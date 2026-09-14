package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable assertion over a {@link TransitionAbortedException}: the transaction was discarded, so nothing
 * moved and there are no committed hops to read — only what was being attempted, how far the cascade had
 * gone, and which actions had already run. The cascade route is compared as the list of literal strings the
 * engine wrote; no expectation here takes it apart to recover a state or an identity.
 */
public final class AbortAssert extends BaseFailureAssert<AbortAssert> {

    private final TransitionAbortedException abort;

    AbortAssert(String context, TransitionAbortedException abort) {
        super(context, abort);
        this.abort = abort;
    }

    @Override
    protected AbortAssert self() {
        return this;
    }

    /** The four fields that say what was being attempted; the entity is matched by reference. */
    public <E, S extends Enum<S>> AbortAssert at(MachineKey<E, S> key, E entity, S from, S to) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Checks.equal(context, "key", key, abort.key());
        Checks.same(context, "entity", entity, abort.entity());
        Checks.equal(context, "from", from, abort.from());
        Checks.equal(context, "to", to, abort.to());
        return this;
    }

    /** The action that was running when the transition aborted, which a FORBID cell never names. */
    public AbortAssert hasActionNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "action", Optional.of(expected), abort.action());
        return this;
    }

    public AbortAssert hasNoAction() {
        Checks.equal(context, "action", Optional.empty(), abort.action());
        return this;
    }

    /** Order and multiplicity included, compared against the engine's own strings. */
    public AbortAssert hasCascadePathExactly(List<String> expected) {
        Objects.requireNonNull(expected, "expected");
        List<String> wanted = List.copyOf(expected);
        Checks.equal(context, "cascade path", wanted, abort.cascadePath());
        return this;
    }

    /** The actions that ran before the abort, which survive it even though nothing was committed. */
    public JournalAssert journal() {
        return JournalAssert.assertThat(abort.journal());
    }
}
