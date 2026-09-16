package io.github.graphmaquina.api;

/** Why a request was rejected: no edge leaves the current state with that identifier, or a guard said no. */
public enum RejectionKind { NO_TRANSITION, GUARD }
