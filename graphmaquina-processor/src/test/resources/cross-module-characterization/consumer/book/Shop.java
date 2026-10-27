package book;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;
import pay.Payments;

/** The set that holds the bookings and the payments the producer jar publishes, their child. */
@StateGraphs
public interface Shop extends Machines {
    Bookings bookings();
    Payments payments();
}
