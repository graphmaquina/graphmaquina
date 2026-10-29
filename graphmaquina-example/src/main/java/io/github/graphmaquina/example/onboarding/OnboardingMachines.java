package io.github.graphmaquina.example.onboarding;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

/** The onboarding's machine, built by the generated OnboardingMachinesRuntime. With Spring this would be one @Bean. */
@StateGraphs
public interface OnboardingMachines extends Machines {

    Accounts accounts();
}
