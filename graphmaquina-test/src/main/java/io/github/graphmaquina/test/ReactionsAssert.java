package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Chainable assertion over the watch reactions a committed outcome carries, obtained from {@link
 * BaseOutcomeAssert#reactions()} on any outcome view. A reaction belongs to the machine that re-evaluated or, when the
 * owner to re-evaluate could not be looked up, to the machine of the peer that moved, so the list is untyped as a whole
 * and {@link #forEntity(MachineKey, Object)} is what narrows it: it selects by machine key and by entity IDENTITY,
 * never by entity equality. An exception thrown by a reaction does not propagate to the commit that triggered it but is
 * reported as an aborted reaction, which is why {@link #hasNoAborted()} exists at all, and an {@link Error} is not
 * caught, so it never reaches this list; and because a reaction's own outcome carries reactions of its own, the direct
 * and the recursive check answer different questions. Order and multiplicity are part of the contract, and the list is
 * copied when the view is built, so later edits to the caller's collection are not seen. A null element, which only a
 * subject built by hand can hold, is neither an aborted reaction nor a source of nested ones, and no entity selects it.
 */
public final class ReactionsAssert {

    private static final String ROOT = "reactions";
    private static final String ABORTED = "aborted reaction";
    private static final String NONE = "none";

    private final String context;
    private final List<Reaction> actual;

    /** The view of a list that came from another view, which names it as that view's own. */
    ReactionsAssert(String context, List<? extends Reaction> actual) {
        this.context = context;
        this.actual = actual == null ? null : Collections.unmodifiableList(new ArrayList<>(actual));
    }

    public static ReactionsAssert assertThat(List<? extends Reaction> actual) {
        return new ReactionsAssert(ROOT, actual);
    }

    public ReactionsAssert hasSize(int expected) {
        List<Reaction> reactions = Checks.subject(context, "size", expected, actual);
        Checks.equal(context, "size", expected, reactions.size());
        return this;
    }

    public ReactionsAssert isEmpty() {
        List<Reaction> reactions = Checks.subject(context, "size", 0, actual);
        Checks.equal(context, "size", 0, reactions.size());
        return this;
    }

    /**
     * No reaction of this list aborted. It looks at this list alone: a reaction that succeeded while one of
     * its own descendants aborted satisfies it, and {@link #hasNoAbortedRecursively()} is what does not.
     * The failure names the position that aborted and carries its cause as the actual value.
     */
    public ReactionsAssert hasNoAborted() {
        List<Reaction> reactions = Checks.subject(context, ABORTED, NONE, actual);
        for (int index = 0; index < reactions.size(); index++) {
            if (reactions.get(index) instanceof Reaction.Aborted aborted) {
                Checks.fail(context + "[" + index + "]", ABORTED, NONE, aborted.cause());
            }
        }
        return this;
    }

    /**
     * No reaction aborted anywhere in the tree this list roots: a reaction's outcome carries its own
     * reactions, and those carry theirs. The search is breadth first, so the abort nearest to the root is
     * the one reported, and the failure names the path that reaches it, such as
     * {@code reactions[0].outcome.reactions[1]}; a list that was opened from another view starts the path
     * with the name of that view. An outcome shared by several reactions is descended into once.
     */
    public ReactionsAssert hasNoAbortedRecursively() {
        List<Reaction> reactions = Checks.subject(context, ABORTED, NONE, actual);
        Set<Outcome<?, ?, ?>> visited =
                Collections.newSetFromMap(new IdentityHashMap<Outcome<?, ?, ?>, Boolean>());
        Deque<Level> pending = new ArrayDeque<>();
        pending.addLast(new Level(reactions, context));
        while (!pending.isEmpty()) {
            Level level = pending.removeFirst();
            for (int index = 0; index < level.reactions().size(); index++) {
                String path = level.path() + "[" + index + "]";
                switch (level.reactions().get(index)) {
                    case null -> { }
                    case Reaction.Aborted aborted -> Checks.fail(path, ABORTED, NONE, aborted.cause());
                    case Reaction.Done done -> {
                        if (visited.add(done.outcome())) {
                            pending.addLast(new Level(nested(done), path + ".outcome.reactions"));
                        }
                    }
                }
            }
        }
        return this;
    }

    /** Selects by key and by entity identity; an entity with no reactions yields an empty view, not a failure. */
    public <E, S extends Enum<S>, T extends Enum<T>> TypedReactionsAssert<E, S, T> forEntity(
            MachineKey<E, S, T> key, E entity) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        List<Reaction> reactions = Checks.subject(context, "entity reactions", entity, actual);
        List<Reaction> selected = reactions.stream()
                .filter((Reaction reaction) ->
                        reaction != null && reaction.key().equals(key) && reaction.entity() == entity)
                .toList();
        return new TypedReactionsAssert<>(context + " for " + key, key, selected);
    }

    /** Only a committed outcome carries reactions of its own; an ignored or rejected one has none. */
    private static List<Reaction> nested(Reaction.Done done) {
        return done.outcome().reactions();
    }

    /** One level of the breadth-first walk of {@link #hasNoAbortedRecursively()}: a list and the path naming it. */
    private record Level(List<Reaction> reactions, String path) { }
}
