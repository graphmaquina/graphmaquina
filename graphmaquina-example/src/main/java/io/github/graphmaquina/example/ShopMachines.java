package io.github.graphmaquina.example;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;
import io.github.graphmaquina.example.item.Items;
import io.github.graphmaquina.example.order.Orders;

/** The two machines, built together by the generated ShopMachinesRuntime. With Spring this would be one @Bean. */
@StateGraphs
public interface ShopMachines extends Machines {

    Orders orders();

    Items items();
}
