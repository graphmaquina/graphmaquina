# Documentation illustrations

The main README embeds these self-contained SVG images. They have a fixed dark background so labels and
connections retain their contrast in both GitHub themes. Each SVG contains a title and text description;
the README also supplies alternative text and keeps the detailed semantics in ordinary text and tables.

| Image | Purpose |
| --- | --- |
| [Overview](graphmaquina-overview.svg) | Project introduction and a compact ticket workflow |
| [Build pipeline](build-pipeline.svg) | Build-time generation, compilation, completion and runtime boundary |
| [Ticket workflow](ticket-workflow.svg) | Successful transition, handled failure and rejection |
| [Transition matrix](transition-matrix.svg) | All sixteen cells of the documented ticket machine |
| [Commit lifecycle](commit-lifecycle.svg) | Shared unit of work and the normal post-commit sequence |
| [Watch reactions](watch-reactions.svg) | Item commit followed by an independent owner reaction |

Regenerate from the repository root with Python 3; no third-party packages are required:

```bash
python3 scripts/render-docs-diagrams.py
git diff -- docs/images
```

Edit the generator rather than individual SVG files. It uses native SVG geometry and text, without embedded
bitmaps, scripts, external fonts or linked resources. It does not change the library's Mermaid exporter.
When editing, render the images and check labels, connector paths, small-width readability and correspondence
with the current implementation before committing.

The palette is shared: blue for movement/build input, teal for success, purple for orchestration, red for
failure/forbidden cells, and amber for post-commit work or cautions. Labels carry the meaning independently
of color. These are documentation illustrations; the graph definition and generated Mermaid remain the
programmatic sources for an application's actual graph.
