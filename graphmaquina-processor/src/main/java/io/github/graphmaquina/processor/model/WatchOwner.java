package io.github.graphmaquina.processor.model;

/**
 * How a watch finds the owner of a peer: through {@link Read}, a property of the peer's entity, or through
 * {@link Lookup}, an exported lookup that takes the peer's entity. A watch declares exactly one of them.
 */
public sealed interface WatchOwner permits WatchOwner.Read, WatchOwner.Lookup {

    /**
     * The owner read from {@code property}, a reader of the peer's entity. {@code optional} says whether it returns an
     * {@code Optional} of the owner, empty for a peer without one, rather than the owner itself.
     */
    record Read(Property property, boolean optional) implements WatchOwner { }

    /** The owner found by {@code binding}, an exported lookup that returns an {@code Optional} of it. */
    record Lookup(Binding binding) implements WatchOwner { }
}
