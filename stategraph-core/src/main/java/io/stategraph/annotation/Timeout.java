package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes an asynchronous action timeout without losing nanosecond precision. */
@Target({})
@Retention(RetentionPolicy.CLASS)
public @interface Timeout {

    /** Whole seconds in the timeout. */
    long seconds() default 0;

    /** Nanosecond adjustment in the timeout. */
    int nanos() default 0;
}
