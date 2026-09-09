package io.stategraph.processor.model;

/** The two accessors a machine needs for its state: the reader that returns it and the writer that sets it. */
public record StateAccess(Property reader, Property writer) { }
