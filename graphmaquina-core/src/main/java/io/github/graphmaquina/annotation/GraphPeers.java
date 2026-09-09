package io.github.graphmaquina.annotation;

import io.github.graphmaquina.api.Machine;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares peer machines for which typed watch vocabulary must be generated. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GraphPeers {

    /** Peer machine interfaces. */
    Class<? extends Machine<?, ?>>[] value();
}
