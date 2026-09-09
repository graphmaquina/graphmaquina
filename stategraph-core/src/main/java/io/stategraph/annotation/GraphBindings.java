package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a concrete host whose inherited marked members are exported as graph bindings. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GraphBindings {}
