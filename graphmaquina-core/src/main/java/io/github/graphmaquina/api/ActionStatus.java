package io.github.graphmaquina.api;

/**
 * Status of an action in the journal: RUNNING while it runs, then OK or FAILED as its outcome said, or THREW when it
 * threw or a failure ended it. A journal the runtime hands out with an abort holds no RUNNING entry: building the
 * abort marks every action still running as THREW.
 */
public enum ActionStatus { RUNNING, OK, FAILED, THREW }
