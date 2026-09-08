package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A FORBID cell with its own reason, different from the row's. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Repeatable(Forbids.class)
public @interface Forbid {

    String from();

    String to();

    String reason();
}
