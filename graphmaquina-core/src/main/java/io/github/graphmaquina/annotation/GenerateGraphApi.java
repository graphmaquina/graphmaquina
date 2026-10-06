package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a {@code Machine<E, S, T>} interface for typed graph API generation. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GenerateGraphApi {}
