package cons;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;
import pay.Payments;

/** A set that holds the machine a producer jar publishes, and declares no peer. */
@StateGraphs
public interface Shelf extends Machines {
    Payments payments();
}
