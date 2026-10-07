package io.github.graphmaquina.runtime;

import io.github.graphmaquina.error.CommitFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 2: pre-read of each entity and a single setter per entity with the to of its last entry. The pre-read
 * refuses a state that changed while the transaction ran, which an action that called the setter or a concurrent
 * transition on the same entity can cause; it is not a lock, so it catches only the interleavings that leave a
 * different state behind. A setter that throws any Exception, checked ones included, stops phase 2 where it stood.
 * It tells the transaction before each setter call, so that a failure during phase 2 is known to have written some
 * states or none.
 */
final class Commit {

    private Commit() { }

    /** Returns the summary it committed, so the root does not group the log a second time to dispatch watches. */
    static CommitSummary apply(MachineRegistry registry, Transaction tx) {
        UnitOfWork uow = tx.uow();
        CommitSummary summary = uow.commitSummary();
        List<CommitSummary.Change> changes = summary.changes();
        for (CommitSummary.Change change : changes) {
            DefaultStateMachine<?, ?, ?> machine = registry.anyMachineFor(change.ref().key());
            Optional<Enum<?>> current = machine.peekState(change.ref().instance());
            Enum<?> expected = change.first().from();
            if (current.isEmpty() || current.get() != expected) {
                throw new CommitFailedException(tx.call(), uow.journal(), instancesOf(changes), "the state of "
                        + change.ref() + " changed during the transaction: expected " + expected + " but is "
                        + current.map(Enum::name).orElse("null")
                        + "; an action called the setter, or a concurrent transition on the same entity wrote it first");
            }
        }
        for (int index = 0; index < changes.size(); index++) {
            CommitSummary.Change change = changes.get(index);
            DefaultStateMachine<?, ?, ?> machine = registry.anyMachineFor(change.ref().key());
            tx.writing();
            try {
                machine.writeState(change.ref().instance(), change.last().to());
            } catch (Exception e) {
                throw new CommitFailedException(tx.call(), uow.journal(), instancesOf(changes.subList(0, index)),
                        change.ref().instance(), instancesOf(changes.subList(index + 1, changes.size())), e);
            }
        }
        return summary;
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
