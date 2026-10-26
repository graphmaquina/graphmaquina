package desk;

import book.Bookings;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;
import pay.Payments;

/** A set of a third module: the bookings of book.jar and the payments of pay.jar, their child. */
@StateGraphs
public interface Desk extends Machines {
    Bookings bookings();
    Payments payments();
}
