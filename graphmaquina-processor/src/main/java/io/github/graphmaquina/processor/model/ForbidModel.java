package io.github.graphmaquina.processor.model;

/** A FORBID cell with its own reason. */
public record ForbidModel(String from, String to, String reason) { }
