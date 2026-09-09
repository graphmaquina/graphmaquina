package io.stategraph.annotation.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Associates generated compilation metadata with its compilation identifier.
 *
 * <p>This annotation is internal generator and processor SPI. Applications must not declare it directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GraphCompilation {

    /** Compilation identifier emitted by the generation tool. */
    String value();
}
