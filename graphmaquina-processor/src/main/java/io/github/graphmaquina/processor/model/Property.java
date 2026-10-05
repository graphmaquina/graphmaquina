package io.github.graphmaquina.processor.model;

/**
 * A resolved property accessor: the name the generated code calls and the exact declaration behind it.
 *
 * <p>{@code method} is the accessor's simple name: the companion of a watching machine calls the peers and owner
 * readers by it, with no argument. {@code accessor} keeps the declaration that was selected, as a member of the
 * entity: the set's companion calls the state and identity accessors through
 * {@link io.github.graphmaquina.processor.gen.BoundInvocation}, so that the state writer's argument is pinned when
 * another overload of that name could take it.</p>
 */
public record Property(String method, Binding accessor) { }
