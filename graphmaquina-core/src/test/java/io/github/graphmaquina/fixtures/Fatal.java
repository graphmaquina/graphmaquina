package io.github.graphmaquina.fixtures;

/** An Error an action, a hook or the backend throws, which no call contains. */
public final class Fatal extends Error {

    public Fatal(String message) {
        super(message);
    }
}
