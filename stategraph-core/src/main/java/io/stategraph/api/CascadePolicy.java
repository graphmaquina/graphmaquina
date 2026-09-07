package io.stategraph.api;

/** Policy for handling failures when a transition cascades across multiple peers. */
public enum CascadePolicy { FAIL_FAST, COLLECT }
