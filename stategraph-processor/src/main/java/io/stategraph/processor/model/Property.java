package io.stategraph.processor.model;

/**
 * A resolved property accessor: the name the generated code calls and the exact declaration behind it.
 *
 * <p>{@code method} stays the label of the accessor, which diagrams and messages already print; {@code
 * accessor} keeps the declaration that was selected, so a later emitter calls that member and not another one
 * that happens to share its name.</p>
 */
public record Property(String method, Binding accessor) { }
