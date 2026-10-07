package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Chainable assertion over the journal of a failed call: the actions that actually ran, in the order they ran,
 * each with the status the engine left on it. The journal is not the list of committed records: an abort
 * commits nothing and still journals everything it started, and a call that failed during or after its commit
 * journals the actions whose effects happened whatever was written. So the two views answer different questions.
 * {@link ActionStatus#THREW} is reported as the engine wrote it and is never translated: the engine also marks
 * an action THREW when it was still in flight while an abort unwound, not only when the action itself threw.
 * The list is copied when the view is built, so later edits to the caller's collection are not seen. A null
 * element, which only a subject built by hand can hold, matches no entry, and an expectation that lists the
 * actions or the statuses shows it as null.
 */
public final class JournalAssert {

    private static final String ROOT = "journal";

    private final String context;
    private final List<ActionRecord> actual;

    /** The view of a journal that came from a failure view, which names it as that view's own. */
    JournalAssert(String context, List<ActionRecord> actual) {
        this.context = context;
        this.actual = actual == null ? null : Collections.unmodifiableList(new ArrayList<>(actual));
    }

    public static JournalAssert assertThat(List<ActionRecord> actual) {
        return new JournalAssert(ROOT, actual);
    }

    public JournalAssert hasSize(int expected) {
        List<ActionRecord> journal = Checks.subject(context, "size", expected, actual);
        Checks.equal(context, "size", expected, journal.size());
        return this;
    }

    public JournalAssert isEmpty() {
        List<ActionRecord> journal = Checks.subject(context, "size", 0, actual);
        Checks.equal(context, "size", 0, journal.size());
        return this;
    }

    /**
     * The action names in order and with their multiplicity: one entry per run, so an action that ran twice
     * appears twice.
     */
    public JournalAssert hasActionsExactly(String... expected) {
        List<String> wanted = List.of(expected);
        List<ActionRecord> journal = Checks.subject(context, "actions", wanted, actual);
        Checks.equal(context, "actions", wanted,
                journal.stream().map((ActionRecord entry) -> entry == null ? null : entry.action()).toList());
        return this;
    }

    public JournalAssert hasStatusesExactly(ActionStatus... expected) {
        List<ActionStatus> wanted = List.of(expected);
        List<ActionRecord> journal = Checks.subject(context, "statuses", wanted, actual);
        Checks.equal(context, "statuses", wanted,
                journal.stream().map((ActionRecord entry) -> entry == null ? null : entry.status()).toList());
        return this;
    }

    /**
     * Satisfied when some entry matches every argument at once, at any position. The entity is matched by
     * reference and everything else by value, so an entry that agrees on the edge, action, depth and status
     * but belongs to another instance does not satisfy it. The identifier is part of the match: two edges
     * that connect the same pair of states under different identifiers are different entries.
     */
    public <E, S extends Enum<S>, T extends Enum<T>> JournalAssert hasEntry(MachineKey<E, S, T> key, E entity,
            S from, T transition, S to, String action, int depth, ActionStatus status) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
        ActionRecord wanted = new ActionRecord(key, entity, from, transition, to, action, depth, status);
        List<ActionRecord> journal = Checks.subject(context, "entry", wanted, actual);
        boolean found = journal.stream().anyMatch((ActionRecord entry) -> entry != null
                && entry.key().equals(key) && entry.entity() == entity
                && entry.from() == from && entry.transition() == transition && entry.to() == to
                && entry.action().equals(action) && entry.depth() == depth && entry.status() == status);
        if (!found) {
            Checks.fail(context, "entry", wanted, journal);
        }
        return this;
    }
}
