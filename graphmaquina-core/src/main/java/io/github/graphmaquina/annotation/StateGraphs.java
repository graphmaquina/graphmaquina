package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a set of machines on an interface that extends {@code Machines}: one accessor per machine, a generated builder. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface StateGraphs {
}
