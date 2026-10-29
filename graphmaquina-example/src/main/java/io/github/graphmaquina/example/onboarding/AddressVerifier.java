package io.github.graphmaquina.example.onboarding;

/** Checks the address an account gave against a register of addresses; a real one calls a remote service. */
public interface AddressVerifier {

    /** Whether the register can verify the address of {@code account}. */
    boolean verifies(Account account);
}
