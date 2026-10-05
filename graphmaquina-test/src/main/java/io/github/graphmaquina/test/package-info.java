/**
 * Typed assertions, probes and graph snapshots for testing GraphMaquina state machines: no JUnit or
 * AssertJ dependency.
 *
 * <p>{@link io.github.graphmaquina.test.GraphAssertions} is the main entry point to the assertion views,
 * with the lists of transition records, reactions and journal entries keeping their own {@code assertThat}
 * factories; {@link io.github.graphmaquina.test.AsyncAssertions} turns an asynchronous transition into the
 * same views inside a budget the test owns; {@link io.github.graphmaquina.test.GraphGoldens} and
 * {@link io.github.graphmaquina.test.GraphSnapshots} compare a graph against a checked-in resource.
 * The {@code probe} package scripts guards, actions, hooks, the state setter and asynchronous callbacks.
 * Every unmet expectation raises {@link io.github.graphmaquina.test.GraphAssertionError}, a null subject
 * included; any other null argument raises {@code NullPointerException}, and invalid fixture configuration
 * raises {@code IllegalArgumentException} or {@code IllegalStateException}. A controlled asynchronous
 * fixture that is invoked after it was closed refuses instead: the action with a
 * {@code GraphAssertionError}, which no {@code failsOn} route of an edge can take for a failure of the
 * action, and the guard with an {@code IllegalStateException}, which aborts the transition.
 *
 * <p>The guide, with installation, what each entry point checks and what each check does not prove, is
 * {@code docs/testing.md} in the repository.
 */
package io.github.graphmaquina.test;
