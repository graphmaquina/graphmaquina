# GraphMaquina

[![Maven Central](https://img.shields.io/maven-central/v/io.github.graphmaquina/graphmaquina-core)](https://central.sonatype.com/artifact/io.github.graphmaquina/graphmaquina-core)

![GraphMaquina: typed graphs and predictable transitions, illustrated by a ticket that goes from OPEN through REVIEW to DONE, or from OPEN to FAILED through the failure route of SUBMIT.](docs/images/graphmaquina-overview.svg)

Typed, graph-based state machines for Java 21. Declare the transitions your domain allows as identified
edges, give every state a reason for refusing the rest, and let the build validate the whole graph before the
application runs.

Documentation: the [documentation index](docs/README.md), the [quickstart](docs/quickstart.md) and
**https://graphmaquina.dev**.

## License

GraphMaquina is licensed under Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

The code emitted into a consuming project by this project's generator and annotation processor belongs to
that project. The repository's license notice states that its license does not extend to that generated output.
