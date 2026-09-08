package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An operation without code: the facade method moves the entity from any of {@code from} to {@code to}. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Move {

    String[] from();

    String to();

    /** Required only when {@link #guards} or {@link #afterCommit} are given. */
    Class<?> by() default void.class;

    String[] guards() default {};

    String afterCommit() default "";
}
