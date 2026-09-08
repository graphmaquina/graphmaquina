package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A cell the engine reaches on its own (failure route, watch, cascade). ACTION with {@code by} and {@code action}; MOVE otherwise. Not an operation. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(Cells.class)
public @interface Cell {

    String[] from();

    String to();

    Class<?> by() default void.class;

    String action() default "";

    String[] guards() default {};

    Class<?> guardsBy() default void.class;

    String onFailure() default "";

    Class<? extends Throwable>[] failsOn() default {};

    String timeout() default "";

    String afterCommit() default "";
}
