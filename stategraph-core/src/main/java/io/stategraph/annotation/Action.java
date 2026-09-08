package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An operation with code: the facade method moves the entity from any of {@code from} to {@code to} by running the action. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Action {

    String[] from();

    String to();

    /** Class hosting the action method, the guards (unless {@link #guardsBy}) and the after-commit hook. */
    Class<?> by();

    /** Name of the action method in {@link #by}; defaults to the operation's name. */
    String action() default "";

    String[] guards() default {};

    Class<?> guardsBy() default void.class;

    String onFailure() default "";

    Class<? extends Throwable>[] failsOn() default {};

    /** ISO-8601 duration, only for an asynchronous action. */
    String timeout() default "";

    String afterCommit() default "";
}
