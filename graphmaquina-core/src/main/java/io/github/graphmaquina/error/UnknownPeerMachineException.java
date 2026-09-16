package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;

/** A watch or cascade referenced a peer machine that was never registered. */
@Getter
public final class UnknownPeerMachineException extends StateMachineException {

    private final MachineKey<?, ?, ?> requested;
    private final Set<MachineKey<?, ?, ?>> registered;

    public UnknownPeerMachineException(MachineKey<?, ?, ?> requested, Collection<MachineKey<?, ?, ?>> registered) {
        super("no machine is registered for " + Objects.requireNonNull(requested, "requested")
                + "; registered: " + Objects.requireNonNull(registered, "registered"));
        this.requested = requested;
        this.registered = Set.copyOf(registered);
    }
}
