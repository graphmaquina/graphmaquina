# GraphMaquina documentation

GraphMaquina builds typed, graph-based state machines for Java 21. You declare the transitions your domain allows
as identified edges, the build generates a typed vocabulary and validates the whole graph before the application
runs, and an in-process runtime executes each call against the entities you supply.

These pages document the latest GraphMaquina version, the API this repository builds. Read them in the order below the first
time; each page links the next one in that order.

## Contents

- [Start here](#start-here)
- [Model your domain](#model-your-domain)
- [Asynchronous work](#asynchronous-work)
- [Several machines](#several-machines)
- [After the commit](#after-the-commit)
- [Observe and debug](#observe-and-debug)
- [Test your machines](#test-your-machines)
- [Build tooling reference](#build-tooling-reference)
- [Find a feature](#find-a-feature)
- [Release signatures](#release-signatures)
- [Illustrations](#illustrations)

## Start here

| Page | What it covers |
| --- | --- |
| [Overview](guide.md) | What GraphMaquina provides, the visual guide, requirements, artifacts and known limitations |
| [Install and configure the build](installation.md) | Gradle and Maven configuration, Lombok, test source sets, multi-module builds |
| [Quickstart](quickstart.md) | A complete, runnable ticket machine and its first JUnit test |
| [Code generation and IDE setup](generation.md) | What the generator writes before javac runs, the first build and the IDE workflow |

## Model your domain

| Page | What it covers |
| --- | --- |
| [Graphs, annotations and bindings](graph-model.md) | Edges, identity, self-loops, guards, policies, structural rules, operations, markers, generated names, signatures |
| [Actions, guards and failure routes](actions-and-failures.md) | Action and guard contexts, operation input, domain failures, `failsOn`, recovery rules by failure kind |
| [Handle transition outcomes](outcomes.md) | The four outcome variants, status and code, records, journals, reactions and failed calls |
| [Programmatic DSL](dsl.md) | Building a graph and a machine without annotations |

## Asynchronous work

| Page | What it covers |
| --- | --- |
| [Asynchronous work and timeouts](async.md) | Asynchronous actions and guards, `machine.async`, a runnable async example, reading the future |
| [Deadlines, cancellation and executors](async-deadlines.md) | Root and edge deadlines, timeouts and late completion, contexts in callbacks, executors, async tests |

## Several machines

| Page | What it covers |
| --- | --- |
| [Machine sets, cascades and watches](coordination.md) | Generated machine sets, peer views, cascades in one unit of work, watches after a peer commits |
| [Compose machines](composition.md) | Sub-machines: binding a machine to a state, parent lookups, starts, exits, interruptions, reuse |

## After the commit

| Page | What it covers |
| --- | --- |
| [Commit, hooks, observers and persistence](commit-and-persistence.md) | The commit sequence, after-commit hooks, observers, state initialization, identity, database boundaries |

## Observe and debug

| Page | What it covers |
| --- | --- |
| [Debug a call with logs](logs.md) | The runtime's `System.Logger` output at `DEBUG` and `TRACE` |
| [The call's flow](call-flow.md) | Typed values every step of a call shares, values applied only on commit, and the call's trail |
| [Inspect graphs and export diagrams](graph-inspection.md) | Reading a definition, Graphviz export, topology queries |
| [Diagnostics and troubleshooting](diagnostics.md) | Every `SG` diagnostic code, runtime exceptions and common build problems |

## Test your machines

| Page | What it covers |
| --- | --- |
| [Testing GraphMaquina machines](testing.md) | `graphmaquina-test`: entry points, identity checks, goldens, snapshots, what a check does not prove |
| [Testing samples: graphs, outcomes, probes and async](testing-samples.md) | Verified samples for graph, outcome, failure, recorder, probe, input and controlled async assertions |
| [Testing samples: recovery, sub-machines and the call's flow](testing-advanced.md) | Verified samples for recovery rules, sub-machines and the flow and trail of a call |

## Build tooling reference

| Page | What it covers |
| --- | --- |
| [Building with the typed graph API](typed-annotations-build.md) | The four build steps, where artifacts come from, the Gradle adapter |
| [The Maven adapter](build-maven.md) | The six goals, the compiler execution they read, outputs, what the adapter requires and refuses |
| [Build troubleshooting and limits](build-troubleshooting.md) | Build failures, repeated builds and renames, generator limits |

## Find a feature

| I want to… | Read |
| --- | --- |
| Allow two different operations between the same pair of states | [Identity, not destination](graph-model.md#identity-not-destination) |
| Explain why a state refuses an operation | [Policies](graph-model.md#policies-every-node-answers) |
| Pass a value from the caller to guards and actions | [Operations and internal edges](graph-model.md#operations-and-internal-edges) |
| Turn an expected failure into a state | [Domain failures](actions-and-failures.md#domain-failures) |
| Send different failures to different states | [Recovery rules](actions-and-failures.md#recover-by-failure-kind-and-exception-type) |
| Map an outcome to an HTTP response | [Map an outcome to a response](outcomes.md#map-an-outcome-to-a-response) |
| Call a remote service from an action | [Asynchronous work](async.md) and [deadline rules](async-deadlines.md#deadline-rules) |
| Move several entities in one unit of work | [Cascade a transition](coordination.md#cascade-a-transition) |
| React when other machines change | [Watch other machines](coordination.md#watch-other-machines) |
| Run a whole machine inside one state | [Compose machines](composition.md) |
| Run code only after a transition committed | [After-commit hooks](commit-and-persistence.md#after-commit-hooks) |
| Count or audit every call | [Observers](commit-and-persistence.md#observers) |
| Share values between the steps of one call | [The call's flow](call-flow.md) |
| See what a call did, step by step | [Logs](logs.md) and [the call's trail](call-flow.md#record-the-calls-trail) |
| Draw the graph | [Inspect graphs and export diagrams](graph-inspection.md) |
| Build a graph at runtime, without annotations | [Programmatic DSL](dsl.md) |
| Pin a graph's structure in a test | [Goldens and structural snapshots](testing.md#goldens-and-structural-snapshots) |
| Decide in a test when an async callback answers | [Controlled asynchronous invocations](testing-samples.md#controlled-asynchronous-invocations) |
| Understand an `SG` error code | [Diagnostics](diagnostics.md) |

## Release signatures

| Page | What it covers |
| --- | --- |
| [Release signing](release-signing.md) | The signing key's fingerprint and how to verify a release's signature |

## Illustrations

| Page | What it covers |
| --- | --- |
| [Documentation illustrations](images/README.md) | The SVG illustrations the pages embed, and what each one shows |
