package io.github.graphmaquina.api;

/**
 * Marker of a failure kind: a category of domain failure that an action names when it fails, with
 * {@link ActionOutcome#failed(Class, String)}. A kind is identified by its class, compared by identity, and the
 * library never instantiates it, so the canonical declaration is a one-line record, top-level or member:
 * {@code public record Unavailable() implements FailureKind { }}.
 *
 * <p>A valid kind is a named final class or record that implements this interface and is not an interface, an enum,
 * a local or anonymous class, or a Throwable; a final class with a private constructor qualifies as well. Kinds are
 * not registered anywhere and add no type parameter to a machine.
 */
public interface FailureKind { }
