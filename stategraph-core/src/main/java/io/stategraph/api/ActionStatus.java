package io.stategraph.api;

/** Final status of an executed action, as recorded in the journal. */
public enum ActionStatus { RUNNING, OK, FAILED, THREW }
