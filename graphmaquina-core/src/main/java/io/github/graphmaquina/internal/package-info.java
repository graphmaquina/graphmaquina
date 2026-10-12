/**
 * Internal parts of the GraphMaquina core: the rules on failure kinds and throwable types, the flows that belong to no
 * call or that a call hands out once it ended, the bridge through which the runtime attaches a call's flow to the
 * failure the call throws, the way the library prints a value whose {@code toString()} the application wrote, and how
 * it keeps a thread's interrupt when it contains an {@code InterruptedException}.
 *
 * <p>This package is not part of the API and is not an application extension point.
 */
package io.github.graphmaquina.internal;
