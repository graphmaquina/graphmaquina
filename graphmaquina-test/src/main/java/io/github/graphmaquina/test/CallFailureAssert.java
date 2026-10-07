package io.github.graphmaquina.test;

import io.github.graphmaquina.error.CallFailedException;

/**
 * Chainable assertion over any failure of a call, obtained from {@link TransitionFailureAssert#isCallFailure()}:
 * the expectations every kind shares, for a test that cares where the call failed and how far it got rather than
 * which exception it threw.
 */
public final class CallFailureAssert extends BaseCallFailureAssert<CallFailureAssert> {

    CallFailureAssert(String context, CallFailedException failure) {
        super(context, failure);
    }

    @Override
    protected CallFailureAssert self() {
        return this;
    }
}
