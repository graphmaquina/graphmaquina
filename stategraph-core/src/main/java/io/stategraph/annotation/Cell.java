package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A cell the engine reaches on its own (failure route, watch, cascade). ACTION with {@code by} and {@code action}; MOVE otherwise. Not an operation.
 *
 * @deprecated since 0.3: declare the graph with the vocabulary the StateGraph generator emits for the
 *     machine. This declaration is kept only so a project that has not been migrated is met with SG609
 *     and the typed declaration to write instead.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(Cells.class)
@Deprecated(since = "0.3")
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
