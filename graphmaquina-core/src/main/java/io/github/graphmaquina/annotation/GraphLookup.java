package io.github.graphmaquina.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an exported lookup: of the parent of a sub-machine's child ({@code parentLookup}) or of the owner
 * of a watched peer ({@code ownerLookup}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface GraphLookup {}
