package io.github.graphmaquina.dsl;

/** Identity of one matrix cell: the (from, to) pair of states. */
record CellKey<S extends Enum<S>>(S from, S to) { }
