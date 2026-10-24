package io.github.graphmaquina.buildlogic.docs

import java.io.File

/** An ATX heading of a page: its 1-based line, its level (the number of `#` marks) and its text. */
data class Heading(val line: Int, val level: Int, val text: String)

/**
 * A Markdown page as the documentation check measures it: its path relative to the repository root, its lines as an
 * editor shows them, its size in UTF-8 bytes, and its ATX headings outside fenced code blocks. Setext headings are not
 * recognized.
 */
class DocPage(val path: String, val lines: List<String>, val bytes: Long) {

    val headings: List<Heading> = scanHeadings(lines)

    companion object {
        private val FENCE: Regex = Regex("^ {0,3}(`{3,}|~{3,})")
        private val HEADING: Regex = Regex("^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$")
        private val CLOSING_MARKS: Regex = Regex("(?:^|[ \t]+)#+$")

        /** Reads the page at [path], relative to [root] and written with `/` separators. */
        fun read(root: File, path: String): DocPage {
            val content: ByteArray = root.resolve(path).readBytes()
            val text: String = String(content, Charsets.UTF_8).replace("\r\n", "\n")
            val split: List<String> = if (text.isEmpty()) listOf() else text.split("\n")
            val lines: List<String> = if (text.endsWith("\n")) split.dropLast(1) else split
            return DocPage(path, lines, content.size.toLong())
        }

        private fun scanHeadings(lines: List<String>): List<Heading> {
            val headings: MutableList<Heading> = mutableListOf()
            var fence: String? = null
            lines.forEachIndexed { index: Int, line: String ->
                val marks: String? = FENCE.find(line)?.groupValues?.get(1)
                val open: String? = fence
                if (open == null && marks != null) {
                    fence = marks
                } else if (open != null) {
                    if (marks != null && closes(open, marks, line)) {
                        fence = null
                    }
                } else {
                    HEADING.matchEntire(line)?.let { match: MatchResult ->
                        val text: String = match.groupValues[2].replace(CLOSING_MARKS, "").trim()
                        headings.add(Heading(index + 1, match.groupValues[1].length, text))
                    }
                }
            }
            return headings
        }

        private fun closes(open: String, marks: String, line: String): Boolean =
            marks[0] == open[0] && marks.length >= open.length && line.trim() == marks
    }
}
