package io.github.graphmaquina.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the log of a root transaction amounts to per entity, read once between the last rollback and the first
 * domain write. One pass groups the entries by entity in order of first appearance and keeps only the two that
 * phase 2 and the watch dispatch need: the entry that says where the entity started and the one that says where
 * it ends. The whole log stays the source of records and hooks; this is a reading of it, not a second copy.
 */
final class CommitSummary {

    static final CommitSummary EMPTY = new CommitSummary(List.of());

    private final List<Change> changes;

    private CommitSummary(List<Change> changes) {
        this.changes = changes;
    }

    static CommitSummary from(List<LogEntry> log) {
        Map<EntityRef, Change> grouped = new LinkedHashMap<>();
        for (LogEntry entry : log) {
            Change previous = grouped.get(entry.ref());
            grouped.put(entry.ref(), previous == null
                    ? new Change(entry.ref(), entry, entry)
                    : new Change(previous.ref(), previous.first(), entry));
        }
        return new CommitSummary(List.copyOf(grouped.values()));
    }

    List<Change> changes() { return changes; }

    /**
     * Entities whose final state differs from the initial one, in order of first appearance. The comparison is by
     * reference, as it has always been: {@code TransitionSpec} declares both states {@code @NonNull}, and one
     * entity's entries carry constants of its own state enum, so reference identity is the enum equality the base
     * compared these two states by.
     */
    List<EntityRef> movedEntities() {
        List<EntityRef> moved = new ArrayList<>();
        for (Change change : changes) {
            if (change.first().from() != change.last().to()) {
                moved.add(change.ref());
            }
        }
        return List.copyOf(moved);
    }

    /**
     * One entity of the transaction: the reference the commit writes through, which is the one its first entry
     * carried, plus that first entry and the last one. Everything in between only matters to records and hooks.
     */
    record Change(EntityRef ref, LogEntry first, LogEntry last) { }
}
