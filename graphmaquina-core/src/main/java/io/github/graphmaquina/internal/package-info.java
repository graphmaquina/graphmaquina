/**
 * Internal parts of the GraphMaquina core: the rules on failure kinds and throwable types, the flows that belong to no
 * call or that a call hands out once it ended, the bridge through which the runtime attaches a call's flow to the
 * failure the call throws, and the way the library prints a value whose {@code toString()} the application wrote.
 *
 * <p>This package is not part of the API and is not an application extension point.
 */
package io.github.graphmaquina.internal;
