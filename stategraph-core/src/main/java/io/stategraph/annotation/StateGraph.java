package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a state machine on an interface that extends {@code Machine<E, S>}. {@code E} and {@code S} are
 * inferred from that supertype. The interface's abstract methods are the operations ({@link Action}, {@link Move});
 * type-level {@link Row}, {@link Cell}, {@link Forbid} and {@link Watch} complete the matrix. State names are the
 * constants of {@code S}; the processor rejects any other value at compile time.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface StateGraph {

    String initial();

    String[] terminals() default {};

    String[] allowsCycle() default {};

    /** Property of the entity holding the state: resolved to p()/getP() and setP(S)/p(S). */
    String state() default "status";

    /** Property of the entity holding its identity: resolved to p()/getP(). */
    String id() default "id";
}
