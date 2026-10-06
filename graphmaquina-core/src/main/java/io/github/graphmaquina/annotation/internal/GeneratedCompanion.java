package io.github.graphmaquina.annotation.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a companion the annotation processor generated for a machine or a set.
 *
 * <p>This annotation is internal processor SPI: only the annotation processor writes it, and the generator does not
 * use it. Applications must not declare it directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GeneratedCompanion {
}
