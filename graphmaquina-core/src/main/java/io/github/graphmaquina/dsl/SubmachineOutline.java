package io.github.graphmaquina.dsl;

import java.util.List;
import lombok.NonNull;

/**
 * The sub-machine bound to one state of a {@link MachineOutline}, by names. It holds the exits as declared, repeats and
 * states that are not terminal included, so that {@link MachineOutlineValidator} can report them.
 *
 * @param state the bound state of the parent
 * @param child the child machine's display name
 * @param childTerminals the terminal states of the child, in the child's declaration order
 * @param exits the declared exits, in declaration order
 */
public record SubmachineOutline(@NonNull String state, @NonNull String child, @NonNull List<String> childTerminals,
                                @NonNull List<ExitOutline> exits) {

    /** Copies both lists, keeping their order and their repeats. */
    public SubmachineOutline {
        childTerminals = List.copyOf(childTerminals);
        exits = List.copyOf(exits);
    }
}
