package io.github.graphmaquina.processor.model;

import java.util.List;
import java.util.Optional;

/**
 * A sub-machine bound to one state of a machine, read from a {@code Submachine} of the vocabulary of a pair.
 *
 * <p>{@code child} is the machine the pair names as the peer, and {@code childTerminals} are the terminal states its
 * own {@code Definition} declares, in the order of its state enum. A child over another entity than the parent's is
 * reached through {@code childOf}, a property of the parent's entity that returns an {@code Optional} of the child's,
 * and leads back to its parent through exactly one of {@code parentOf}, a property of the child's entity that returns
 * an {@code Optional} of the parent's, or {@code parentLookup}, an exported lookup; a child over the parent's own
 * entity has none of the three. {@code exits} are the declared exits, in declaration order.</p>
 */
public record SubmachineModel(String state, MachineTypes child, List<String> childTerminals, Optional<Property> childOf,
                              Optional<Property> parentOf, Optional<Binding> parentLookup, List<Exit> exits) {

    /** One exit: the state of the child it is declared {@code on} and the identifier of the parent it executes. */
    public record Exit(String on, String transition) { }

    public SubmachineModel {
        childTerminals = List.copyOf(childTerminals);
        exits = List.copyOf(exits);
    }
}
