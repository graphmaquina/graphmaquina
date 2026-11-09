package io.github.graphmaquina.annotation.internal;

import lombok.NonNull;

/**
 * The receiver the {@code structure()} of a machine's companion calls an instance member of a collaborator on. That
 * method builds the machine's definition with no collaborator bound, so each action, guard, hook or lookup it declares
 * that calls an instance member of one asks this class for the receiver, which never answers: it throws
 * {@link IllegalStateException}, which names the collaborator and the companion, when the callable runs, never while
 * the definition is built, inspected or drawn.
 *
 * <p>This class is internal processor SPI: only the code the annotation processor generates calls it, and
 * {@link #STRUCTURE} is the one declaration of the method's name, which the processor writes and the generator's
 * export calls. Applications must not use it directly.
 */
public final class UnboundCollaborator {

    /** The name of the static method of a machine's companion that builds its definition with no collaborator. */
    public static final String STRUCTURE = "structure";

    private UnboundCollaborator() { }

    /**
     * Never returns: refuses to stand for {@code collaborator} in the definition {@code companion}'s
     * {@code structure()} built.
     *
     * @param collaborator the qualified name of the class whose instance member the callable calls
     * @param companion the qualified name of the companion whose {@code structure()} built the definition
     * @param <T> the type of the collaborator, which only lets the generated call compile as it does in
     *            {@code graph(...)}
     * @return nothing: it always throws
     * @throws IllegalStateException always, naming the collaborator and the companion
     */
    public static <T> T of(@NonNull String collaborator, @NonNull String companion) {
        throw new IllegalStateException(companion + "." + STRUCTURE + "() binds no " + collaborator
                + ": build a machine from " + companion + ".graph(...)");
    }
}
