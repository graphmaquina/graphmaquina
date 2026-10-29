package io.github.graphmaquina.example.onboarding;

/** Verifies every address until {@link #refuse()} is called, and none after that. */
final class FakeAddressVerifier implements AddressVerifier {

    private boolean refusing;

    FakeAddressVerifier refuse() {
        refusing = true;
        return this;
    }

    @Override
    public boolean verifies(Account account) {
        return !refusing;
    }
}
