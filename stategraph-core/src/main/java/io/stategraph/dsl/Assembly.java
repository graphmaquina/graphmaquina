package io.stategraph.dsl;

import io.stategraph.api.EdgeSpec;
import io.stategraph.api.WatchSpec;
import java.util.Map;

/** Result of assembling the drafts. {@code complete} is true only when all N² cells exist and are valid. */
record Assembly<E, S extends Enum<S>>(Map<CellKey<S>, EdgeSpec<E, S>> specs,
                                      Map<S, WatchSpec<E, S, ?, ?>> watches,
                                      boolean complete) { }
