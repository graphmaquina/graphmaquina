package io.stategraph.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container of {@link Cell}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Cells {

    Cell[] value();
}
