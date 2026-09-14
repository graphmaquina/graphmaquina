/**
 * Typed assertions, probes and graph snapshots for testing GraphMaquina state machines: no JUnit or
 * AssertJ dependency.
 *
 * <p>{@link io.github.graphmaquina.test.GraphAssertions} is the single entry point to the assertion
 * views; {@link io.github.graphmaquina.test.AsyncAssertions} turns an asynchronous transition into the
 * same views inside a budget the test owns; {@link io.github.graphmaquina.test.GraphGoldens} and
 * {@link io.github.graphmaquina.test.GraphSnapshots} compare a graph against a checked-in resource.
 * The {@code probe} package scripts guards, actions, hooks, the state setter and asynchronous callbacks.
 * Every unmet expectation raises {@link io.github.graphmaquina.test.GraphAssertionError}; invalid fixture
 * configuration raises {@code IllegalArgumentException} or {@code IllegalStateException}.
 *
 * <p>The guide, with installation, what each entry point checks and what each check does not prove, is
 * {@code docs/testing.md} in the repository.
 */
package io.github.graphmaquina.test;
