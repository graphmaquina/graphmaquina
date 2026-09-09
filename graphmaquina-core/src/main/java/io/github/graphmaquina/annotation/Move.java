package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An operation without code: the facade method moves the entity from any of {@code from} to {@code to}.
 *
 * @deprecated since 0.3: declare the graph with the vocabulary the StateGraph generator emits for the
 *     machine. This declaration is kept only so a project that has not been migrated is met with SG609
 *     and the typed declaration to write instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
@Deprecated(since = "0.3")
public @interface Move {

    String[] from();

    String to();

    /** Required only when {@link #guards} or {@link #afterCommit} are given. */
    Class<?> by() default void.class;

    String[] guards() default {};

    String afterCommit() default "";
}
