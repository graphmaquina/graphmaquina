package io.github.graphmaquina.buildlogic.docs

import java.io.File

/**
 * The pages the documentation check reads: the root `README.md` and every `*.md` under `docs/`, except the internal
 * records the root `.gitignore` ignores there. The exclusions are written out here rather than read from git, so the
 * check works in a checkout without `.git`; keep them in step with `.gitignore`.
 */
object DocsScope {

    private val EXCLUDED: List<Regex> = listOf(
        Regex("docs/private/.*"),
        Regex("docs/[^/]*-verification\\.md"),
        Regex("docs/audit-64-decisions\\.md"),
    )

    /** The pages in scope under [root], as sorted paths relative to it with `/` separators. */
    fun files(root: File): List<String> {
        val docs: List<String> = root.resolve("docs").walkTopDown()
            .filter { file: File -> file.isFile && file.name.endsWith(".md") }
            .map { file: File -> file.relativeTo(root).invariantSeparatorsPath }
            .filter { path: String -> EXCLUDED.none { pattern: Regex -> pattern.matches(path) } }
            .toList()
        val readme: List<String> = if (root.resolve("README.md").isFile) listOf("README.md") else listOf()
        return (readme + docs).sorted()
    }
}
