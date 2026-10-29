package io.github.graphmaquina.example.onboarding;

/**
 * The error the example's controller answers a bad request with, its message the reason to show. The library depends
 * on no web framework, so the example declares its own; a framework's exception handler would turn it into a 400.
 */
public final class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
