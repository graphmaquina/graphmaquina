package io.github.graphmaquina.internal;

import io.github.graphmaquina.api.FailureKind;

/** A failure kind declared as a top-level record, the canonical form. */
record TopLevelKind() implements FailureKind { }
