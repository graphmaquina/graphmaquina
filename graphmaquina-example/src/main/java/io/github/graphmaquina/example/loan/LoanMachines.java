package io.github.graphmaquina.example.loan;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

/** The loan's machine, built by the generated LoanMachinesRuntime from a {@link LoanGuards}. */
@StateGraphs
public interface LoanMachines extends Machines {

    Loans loans();
}
