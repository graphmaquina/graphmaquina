# Native images

GraalVM `native-image` compiled an application that uses GraphMaquina machines ahead of time without any reflection,
resource or initialization configuration, and the native executable ran them as the JVM does. This page says what was
built and run, and with which versions, so you can tell how far it covers your application.

## What was verified

A probe program, built with GraalVM CE 21.0.2 (`native-image` 21.0.2) against GraphMaquina 2.0.7 and again against
2.0.8, and the machines of this repository's example module, ran in the native executable:

- the generated sets of the profile and the shipment, the profile's with `recordTrail()`;
- the profile's chain that runs only on `advance(entity)`, a choice and a choice check of the shipment;
- `machine.async(...)` on its default executor, whose call ran on a virtual thread;
- a machine of the DSL, built with `StateGraph.define` and `StateMachines.from`, with an eligibility check and an
  advance.

The executable was built with:

```text
native-image --no-fallback -cp <probe classes>:<graphmaquina-core jar>:<example classes> -o native-probe probe.NativeProbe
```

No `reflect-config.json`, `resource-config.json`, `--initialize-at-build-time` or tracing agent was needed.

## What it does not cover

The probe used no framework. A framework that builds the set, such as the ones in
[dependency injection](dependency-injection.md), brings its own native-image requirements, which its documentation
describes. Other GraalVM releases, and machines whose actions or guards use reflection of their own, were not part of
the probe.

---

Next: [Documentation illustrations](images/README.md). Back to the [documentation index](README.md).
