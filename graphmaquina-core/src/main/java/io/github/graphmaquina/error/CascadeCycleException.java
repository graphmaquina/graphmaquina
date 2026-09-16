package io.github.graphmaquina.error;

import java.util.List;
import java.util.Objects;
import lombok.Getter;

/** Re-entry of an entity that is already in flight, whatever transition the nested call asks for. */
@Getter
public final class CascadeCycleException extends TransitionAbortedException {

    private final List<String> route;
    private final Enum<?> inFlightTransition;
    private final Enum<?> requestedTransition;

    public CascadeCycleException(AbortDetails details, List<String> route, Enum<?> inFlightTransition,
                                 Enum<?> requestedTransition) {
        super(details, "cascade cycle " + String.join(" → ", Objects.requireNonNull(route, "route"))
                + "; in flight running " + Objects.requireNonNull(inFlightTransition, "inFlightTransition")
                + " and requested " + Objects.requireNonNull(requestedTransition, "requestedTransition"));
        this.route = List.copyOf(route);
        this.inFlightTransition = inFlightTransition;
        this.requestedTransition = requestedTransition;
    }
}
