package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an exported post-commit hook: {@code void hook(E entity)} or {@code void hook(E entity, CommitContext<S> context)},
 * where the context is a {@link io.github.graphmaquina.api.CommitContext} and {@code E} and {@code S} are the
 * machine's entity and state types.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface GraphHook {}
