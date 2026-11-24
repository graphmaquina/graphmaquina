# Dependency injection

A generated machine set is the unit an application manages: it is built once from the collaborators its machines need,
the instance hosts of their actions, guards and hooks, it hands out the facades, and it is closed once, when the
application stops, which closes the default executors of its machines. A container does exactly that; nothing in the
library depends on one.

## Contents

- [The set by hand](#the-set-by-hand)
- [Spring](#spring)
- [CDI](#cdi)
- [A clock for the whole set](#a-clock-for-the-whole-set)

## The set by hand

The [loan's wiring](../graphmaquina-example/src/main/java/io/github/graphmaquina/example/loan/LoanWiring.java) is the
whole recipe without a framework: the constructor builds the set from the reservations service its guards read, the
application asks for the facade, and the owner closes the set.

```java
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
```

Build the set once and hand the same facades to every caller. A set built for each request would create a default
executor for each of its machines and leave it open until that set is closed.

## Spring

A configuration declares the collaborators, the set with `close` as its destroy method, and the facade from the set:

```java
@Configuration
public class LoanConfiguration {

    @Bean
    public LoanGuards loanGuards(Reservations reservations) {
        return new LoanGuards(reservations);
    }

    @Bean(destroyMethod = "close")
    public LoanMachines loanMachines(LoanGuards guards) {
        return LoanMachinesRuntime.builder(guards).build();
    }

    @Bean
    public Loans loans(LoanMachines machines) {
        return machines.loans();
    }
}
```

This shape was verified with Spring Framework 7.1.0 and GraphMaquina 2.0.7 and 2.0.8, in a project outside this build
whose machine had an action host in place of the loan's guard host: the facade ran its operation, and once the context
closed, the set's default executor refused the facade's next asynchronous call.

## CDI

A producer makes the set and the facade application-scoped and closes the set in a disposer:

```java
@ApplicationScoped
public class LoanProducer {

    @Produces
    @ApplicationScoped
    public LoanMachines loanMachines(Reservations reservations) {
        return LoanMachinesRuntime.builder(new LoanGuards(reservations)).build();
    }

    public void closeLoanMachines(@Disposes LoanMachines machines) {
        machines.close();
    }

    @Produces
    @ApplicationScoped
    public Loans loans(LoanMachines machines) {
        return machines.loans();
    }
}
```

This shape was verified with Weld SE 7.0.0.Final and GraphMaquina 2.0.7 and 2.0.8, in the same project outside this
build: the facade ran its operation, and the disposer closed the set when the container shut down. The generated set and
facade are interfaces, so a normal scope can proxy them.

## A clock for the whole set

The set builder's `clock(InstantSource)` hands one clock to every machine of the set, which `ctx.now()` and the trail
read, as [guard evidence](guard-evidence.md) explains. A container can inject a clock the way it injects any
collaborator, and a test can pass a fixed one.

---

Next: [Compose machines](composition.md). Back to the [documentation index](README.md).
