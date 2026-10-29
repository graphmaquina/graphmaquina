package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * Chainable assertion over a {@link TransitionAbortedException}: the transaction was discarded, so nothing
 * moved and there are no committed hops to read. This view reads what was being attempted, how far the cascade
 * had gone, and which actions had already run. The cascade route is compared as the list of literal strings the
 * engine wrote; no expectation here takes it apart to recover a state or an identity.
 *
 * <p>{@link #at(MachineKey, Object, Enum, Enum, Enum)} describes an abort that had already resolved its
 * edge; {@link #atUnresolved(MachineKey, Object, Enum, Enum)} describes one raised before an edge was
 * resolved, which has no destination to report because the identifier matched none from that state; and
 * {@link #atUnread(MachineKey, Object, Enum)} describes one raised before any identifier was read, such as
 * a chain of watch reactions that exceeds its limit, which reports neither.
 * {@link #atUnreadState(MachineKey, Object, Enum)} and {@link #atUnreadState(MachineKey, Object)} describe one raised
 * before the entity's state was read, which reports no state either.
 */
public final class AbortAssert extends BaseCallFailureAssert<AbortAssert> {

    private final TransitionAbortedException abort;

    AbortAssert(String context, TransitionAbortedException abort) {
        super(context, abort);
        this.abort = abort;
    }

    @Override
    protected AbortAssert self() {
        return this;
    }

    /** The five fields that say which edge was being run; the entity is matched by reference. */
    public <E, S extends Enum<S>, T extends Enum<T>> AbortAssert at(@NonNull MachineKey<E, S, T> key, @NonNull E entity,
            @NonNull S from, @NonNull T transition, @NonNull S to) {
        Checks.equal(context, "key", key, abort.key());
        Checks.same(context, "entity", entity, abort.entity());
        Checks.equal(context, "from", Optional.<Enum<?>>of(from), abort.from());
        Checks.equal(context, "transition", Optional.<Enum<?>>of(transition), abort.transition());
        Checks.equal(context, "to", Optional.<Enum<?>>of(to), abort.to());
        return this;
    }

    /**
     * The request that was refused before any edge was resolved: the identifier was read, so it is compared,
     * and no destination is reported because none was selected.
     */
    public <E, S extends Enum<S>, T extends Enum<T>> AbortAssert atUnresolved(@NonNull MachineKey<E, S, T> key,
            @NonNull E entity, @NonNull S from, @NonNull T transition) {
        Checks.equal(context, "key", key, abort.key());
        Checks.same(context, "entity", entity, abort.entity());
        Checks.equal(context, "from", Optional.<Enum<?>>of(from), abort.from());
        Checks.equal(context, "transition", Optional.<Enum<?>>of(transition), abort.transition());
        Checks.equal(context, "to", Optional.empty(), abort.to());
        return this;
    }

    /**
     * The abort that was raised before any identifier was read, so that it reports neither an identifier nor a
     * destination: the machine, the entity by reference and the state it was in are all there is to locate it.
     * An abort that did read an identifier does not satisfy it.
     */
    public <E, S extends Enum<S>, T extends Enum<T>> AbortAssert atUnread(@NonNull MachineKey<E, S, T> key,
            @NonNull E entity, @NonNull S from) {
        Checks.equal(context, "key", key, abort.key());
        Checks.same(context, "entity", entity, abort.entity());
        Checks.equal(context, "from", Optional.<Enum<?>>of(from), abort.from());
        Checks.equal(context, "transition", Optional.empty(), abort.transition());
        Checks.equal(context, "to", Optional.empty(), abort.to());
        return this;
    }

    /**
     * The abort raised before the entity's state was read, by an {@code execute} asked for {@code transition}, by
     * the edge {@code transition} a reevaluation selected, or by the automatic edge {@code transition} an advance
     * tried: the entity's identity or its state could not be read, so it reports neither a state nor a destination.
     */
    public <E, S extends Enum<S>, T extends Enum<T>> AbortAssert atUnreadState(@NonNull MachineKey<E, S, T> key,
            @NonNull E entity, @NonNull T transition) {
        Checks.equal(context, "key", key, abort.key());
        Checks.same(context, "entity", entity, abort.entity());
        Checks.equal(context, "from", Optional.empty(), abort.from());
        Checks.equal(context, "transition", Optional.<Enum<?>>of(transition), abort.transition());
        Checks.equal(context, "to", Optional.empty(), abort.to());
        return this;
    }

    /**
     * The same for a reevaluation or an advance that could not read the state before it ran an edge, which ask for no
     * identifier: it reports none either.
     */
    public <E, S extends Enum<S>, T extends Enum<T>> AbortAssert atUnreadState(@NonNull MachineKey<E, S, T> key,
            @NonNull E entity) {
        Checks.equal(context, "key", key, abort.key());
        Checks.same(context, "entity", entity, abort.entity());
        Checks.equal(context, "from", Optional.empty(), abort.from());
        Checks.equal(context, "transition", Optional.empty(), abort.transition());
        Checks.equal(context, "to", Optional.empty(), abort.to());
        return this;
    }

    /** The action that was running when the transition aborted, which an edge without one never names. */
    public AbortAssert hasActionNamed(@NonNull String expected) {
        Checks.equal(context, "action", Optional.of(expected), abort.action());
        return this;
    }

    public AbortAssert hasNoAction() {
        Checks.equal(context, "action", Optional.empty(), abort.action());
        return this;
    }

    /** Order and multiplicity included, compared against the engine's own strings. */
    public AbortAssert hasCascadePathExactly(@NonNull List<String> expected) {
        List<String> wanted = List.copyOf(expected);
        Checks.equal(context, "cascade path", wanted, abort.cascadePath());
        return this;
    }
}
