package app;

import book.Bookings;
import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;
import pay.Payments;
import shop.Orders;
import stock.Items;

/**
 * The application's set: the consumer's bookings and orders, and the payments and items of the producer's jar that
 * they bind and watch. A set that holds a parent holds its child, and a set that holds a watch holds its peers.
 */
@StateGraphs
public interface Store extends Machines {

    Bookings bookings();

    Payments payments();

    Orders orders();

    Items items();
}
