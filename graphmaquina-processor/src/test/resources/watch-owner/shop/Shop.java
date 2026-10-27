package shop;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

/** The set that holds the orders and the items they watch. */
@StateGraphs
public interface Shop extends Machines {
    Orders orders();
    Items items();
}
