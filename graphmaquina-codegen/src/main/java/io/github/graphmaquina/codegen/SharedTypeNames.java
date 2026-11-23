package io.github.graphmaquina.codegen;

import io.github.graphmaquina.api.AutomaticChain;

/**
 * The names of the library types that both the generator and the annotation processor write into the sources they
 * generate, declared once for both: the generator's vocabulary declares an element of such a type, and the processor's
 * companion names it in the graph it builds.
 */
public final class SharedTypeNames {

    /**
     * {@link AutomaticChain}: the type of the {@code automaticChain} element of a machine's {@code Definition}, and of
     * the setting the companion declares on the graph's builder.
     */
    public static final String AUTOMATIC_CHAIN = AutomaticChain.class.getName();

    private SharedTypeNames() {
    }
}
