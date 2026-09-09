package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reads the UnitOfWork first and the entity second: the only consistent view during a transaction.
 * The guard runs before every read, so a view captured in an action cannot outlive its transaction.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultPeerView<P, Q extends Enum<Q>> implements PeerView<P, Q> {

    private final Runnable guard;
    private final UnitOfWork uow;
    private final DefaultStateMachine<P, Q> machine;

    @Override
    public MachineKey<P, Q> key() { return machine.key(); }

    @Override
    public Q stateOf(@NonNull P peer) {
        guard.run();
        Optional<Enum<?>> pending = uow.pendingState(machine.refOf(peer));
        if (pending.isPresent()) {
            return machine.key().states().cast(pending.get());
        }
        return machine.readState(peer);
    }
}
