package io.github.graphmaquina.example.onboarding;

import io.github.graphmaquina.api.FailureKind;

/** The failure kinds of the address check, each of which the graph can send to a state of its own. */
public final class AddressFailures {

    private AddressFailures() { }

    /** The verifier could not verify the address the account gave. */
    public record Unverifiable() implements FailureKind { }
}
