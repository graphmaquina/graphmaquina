package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a method as an exported graph action. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface GraphAction {}
