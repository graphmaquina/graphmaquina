package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/**
 * Re-entry of an entity that is already in flight, whatever transition the nested call asks for. Its message names
 * the identifier in flight and the one requested by their {@code name()}.
 */
@Getter
public final class CascadeCycleException extends TransitionAbortedException {

    private final List<String> route;
    private final Enum<?> inFlightTransition;
    private final Enum<?> requestedTransition;

    public CascadeCycleException(MachineCall call, AbortDetails details, List<String> route,
                                 Enum<?> inFlightTransition, Enum<?> requestedTransition) {
        super(call, CallPhase.ACTION, details,
                "cascade cycle " + String.join(" → ", Objects.requireNonNull(route, "route"))
                + "; in flight running " + Objects.requireNonNull(inFlightTransition, "inFlightTransition").name()
                + " and requested " + Objects.requireNonNull(requestedTransition, "requestedTransition").name());
        this.route = List.copyOf(route);
        this.inFlightTransition = inFlightTransition;
        this.requestedTransition = requestedTransition;
    }
}
