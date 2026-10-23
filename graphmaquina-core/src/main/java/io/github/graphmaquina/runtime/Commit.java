package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.internal.Interrupts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 2: pre-read of each entity and a single setter per entity with the to of its last entry. The pre-read refuses a
 * state that changed while the transaction ran, which an action that called the setter or a concurrent transition on
 * the same entity can cause; it is not a lock, so it catches only the interleavings that leave a different state
 * behind. A setter that throws any throwable that is not an Error, a checked exception included, stops phase 2 where it
 * stood. It tells the transaction before each setter call, so that a failure during phase 2 is known to have written
 * some states or none. Whatever throws before the first setter, the state read of the check or the grouping of the log,
 * stops phase 2 with nothing written. Once the last setter returned, it moves the call on to HOOK, so that whatever
 * fails from then on, before the hooks too, reports every state written. A call that logs at TRACE logs the check,
 * the conflict it found, if any, and each setter before it is called; those lines print what the log already holds and
 * call no getter or identity function. A retained action context cannot stage a cascade from a commit getter or
 * setter: it refuses the cascade before adding anything to the log, whose changes phase 2 already fixed. An
 * InterruptedException that stops phase 2 interrupts the thread again.
 */
final class Commit {

    private Commit() { }

    /** Returns the summary it committed, so the root does not group the log a second time to dispatch watches. */
    static CommitSummary apply(MachineRegistry registry, Transaction tx) {
        UnitOfWork uow = tx.uow();
        CommitSummary summary;
        Optional<String> conflict;
        try {
            summary = uow.commitSummary();
            conflict = conflict(registry, summary);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw new CommitFailedException(tx.call(), uow.journal(), plannedEntities(uow), e);
        }
        List<CommitSummary.Change> changes = summary.changes();
        CallLog log = tx.log();
        List<LogEntry> entries = log.trace() ? uow.entries() : List.of();
        Map<EntityRef, Integer> entryCounts = log.trace() ? entryCounts(entries, changes.size()) : Map.of();
        if (log.trace()) {
            log.trace("commit-check entities=" + changes.size() + " hops=" + hops(entries));
            if (conflict.isPresent()) {
                log.trace("commit-conflict reason=\"" + conflict.get() + "\"");
            }
        }
        if (conflict.isPresent()) {
            throw new CommitFailedException(tx.call(), uow.journal(), instancesOf(changes), conflict.get());
        }
        writeStates(registry, tx, changes, entryCounts);
        tx.enter(CallPhase.HOOK);
        return summary;
    }

    /**
     * Calls the setter of each of {@code changes}, in order, with the to of its last entry, telling the transaction
     * before each call; at TRACE each setter is logged first, with how many entries of the log, counted in
     * {@code entryCounts}, move its entity. A setter that throws stops the writes there.
     */
    private static void writeStates(MachineRegistry registry, Transaction tx, List<CommitSummary.Change> changes,
                                    Map<EntityRef, Integer> entryCounts) {
        UnitOfWork uow = tx.uow();
        CallLog log = tx.log();
        for (int index = 0; index < changes.size(); index++) {
            CommitSummary.Change change = changes.get(index);
            DefaultStateMachine<?, ?, ?> machine = registry.anyMachineFor(change.ref().key());
            if (log.trace()) {
                log.trace("setter " + CallLog.show(change.ref()) + " " + change.first().from().name() + " -> "
                        + change.last().to().name() + " entries=" + entryCounts.getOrDefault(change.ref(), 0));
            }
            tx.writing();
            try {
                machine.writeState(change.ref().instance(), change.last().to());
            } catch (Error fatal) {
                throw fatal;
            } catch (Throwable e) {
                Interrupts.keep(e);
                throw new CommitFailedException(tx.call(), uow.journal(), instancesOf(changes.subList(0, index)),
                        change.ref().instance(), instancesOf(changes.subList(index + 1, changes.size())), e);
            }
        }
    }

    /** The first entity whose state moved while the call ran, described; empty when every one is where it started. */
    private static Optional<String> conflict(MachineRegistry registry, CommitSummary summary) {
        for (CommitSummary.Change change : summary.changes()) {
            DefaultStateMachine<?, ?, ?> machine = registry.anyMachineFor(change.ref().key());
            Optional<Enum<?>> current = machine.peekState(change.ref().instance());
            Enum<?> expected = change.first().from();
            if (current.isEmpty() || current.get() != expected) {
                return Optional.of("the state of " + change.ref() + " changed during the transaction: expected "
                        + expected.name() + " but is " + current.map(Enum::name).orElse("null")
                        + "; an action called the setter, or a concurrent transition on the same entity wrote it"
                        + " first");
            }
        }
        return Optional.empty();
    }

    /**
     * Every entity the log moves, in order of first appearance, told apart by machine and by reference rather than by
     * the identities, whose equals or hashCode may be what failed, or by the entities' own: one identity set of the
     * entities seen per machine key, which compares by reference and hashes by {@link System#identityHashCode}.
     */
    private static List<Object> plannedEntities(UnitOfWork uow) {
        List<Object> entities = new ArrayList<>();
        Map<MachineKey<?, ?, ?>, Set<Object>> seen = new HashMap<>();
        for (LogEntry entry : uow.entries()) {
            Set<Object> instances = seen.computeIfAbsent(entry.ref().key(),
                    (MachineKey<?, ?, ?> key) -> Collections.newSetFromMap(new IdentityHashMap<>()));
            if (instances.add(entry.ref().instance())) {
                entities.add(entry.ref().instance());
            }
        }
        return entities;
    }

    /** How many of {@code entries} are hops; the starts of sub-machines are not. */
    private static int hops(List<LogEntry> entries) {
        int hops = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index) instanceof LogEntry.Hop) {
                hops++;
            }
        }
        return hops;
    }

    /**
     * How many of {@code entries} move each entity, in one pass: those that carry the very reference of the entity,
     * which the unit of work registered once per entity. The map, sized for the {@code entities} the log moves,
     * compares references and hashes them by {@link System#identityHashCode}, so no identity's equals or hashCode is
     * called.
     */
    private static Map<EntityRef, Integer> entryCounts(List<LogEntry> entries, int entities) {
        Map<EntityRef, Integer> counts = new IdentityHashMap<>(entities);
        for (int index = 0; index < entries.size(); index++) {
            counts.merge(entries.get(index).ref(), 1, Integer::sum);
        }
        return counts;
    }

    /** The entities of {@code changes}, in order. */
    private static List<Object> instancesOf(List<CommitSummary.Change> changes) {
        List<Object> instances = new ArrayList<>(changes.size());
        for (CommitSummary.Change change : changes) {
            instances.add(change.ref().instance());
        }
        return instances;
    }
}
