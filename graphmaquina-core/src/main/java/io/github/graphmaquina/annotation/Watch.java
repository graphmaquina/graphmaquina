package io.github.graphmaquina.annotation;

import io.github.graphmaquina.api.Machine;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A watch of {@code state} over the machine {@code peer}. {@code peers} is a property of the entity returning
 * {@code Collection<? extends P>}; {@code owner} is a property of the peer returning {@code Optional<E>} or a nullable {@code E}.
 *
 * @deprecated since 0.3: declare the graph with the vocabulary the StateGraph generator emits for the
 *     machine. This declaration is kept only so a project that has not been migrated is met with SG609
 *     and the typed declaration to write instead.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(Watches.class)
@Deprecated(since = "0.3")
public @interface Watch {

    String state();

    Class<? extends Machine<?, ?>> peer();

    String peers();

    String owner();

    /** Class hosting the {@link When#condition} methods; required only when a rule uses one. */
    Class<?> by() default void.class;

    When[] rules();
}
