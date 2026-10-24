package io.github.graphmaquina.buildlogic.docs

import java.io.File
import org.gradle.api.GradleException

/**
 * The documentation check the root build runs before every compilation: the lychee prerequisite first, then rules 1
 * to 5 and 7 on every page in [DocsScope], then the links. Every violation is listed before the build stops.
 */
object DocsCheck {

    private const val LYCHEE: String = "lychee"
    private const val FIX: String = "Split the page or section, add the contents list or the index link, or fix the " +
        "heading or link. Do not raise a limit."

    /** Every violation under [root], as `<path>:<line>: <rule> <detail>` lines sorted by path, line and rule. */
    fun violations(root: File, lychee: String): List<String> {
        Lychee.requireVersion(lychee)
        val scope: List<String> = DocsScope.files(root)
        val pages: List<DocPage> = scope.map { path: String -> DocPage.read(root, path) }
        val rules: List<Violation> = pages.flatMap { page: DocPage ->
            DocsRules.fileSize(page) + DocsRules.contents(page) + DocsRules.sections(page) + DocsRules.headings(page) +
                DocsRules.internalLinks(root, page)
        }
        val found: List<Violation> = rules + DocsRules.index(root, scope) + Lychee.brokenLinks(root, scope, lychee)
        return found.sortedWith(compareBy<Violation>({ it.path }, { it.line }, { it.rule })).map { it.toString() }
    }

    /** Stops the build, listing every violation under [root], unless there is none. */
    fun verify(root: File) {
        val found: List<String> = violations(root, LYCHEE)
        if (found.isNotEmpty()) {
            val lines: List<String> = listOf("Documentation check failed with ${found.size} violations:") + found + FIX
            throw GradleException(lines.joinToString("\n"))
        }
    }
}
