package io.github.graphmaquina.error;

import java.util.List;
import java.util.Objects;
import lombok.Getter;

/** Re-entry of an entity already in flight toward a different target than the one it already has. */
@Getter
public final class CascadeCycleException extends TransitionAbortedException {

    private final List<String> route;
    private final Enum<?> inFlightTarget;
    private final Enum<?> requestedTarget;

    public CascadeCycleException(AbortDetails details, List<String> route, Enum<?> inFlightTarget, Enum<?> requestedTarget) {
        super(details, "cascade cycle " + String.join(" → ", Objects.requireNonNull(route, "route"))
                + "; in flight toward " + Objects.requireNonNull(inFlightTarget, "inFlightTarget")
                + " and requested " + Objects.requireNonNull(requestedTarget, "requestedTarget"));
        this.route = List.copyOf(route);
        this.inFlightTarget = inFlightTarget;
        this.requestedTarget = requestedTarget;
    }
}
