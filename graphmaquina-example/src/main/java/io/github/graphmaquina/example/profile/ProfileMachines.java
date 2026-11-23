package io.github.graphmaquina.example.profile;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

/** The profile's machine, built by the generated ProfileMachinesRuntime. */
@StateGraphs
public interface ProfileMachines extends Machines {

    Profiles profiles();
}
