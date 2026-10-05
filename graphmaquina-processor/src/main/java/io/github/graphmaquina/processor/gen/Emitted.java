package io.github.graphmaquina.processor.gen;

/**
 * The source of a generated companion, and what it writes at the head of its names: the processor checks
 * {@code written} against the companion's scope before it writes {@code source}.
 */
public record Emitted(String source, Written written) { }
