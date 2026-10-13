package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a concrete host whose inherited marked members are exported as graph bindings.
 *
 * <p>Generated code calls a static binding through the host, so a marked static method is exported only when the
 * host inherits it: never a static method of an interface, which no type inherits and which the interface's own
 * symbol exports, nor one that the host or a superclass between hides with a method of the same name and parameter
 * types. The hiding method is exported in its place when it is marked itself.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GraphBindings {}
