package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineSpec;

/**
 * One binding in which a machine is the child: the key of the parent machine and the sub-machine that the parent's
 * definition declares on one of its states. The registry builds them when it is assembled and hands each machine the
 * list of its own.
 */
record SubmachineBinding<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>(
        MachineKey<E, S, T> parentKey, SubmachineSpec<E, S, T, P, Q, U> spec) { }
