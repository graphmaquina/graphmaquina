package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container of {@link Watch}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Watches {

    Watch[] value();
}
