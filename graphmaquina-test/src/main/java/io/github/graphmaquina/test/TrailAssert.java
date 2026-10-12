package io.github.graphmaquina.test;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.test.internal.Checks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable assertion over the trail of a call, obtained from {@link FlowAssert#trail()} or from
 * {@link #assertThat(List)} for a list a test holds on its own. A call records its trail when its root machine was
 * built with {@code recordTrail}: a {@link FlowEntry.Hop} for each hop and a {@link FlowEntry.Start} for each
 * sub-machine start, in the order the call took them, then a final {@link FlowEntry.Rejected} or
 * {@link FlowEntry.Failed} when the call ended so. A clock that threw or answered null when the call read that instant
 * leaves the {@link FlowEntry.Failed} entry out, and fails a rejected call, whose trail then ends as a failed call's;
 * it also leaves out the hop or start whose instant it could not read. Order and multiplicity are part of the contract.
 * An entry's class is compared exactly, so {@code FlowEntry} itself matches none. {@link #hasHopsExactly(Enum[])} names
 * each hop by the identifier of its transition alone, whatever machine took it; {@link #hops()} opens the records of
 * the hops, which say which machine and entity took each, from which state to which, so the hops of a call that failed
 * before its commit, which has no committed records, can be asserted as an outcome's are. The list is copied when the
 * view is built, so later edits to the caller's collection are not seen, and a null element, which only a list built by
 * hand can hold, is refused then with a {@link NullPointerException}.
 */
public final class TrailAssert {

    private static final String ROOT = "trail";
    private static final String LAST = "last entry";
    private static final String NONE = "none";

    private final String context;
    private final Optional<List<FlowEntry>> actual;

    /** The view of a trail that came from another view, which names it as that view's own. */
    TrailAssert(String context, Optional<? extends List<? extends FlowEntry>> actual) {
        this.context = context;
        this.actual = actual.map(TrailAssert::copy);
    }

    public static TrailAssert assertThat(List<? extends FlowEntry> actual) {
        return new TrailAssert(ROOT, Optional.ofNullable(actual));
    }

    public TrailAssert hasSize(int expected) {
        List<FlowEntry> trail = Checks.present(context, "size", expected, actual);
        Checks.equal(context, "size", expected, trail.size());
        return this;
    }

    public TrailAssert isEmpty() {
        List<FlowEntry> trail = Checks.present(context, "size", 0, actual);
        Checks.equal(context, "size", 0, trail.size());
        return this;
    }

    /**
     * The class of every entry, in order, each compared with the entry's own class: a {@code Hop}, a {@code Start}, a
     * {@code Rejected} or a {@code Failed} of {@link FlowEntry}. No argument expects an empty trail.
     */
    @SafeVarargs
    public final TrailAssert hasEntryTypesExactly(Class<? extends FlowEntry>... types) {
        Objects.requireNonNull(types, "types");
        List<Class<? extends FlowEntry>> declared = new ArrayList<>();
        for (Class<? extends FlowEntry> type : types) {
            declared.add(type);
        }
        List<Class<? extends FlowEntry>> wanted = List.copyOf(declared);
        List<FlowEntry> trail = Checks.present(context, "entry types", wanted, actual);
        List<Class<? extends FlowEntry>> taken = new ArrayList<>();
        for (FlowEntry entry : trail) {
            taken.add(entry.getClass());
        }
        Checks.equal(context, "entry types", wanted, taken);
        return this;
    }

    /**
     * The identifier of the transition of every {@link FlowEntry.Hop}, in order and with multiplicity, whatever machine
     * took it; the sub-machine starts and the final rejection or failure are skipped. No argument expects a trail
     * without a hop.
     */
    public TrailAssert hasHopsExactly(Enum<?>... transitions) {
        Objects.requireNonNull(transitions, "transitions");
        List<Enum<?>> wanted = List.of(transitions);
        List<FlowEntry> trail = Checks.present(context, "hops", wanted, actual);
        List<Enum<?>> hops = new ArrayList<>();
        for (TransitionRecord<?, ?, ?> hop : hopsOf(trail)) {
            hops.add(hop.transition());
        }
        Checks.equal(context, "hops", wanted, hops);
        return this;
    }

    /**
     * The record of every {@link FlowEntry.Hop}, in trail order and with multiplicity, as a list of transition records:
     * its machine, its entity, compared by reference, its states, its identifier, its kind, its action and its depth.
     * The sub-machine starts and the final rejection or failure are skipped. In an outcome's trail, these are the very
     * records that the outcome's {@code records()} lists.
     */
    public TransitionRecordsAssert hops() {
        return new TransitionRecordsAssert(context + ".hops", actual.map(TrailAssert::hopsOf));
    }

    /**
     * No entry's {@link FlowEntry#at()} is earlier than the one of the entry before it, so equal instants satisfy it.
     * The first entry out of order is reported by its position, with the instant it should not precede.
     */
    public TrailAssert isInTimeOrder() {
        List<FlowEntry> trail = Checks.present(context, "at", "never decreasing", actual);
        for (int index = 1; index < trail.size(); index++) {
            Instant previous = trail.get(index - 1).at();
            Instant at = trail.get(index).at();
            if (at.isBefore(previous)) {
                Checks.fail(context + "[" + index + "]", "at", "not before " + previous, at);
            }
        }
        return this;
    }

    /** The last entry is the {@link FlowEntry.Rejected} of a rejection of that kind. */
    public TrailAssert endsWithRejection(RejectionKind kind) {
        Objects.requireNonNull(kind, "kind");
        List<FlowEntry> trail = Checks.present(context, LAST, FlowEntry.Rejected.class, actual);
        FlowEntry.Rejected rejected = last(trail, FlowEntry.Rejected.class);
        Checks.equal(context + "[" + (trail.size() - 1) + "]", "kind", kind, rejected.kind());
        return this;
    }

    /** The last entry is the {@link FlowEntry.Failed} of a failure in that phase. */
    public TrailAssert endsWithFailure(CallPhase phase) {
        Objects.requireNonNull(phase, "phase");
        List<FlowEntry> trail = Checks.present(context, LAST, FlowEntry.Failed.class, actual);
        FlowEntry.Failed failed = last(trail, FlowEntry.Failed.class);
        Checks.equal(context + "[" + (trail.size() - 1) + "]", "phase", phase, failed.failure().phase());
        return this;
    }

    /** The last entry, once it is known to be of {@code type}; an empty trail reports {@code none}. */
    private <T extends FlowEntry> T last(List<FlowEntry> trail, Class<T> type) {
        if (trail.isEmpty()) {
            Checks.fail(context, LAST, type, NONE);
        }
        FlowEntry entry = trail.get(trail.size() - 1);
        if (!type.isInstance(entry)) {
            Checks.fail(context, LAST, type, entry);
        }
        return type.cast(entry);
    }

    /** The record of every hop of {@code trail}, in its order. */
    private static List<TransitionRecord<?, ?, ?>> hopsOf(List<FlowEntry> trail) {
        List<TransitionRecord<?, ?, ?>> hops = new ArrayList<>();
        for (FlowEntry entry : trail) {
            if (entry instanceof FlowEntry.Hop hop) {
                hops.add(hop.hop());
            }
        }
        return hops;
    }

    private static List<FlowEntry> copy(List<? extends FlowEntry> entries) {
        return List.copyOf(entries);
    }
}
