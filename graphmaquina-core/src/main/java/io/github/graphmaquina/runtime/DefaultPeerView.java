package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import java.util.Collection;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reads the UnitOfWork first and the entity second: the only consistent view during a transaction.
 * The guard runs before every read, so a view captured in an action cannot outlive its transaction. The helpers run it
 * first too, so they refuse even over an empty collection, which reads no state; noneIn reads through anyIn.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultPeerView<P, Q extends Enum<Q>, U extends Enum<U>> implements PeerView<P, Q, U> {

    private final Runnable guard;
    private final UnitOfWork uow;
    private final DefaultStateMachine<P, Q, U> machine;

    /** A watch is outside a transaction, but identity validation still precedes each committed-state read. */
    static <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> committed(
            DefaultStateMachine<P, Q, U> machine) {
        return new PeerView<>() {
            @Override
            public MachineKey<P, Q, U> key() { return machine.key(); }

            @Override
            public Q stateOf(@NonNull P peer) {
                // Preserve custom identity callbacks and their exceptions, even with no pending state to query.
                machine.refOf(peer);
                return machine.readState(peer);
            }
        };
    }

    @Override
    public MachineKey<P, Q, U> key() { return machine.key(); }

    @Override
    public Q stateOf(@NonNull P peer) {
        guard.run();
        Optional<Enum<?>> pending = uow.pendingState(machine.refOf(peer));
        if (pending.isPresent()) {
            return machine.key().states().cast(pending.get());
        }
        return machine.readState(peer);
    }

    @Override
    public boolean allIn(@NonNull Collection<? extends P> peers, @NonNull Q state) {
        guard.run();
        return PeerView.super.allIn(peers, state);
    }

    @Override
    public boolean anyIn(@NonNull Collection<? extends P> peers, @NonNull Q state) {
        guard.run();
        return PeerView.super.anyIn(peers, state);
    }
}
