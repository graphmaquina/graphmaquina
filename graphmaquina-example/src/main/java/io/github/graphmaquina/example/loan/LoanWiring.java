package io.github.graphmaquina.example.loan;

/**
 * The loan's machines wired by hand, as any container wires them: the set is built once from the collaborators it
 * needs, the application asks it for the facade, and whoever owns the set closes it once, when the application stops.
 */
public final class LoanWiring implements AutoCloseable {

    private final LoanMachines machines;

    public LoanWiring(Reservations reservations) {
        this.machines = LoanMachinesRuntime.builder(new LoanGuards(reservations)).build();
    }

    public Loans loans() {
        return machines.loans();
    }

    @Override
    public void close() {
        machines.close();
    }
}
