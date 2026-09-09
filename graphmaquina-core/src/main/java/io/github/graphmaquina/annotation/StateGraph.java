package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a state machine on an interface that extends {@code Machine<E, S>}. {@code E} and {@code S} are
 * inferred from that supertype. The interface's abstract methods are the operations ({@link Action}, {@link Move});
 * type-level {@link Row}, {@link Cell}, {@link Forbid} and {@link Watch} complete the matrix. State names are the
 * constants of {@code S}; the processor rejects any other value at compile time.
 *
 * @deprecated since 0.3: declare the graph with the vocabulary the StateGraph generator emits for the
 *     machine. This declaration is kept only so a project that has not been migrated is met with SG609
 *     and the typed declaration to write instead.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Deprecated(since = "0.3")
public @interface StateGraph {

    String initial();

    String[] terminals() default {};

    String[] allowsCycle() default {};

    /** Property of the entity holding the state: resolved to p()/getP() and setP(S)/p(S). */
    String state() default "status";

    /** Property of the entity holding its identity: resolved to p()/getP(). */
    String id() default "id";
}
