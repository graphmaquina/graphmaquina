package io.github.graphmaquina.buildlogic.docs

import java.io.File

/**
 * The documentation check's limits, all in one place. A page holds at most
 * 500 lines; 40 960 bytes is about 10 000 tokens at 4 characters per token; a page over 100 lines needs a
 * contents list so a partial read still shows its scope; 150 lines keeps a section readable in one read.
 */
object DocsLimits {
    const val MAX_LINES: Int = 500
    const val MAX_BYTES: Long = 40_960
    const val CONTENTS_FROM_LINES: Int = 100
    const val MAX_SECTION_LINES: Int = 150
    const val INDEX: String = "docs/README.md"
}

/** One broken rule: the page, the 1-based line it points at, the rule's id and what was measured. */
data class Violation(val path: String, val line: Int, val rule: String, val detail: String) {
    override fun toString(): String = "$path:$line: $rule $detail"
}

/** Rules 1 to 5 of the documentation check: file size, contents list, section size, headings and the index. */
object DocsRules {

    private val TOP_LEVEL_ENTRY: Regex = Regex("^[-*+][ \t]+\\[([^\\]]+)\\]\\(#[^)]*\\)")
    private val INLINE_LINK: Regex = Regex("\\]\\(([^)\\s]+)")
    private val URL_SCHEME: Regex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private const val CONTENTS: String = "Contents"

    /** Rule 1: at most [DocsLimits.MAX_LINES] lines and at most [DocsLimits.MAX_BYTES] bytes. */
    fun fileSize(page: DocPage): List<Violation> {
        val violations: MutableList<Violation> = mutableListOf()
        val lines: Int = page.lines.size
        if (lines > DocsLimits.MAX_LINES) {
            violations.add(Violation(page.path, 1, "file-size", "$lines lines, limit ${DocsLimits.MAX_LINES}"))
        }
        if (page.bytes > DocsLimits.MAX_BYTES) {
            violations.add(Violation(page.path, 1, "file-size", "${page.bytes} bytes, limit ${DocsLimits.MAX_BYTES}"))
        }
        return violations
    }

    /**
     * Rule 2: a page over [DocsLimits.CONTENTS_FROM_LINES] lines lists its `##` headings, in order and with their own
     * text, in the unindented `#`-anchor entries of a list before its first `##` heading other than `## Contents`.
     * Entries that link elsewhere, and nested entries, are not compared.
     */
    fun contents(page: DocPage): List<Violation> {
        if (page.lines.size <= DocsLimits.CONTENTS_FROM_LINES) {
            return listOf()
        }
        val sections: List<Heading> = page.headings.filter { it.level == 2 && it.text != CONTENTS }
        val end: Int = sections.firstOrNull()?.line ?: (page.lines.size + 1)
        val entries: List<String> = page.lines.subList(0, end - 1)
            .mapNotNull { line: String -> TOP_LEVEL_ENTRY.find(line)?.groupValues?.get(1) }
        if (entries == sections.map { it.text }) {
            return listOf()
        }
        return listOf(Violation(page.path, 1, "contents", "list must link the ${sections.size} ## headings in order"))
    }

    /**
     * Rule 3: a section, from a heading of any level to the line before the next one, runs at most
     * [DocsLimits.MAX_SECTION_LINES] lines, code blocks and tables included. Lines before the first heading form a
     * section too.
     */
    fun sections(page: DocPage): List<Violation> {
        if (page.lines.isEmpty()) {
            return listOf()
        }
        val starts: List<Pair<Int, String>> =
            (if (page.headings.firstOrNull()?.line == 1) listOf() else listOf(1 to "(before the first heading)")) +
                page.headings.map { it.line to it.text }
        val ends: List<Int> = starts.drop(1).map { it.first } + (page.lines.size + 1)
        return starts.zip(ends).mapNotNull { (start: Pair<Int, String>, end: Int) ->
            val length: Int = end - start.first
            if (length > DocsLimits.MAX_SECTION_LINES) {
                Violation(page.path, start.first, "section-size",
                    "$length lines, limit ${DocsLimits.MAX_SECTION_LINES}: ${start.second}")
            } else {
                null
            }
        }
    }

    /** Rule 5: exactly one `#` heading, as the page's first, and no heading two levels below the one before it. */
    fun headings(page: DocPage): List<Violation> {
        val violations: MutableList<Violation> = mutableListOf()
        val titles: Int = page.headings.count { it.level == 1 }
        if (titles != 1 || page.headings.first().level != 1) {
            violations.add(Violation(page.path, page.headings.firstOrNull()?.line ?: 1, "headings",
                "found $titles # headings; a page needs exactly one, as its first heading"))
        }
        page.headings.zipWithNext().forEach { (previous: Heading, next: Heading) ->
            if (next.level > previous.level + 1) {
                violations.add(Violation(page.path, next.line, "headings",
                    "jumps from level ${previous.level} to ${next.level}"))
            }
        }
        return violations
    }

    /** Rule 4: [DocsLimits.INDEX] exists and links every page of [scope] under `docs/` directly. */
    fun index(root: File, scope: List<String>): List<Violation> {
        val index: File = root.resolve(DocsLimits.INDEX)
        if (!index.isFile) {
            return listOf(Violation(DocsLimits.INDEX, 1, "index", "is missing; it must link every page under docs/"))
        }
        val base: File = index.parentFile
        val linked: Set<String> = INLINE_LINK.findAll(index.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .filter { target: String -> !target.startsWith("#") && !URL_SCHEME.containsMatchIn(target) }
            .map { target: String -> base.resolve(target.substringBefore('#')).normalize() }
            .map { file: File -> file.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        return scope
            .filter { path: String -> path != "README.md" && path != DocsLimits.INDEX && path !in linked }
            .map { path: String -> Violation(DocsLimits.INDEX, 1, "index", "does not link $path") }
    }
}
