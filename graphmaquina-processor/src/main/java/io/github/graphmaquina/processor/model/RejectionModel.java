package io.github.graphmaquina.processor.model;

/** One identifier a state refuses, with the reason that overrides its policy. */
public record RejectionModel(String from, String transition, String reason) { }
