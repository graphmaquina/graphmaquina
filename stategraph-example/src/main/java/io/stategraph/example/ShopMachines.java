package io.stategraph.example;

import io.stategraph.annotation.StateGraphs;
import io.stategraph.api.Machines;
import io.stategraph.example.item.Items;
import io.stategraph.example.order.Orders;

/** The two machines, built together by the generated ShopMachines_. With Spring this would be one @Bean. */
@StateGraphs
public interface ShopMachines extends Machines {

    Orders orders();

    Items items();
}
