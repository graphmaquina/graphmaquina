package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * A sub-machine started at its initial state because its parent entered the bound node. The start is logged right
 * after the hop that entered the node, before that hop's action runs, and is committed with it. It runs no hook. When
 * the initial state of the child is bound too, the child's own start follows, with this child as parent.
 *
 * @param key the machine of the child
 * @param child the child entity
 * @param previous the state of the child before the start: its pending state in the call, or else what its getter
 *     returned
 * @param initial the initial state of the child's graph, where the start put the child
 * @param parentKey the machine of the parent
 * @param parent the parent entity
 * @param node the parent's state that holds the binding
 * @param depth the depth of the step that entered the node, the root's step being at depth 0; a start that another
 *     start caused has that start's depth
 */
public record SubmachineStart<P, Q extends Enum<Q>, U extends Enum<U>>(
        @NonNull MachineKey<P, Q, U> key, @NonNull P child, @NonNull Q previous, @NonNull Q initial,
        @NonNull MachineKey<?, ?, ?> parentKey, @NonNull Object parent, @NonNull Enum<?> node, int depth) { }
