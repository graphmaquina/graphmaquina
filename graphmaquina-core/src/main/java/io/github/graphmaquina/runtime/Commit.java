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
 */
final class Commit {

    private Commit() { }

    /** Returns the summary it committed, so the root does not group the log a second time to dispatch watches. */
    static CommitSummary apply(MachineRegistry registry, UnitOfWork uow) {
        CommitSummary summary = uow.commitSummary();
        List<CommitSummary.Change> changes = summary.changes();
        List<Object> all = new ArrayList<>(changes.size());
        for (CommitSummary.Change change : changes) {
            all.add(change.ref().instance());
        }
        for (CommitSummary.Change change : changes) {
            DefaultStateMachine<?, ?, ?> machine = registry.anyMachineFor(change.ref().key());
            Optional<Enum<?>> current = machine.peekState(change.ref().instance());
            Enum<?> expected = change.first().from();
            if (current.isEmpty() || current.get() != expected) {
                throw new CommitFailedException(List.of(), all, "the state of " + change.ref()
                        + " changed during the transaction: expected " + expected + " but is "
                        + current.map(Enum::name).orElse("null")
                        + "; an action called the setter, or a concurrent transition on the same entity wrote it first");
            }
        }
        List<Object> applied = new ArrayList<>();
        for (CommitSummary.Change change : changes) {
            DefaultStateMachine<?, ?, ?> machine = registry.anyMachineFor(change.ref().key());
            try {
                machine.writeState(change.ref().instance(), change.last().to());
            } catch (Exception e) {
                throw new CommitFailedException(applied, all.subList(applied.size(), all.size()), e);
            }
            applied.add(change.ref().instance());
        }
        return summary;
    }
}
