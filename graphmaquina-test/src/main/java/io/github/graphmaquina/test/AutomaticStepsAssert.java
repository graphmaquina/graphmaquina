package io.github.graphmaquina.test;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/**
 * Chainable assertion over the automatic attempts a committed outcome carries, obtained from {@link
 * BaseOutcomeAssert#automaticSteps()} on any outcome view. An attempt belongs to the machine of the entity whose chain
 * ran it, so the list is untyped as a whole and {@link #forEntity(MachineKey, Object)} is what narrows it: it selects
 * by machine key and by entity IDENTITY, never by entity equality. A throwable that is not an {@link Error} does not
 * propagate to the commit that triggered the attempt but is reported as an aborted step, which is why {@link
 * #hasNoAborted()} exists at all, and an {@link Error} is not caught, so it never reaches this list; and because the
 * outcome of an attempt carries automatic steps of its own, the direct and the recursive check answer different
 * questions. Order and multiplicity are part of the contract, and the list is copied when the view is built, so later
 * edits to the caller's collection are not seen. A null element, which only a subject built by hand can hold, is
 * neither an aborted step nor a source of nested ones, and no entity selects it.
 */
public final class AutomaticStepsAssert {

    private static final String ROOT = "automatic steps";
    private static final String ABORTED = "aborted automatic step";
    private static final String NONE = "none";

    private final String context;
    private final List<AutomaticStep> actual;

    /** The view of a list that came from another view, which names it as that view's own. */
    AutomaticStepsAssert(String context, List<? extends AutomaticStep> actual) {
        this.context = context;
        this.actual = actual == null ? null : Collections.unmodifiableList(new ArrayList<>(actual));
    }

    public static AutomaticStepsAssert assertThat(List<? extends AutomaticStep> actual) {
        return new AutomaticStepsAssert(ROOT, actual);
    }

    public AutomaticStepsAssert hasSize(int expected) {
        List<AutomaticStep> steps = Checks.subject(context, "size", expected, actual);
        Checks.equal(context, "size", expected, steps.size());
        return this;
    }

    public AutomaticStepsAssert isEmpty() {
        List<AutomaticStep> steps = Checks.subject(context, "size", 0, actual);
        Checks.equal(context, "size", 0, steps.size());
        return this;
    }

    /**
     * No step of this list aborted. It looks at this list alone: an attempt that succeeded while one of the steps its
     * own commit triggered aborted satisfies it, and {@link #hasNoAbortedRecursively()} is what does not. The failure
     * names the position that aborted and carries its cause as the actual value.
     */
    public AutomaticStepsAssert hasNoAborted() {
        List<AutomaticStep> steps = Checks.subject(context, ABORTED, NONE, actual);
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index) instanceof AutomaticStep.Aborted aborted) {
                Checks.fail(context + "[" + index + "]", ABORTED, NONE, aborted.cause());
            }
        }
        return this;
    }

    /**
     * No step aborted anywhere in the tree this list roots: an attempt's outcome carries its own automatic steps, and
     * those carry theirs. The search is breadth first, so the abort nearest to the root is the one reported, and the
     * failure names the path that reaches it, such as {@code automatic steps[0].outcome.automaticSteps[1]}; a list that
     * was opened from another view starts the path with the name of that view. An outcome shared by several steps is
     * descended into once.
     */
    public AutomaticStepsAssert hasNoAbortedRecursively() {
        List<AutomaticStep> steps = Checks.subject(context, ABORTED, NONE, actual);
        Set<Outcome<?, ?, ?>> visited =
                Collections.newSetFromMap(new IdentityHashMap<Outcome<?, ?, ?>, Boolean>());
        Deque<Level> pending = new ArrayDeque<>();
        pending.addLast(new Level(steps, context));
        while (!pending.isEmpty()) {
            Level level = pending.removeFirst();
            for (int index = 0; index < level.steps().size(); index++) {
                String path = level.path() + "[" + index + "]";
                switch (level.steps().get(index)) {
                    case null -> { }
                    case AutomaticStep.Aborted aborted -> Checks.fail(path, ABORTED, NONE, aborted.cause());
                    case AutomaticStep.Done done -> {
                        if (visited.add(done.outcome())) {
                            pending.addLast(new Level(done.outcome().automaticSteps(),
                                    path + ".outcome.automaticSteps"));
                        }
                    }
                }
            }
        }
        return this;
    }

    /** Selects by key and by entity identity; an entity with no automatic steps yields an empty view, not a failure. */
    public <E, S extends Enum<S>, T extends Enum<T>> TypedAutomaticStepsAssert<E, S, T> forEntity(
            @NonNull MachineKey<E, S, T> key, @NonNull E entity) {
        List<AutomaticStep> steps = Checks.subject(context, "entity automatic steps", entity, actual);
        List<AutomaticStep> selected = steps.stream()
                .filter((AutomaticStep step) -> step != null && step.key().equals(key) && step.entity() == entity)
                .toList();
        return new TypedAutomaticStepsAssert<>(context + " for " + key, key, selected);
    }

    /** One level of the breadth-first walk of {@link #hasNoAbortedRecursively()}: a list and the path naming it. */
    private record Level(List<AutomaticStep> steps, String path) { }
}
