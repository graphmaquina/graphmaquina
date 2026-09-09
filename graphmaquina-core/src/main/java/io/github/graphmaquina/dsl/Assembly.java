package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.EdgeSpec;
import io.github.graphmaquina.api.WatchSpec;
import java.util.Map;

/** Result of assembling the drafts. {@code complete} is true only when all N² cells exist and are valid. */
record Assembly<E, S extends Enum<S>>(Map<CellKey<S>, EdgeSpec<E, S>> specs,
                                      Map<S, WatchSpec<E, S, ?, ?>> watches,
                                      boolean complete) { }
