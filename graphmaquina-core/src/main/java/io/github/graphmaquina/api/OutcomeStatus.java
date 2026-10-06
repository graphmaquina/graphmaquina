package io.github.graphmaquina.api;

/**
 * The variant of an {@link Outcome} as a value: one constant per permitted variant, named after it. The names are
 * stable identifiers, safe to persist or serialize; a constant is added only together with a new variant.
 */
public enum OutcomeStatus { APPLIED, REJECTED, FAILED, IGNORED }
