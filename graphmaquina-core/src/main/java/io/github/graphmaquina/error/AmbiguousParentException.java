package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/**
 * A sub-machine's entity was found active under two bindings at once: two parents hold it, each in its bound node or
 * leaving it. A parent that leaves one bound node for another that binds the same child is not two: it holds the
 * child only in the node it enters. The engine raises it while it checks the bindings of a child's step, and the
 * call's abort carries it as cause: in RESOLVE for the root's own step, in ACTION for a cascaded step or an exit, which
 * also poisons the transaction. The message names the child machine and both bindings by their parent machine and
 * the {@code name()} of their node; it never prints an entity.
 */
@Getter
public final class AmbiguousParentException extends StateMachineException {

    /** The machine of the child entity. */
    private final MachineKey<?, ?, ?> child;

    /** The parent machines of the two active bindings, in the order the child's machine lists its bindings. */
    private final List<MachineKey<?, ?, ?>> parents;

    /** The bound nodes of the two active bindings, in the same order as {@link #parents()}. */
    private final List<Enum<?>> nodes;

    public AmbiguousParentException(MachineKey<?, ?, ?> child, MachineKey<?, ?, ?> firstParent, Enum<?> firstNode,
                                    MachineKey<?, ?, ?> secondParent, Enum<?> secondNode) {
        super(Objects.requireNonNull(child, "child") + " is bound to two active parents: "
                + Objects.requireNonNull(firstParent, "firstParent") + " in "
                + Objects.requireNonNull(firstNode, "firstNode").name() + " and "
                + Objects.requireNonNull(secondParent, "secondParent") + " in "
                + Objects.requireNonNull(secondNode, "secondNode").name());
        this.child = child;
        this.parents = List.of(firstParent, secondParent);
        this.nodes = List.of(firstNode, secondNode);
    }
}
