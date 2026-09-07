package io.stategraph.runtime;

import io.stategraph.api.TransitionRecord;
import io.stategraph.error.CommitFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Phase 2: pre-read of each entity and a single setter per entity with the to of its last entry. */
final class Commit {

    private Commit() { }

    static List<TransitionRecord<?, ?>> apply(MachineRegistry registry, UnitOfWork uow) {
        Map<EntityRef, List<LogEntry>> chains = uow.chains();
        List<Object> all = new ArrayList<>();
        for (EntityRef ref : chains.keySet()) {
            all.add(ref.instance());
        }
        for (Map.Entry<EntityRef, List<LogEntry>> entry : chains.entrySet()) {
            DefaultStateMachine<?, ?> machine = registry.anyMachineFor(entry.getKey().key());
            Optional<Enum<?>> current = machine.peekState(entry.getKey().instance());
            Enum<?> expected = entry.getValue().get(0).from();
            if (current.isEmpty() || current.get() != expected) {
                throw new CommitFailedException(List.of(), all, "the state of " + entry.getKey()
                        + " changed during the transaction: expected " + expected + " but is "
                        + current.map(Enum::name).orElse("null") + "; an action must not call the setter");
            }
        }
        List<Object> applied = new ArrayList<>();
        for (Map.Entry<EntityRef, List<LogEntry>> entry : chains.entrySet()) {
            DefaultStateMachine<?, ?> machine = registry.anyMachineFor(entry.getKey().key());
            List<LogEntry> chain = entry.getValue();
            Enum<?> last = chain.get(chain.size() - 1).to();
            try {
                machine.writeState(entry.getKey().instance(), last);
            } catch (RuntimeException e) {
                throw new CommitFailedException(applied, all.subList(applied.size(), all.size()), e);
            }
            applied.add(entry.getKey().instance());
        }
        return uow.records();
    }
}
