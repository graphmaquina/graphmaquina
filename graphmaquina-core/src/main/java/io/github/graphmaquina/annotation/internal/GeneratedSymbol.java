package io.github.graphmaquina.annotation.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records the declaration represented by a generated symbol type.
 *
 * <p>This annotation is internal generator and processor SPI. Applications must not declare it directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface GeneratedSymbol {

    /** Concrete host used to resolve and invoke the exported member. */
    Class<?> host();

    /** Name of the original exported member. */
    String member();

    /** Roles exported by the symbol family. */
    SymbolKind[] kinds();
}
