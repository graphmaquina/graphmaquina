package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable assertion over the sub-machine starts a call reports, obtained from {@link BaseOutcomeAssert#starts()} on
 * any outcome view or from {@link AfterCommitAssert#starts()}. One call may start the children of several machines,
 * so the list is untyped as a whole and {@link #forChild(MachineKey, Object)} is what narrows it: it selects by
 * machine key and by child IDENTITY, never by equality, so two entities that carry the same business id stay distinct
 * subjects. Order and multiplicity are part of the contract, since a child may start more than once in one call. The
 * list is copied when the view is built, so later edits to the caller's collection are not seen, and a null element,
 * which only a list built by hand can hold, is refused then with a {@link NullPointerException}.
 */
public final class StartsAssert {

    private static final String ROOT = "starts";

    private final String context;
    private final Optional<List<SubmachineStart<?, ?, ?>>> actual;

    /** The view of a list that came from another view, which names it as that view's own. */
    StartsAssert(String context, Optional<? extends List<? extends SubmachineStart<?, ?, ?>>> actual) {
        this.context = context;
        this.actual = actual.map(StartsAssert::copy);
    }

    public static StartsAssert assertThat(List<? extends SubmachineStart<?, ?, ?>> actual) {
        return new StartsAssert(ROOT, Optional.ofNullable(actual));
    }

    public StartsAssert hasSize(int expected) {
        List<SubmachineStart<?, ?, ?>> starts = Checks.present(context, "size", expected, actual);
        Checks.equal(context, "size", expected, starts.size());
        return this;
    }

    public StartsAssert isEmpty() {
        List<SubmachineStart<?, ?, ?>> starts = Checks.present(context, "size", 0, actual);
        Checks.equal(context, "size", 0, starts.size());
        return this;
    }

    /** Selects by key and by child identity; a child with no starts yields an empty view, not a failure. */
    public <P, Q extends Enum<Q>, U extends Enum<U>> TypedStartsAssert<P, Q, U> forChild(
            MachineKey<P, Q, U> key, P child) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(child, "child");
        List<SubmachineStart<?, ?, ?>> starts = Checks.present(context, "child starts", child, actual);
        List<SubmachineStart<?, ?, ?>> selected = starts.stream()
                .filter((SubmachineStart<?, ?, ?> start) -> start.key().equals(key) && start.child() == child)
                .toList();
        return new TypedStartsAssert<>(context + " for " + key, selected);
    }

    private static List<SubmachineStart<?, ?, ?>> copy(List<? extends SubmachineStart<?, ?, ?>> starts) {
        return List.copyOf(starts);
    }
}
