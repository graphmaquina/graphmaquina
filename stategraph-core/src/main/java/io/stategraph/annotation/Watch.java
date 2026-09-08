package io.stategraph.annotation;

import io.stategraph.api.Machine;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A watch of {@code state} over the machine {@code peer}. {@code peers} is a property of the entity returning
 * {@code Collection<? extends P>}; {@code owner} is a property of the peer returning {@code Optional<E>} or a nullable {@code E}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(Watches.class)
public @interface Watch {

    String state();

    Class<? extends Machine<?, ?>> peer();

    String peers();

    String owner();

    /** Class hosting the {@link When#condition} methods; required only when a rule uses one. */
    Class<?> by() default void.class;

    When[] rules();
}
