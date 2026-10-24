# Documentation illustrations

The guide embeds these self-contained SVG images. They have a fixed dark background so labels and
connections retain their contrast in both GitHub themes. Each SVG contains a title and text description;
the guide also supplies alternative text and keeps the detailed semantics in ordinary text and tables. Besides
these images, the pages draw flow, sequence and class diagrams as Mermaid code blocks, which GitHub renders from
their text in the page.

| Image | Purpose |
| --- | --- |
| [Overview](graphmaquina-overview.svg) | Project introduction and a compact ticket workflow |
| [Build pipeline](build-pipeline.svg) | Build-time generation, compilation, completion and runtime boundary |
| [Ticket workflow](ticket-workflow.svg) | The quickstart's four states and six identified edges, including two parallel edges and a self-loop |
| [Operation decisions](operation-decisions.svg) | How one identifier selects one edge, and which outcome each path produces |
| [Commit lifecycle](commit-lifecycle.svg) | Shared unit of work and the normal post-commit sequence, up to the outcome returned with the call's frozen flow and its reactions |
| [Watch reactions](watch-reactions.svg) | Item commit followed by an independent owner reaction |
| [Sub-machine binding](submachine-binding.svg) | One payment machine bound in two states of a booking: the starts, the exits from the child's terminal states, an interruption from the state's border, and the three rules of a binding |
| [Annotation bootstrap](annotation-bootstrap.svg) | The first build: a generator reads the original Java source and writes the typed vocabulary, then javac compiles both together |
| [Async execution](async-execution.svg) | Which thread waits: a direct call waits on the caller's thread, while machine.async runs the whole operation on an executor and gives the caller a future |
| [Async future results](async-future-results.svg) | A future that completes normally can hold any outcome, a rejection included; technical and post-commit failures complete it exceptionally, and reactions need inspecting too |
| [Async deadlines](async-deadlines.svg) | How long an action's stage may be awaited: the root's budget, shared with cascades and sub-machine exits, caps every edge timeout |
| [Async late completion](async-late-completion.svg) | After a wait times out, the call aborts without committing, while the stage and the service it called can still finish and have external effects |
| [Async context boundary](async-context-boundary.svg) | Peers, cascades and the call's flow belong to the operation thread while its transaction is alive; a completion callback receives immutable data and translates the result |
| [Cascade versus watch](cascade-vs-watch.svg) | A cascade stages its changes in the root's unit of work, under the root's deadline; a watch reacts after the peer committed, in a new transaction that cannot undo that commit |
| [Call flow](call-flow.svg) | One call's flow, through the shop's cancellation: the steps' writes after their hops, the commit, the hooks, the frozen flow and trail the caller receives, a failure route's rollback and a COLLECT abort |

The images use native SVG geometry and text, without embedded bitmaps, scripts, external fonts or linked
resources. They do not change the library's Graphviz exporter.

The palette is shared: every image draws on the same dark background, text colors and five accents. Each
image gives its accents to its own groups of parts, and the same roles often recur: blue for requests,
movement or build input, teal for success, purple for orchestration, red for failures, aborts or what is not
allowed, and amber for refusals, post-commit work or cautions. Labels carry the meaning independently of
color. These are documentation illustrations. The graph definition is the structural contract for an
application's actual graph; exported Graphviz DOT is a visual projection of that definition.

---

Back to the [documentation index](../README.md).
