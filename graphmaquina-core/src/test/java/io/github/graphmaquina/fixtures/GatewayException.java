package io.github.graphmaquina.fixtures;

/** "Foreign" exception for failsOn. */
public final class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }
}
