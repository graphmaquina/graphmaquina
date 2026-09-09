package io.github.graphmaquina.annotation.internal;

import io.github.graphmaquina.api.Machine;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes the machine and role of a generated vocabulary annotation.
 *
 * <p>This annotation is internal generator and processor SPI. Applications must not declare it directly.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GeneratedVocabulary {

    /** Machine for which the vocabulary was generated. */
    Class<? extends Machine<?, ?>> machine();

    /** Peer machines associated with pair-specific watch vocabulary. */
    Class<? extends Machine<?, ?>>[] peer() default {};

    /** Generated vocabulary role. */
    VocabularyKind value();
}
