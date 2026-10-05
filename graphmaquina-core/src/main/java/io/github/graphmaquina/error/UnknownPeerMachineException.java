package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;

/**
 * No machine of the registry has the requested key: {@code MachineRegistry.of(key)}, a context's {@code peer(key)}
 * or a cascade to that key. A watch on such a machine is refused earlier, when the registry is built.
 */
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
