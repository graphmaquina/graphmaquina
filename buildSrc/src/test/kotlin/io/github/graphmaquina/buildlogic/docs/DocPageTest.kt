package io.github.graphmaquina.buildlogic.docs

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A page counts its lines as an editor shows them, its size in UTF-8 bytes, and its ATX headings outside fences. */
class DocPageTest {

    @TempDir
    lateinit var root: File

    private fun page(text: String): DocPage {
        root.resolve("page.md").writeText(text, Charsets.UTF_8)
        return DocPage.read(root, "page.md")
    }

    @Test
    fun countsLinesAsAnEditorShowsThem() {
        assertEquals(2, page("a\nb").lines.size)
        assertEquals(2, page("a\nb\n").lines.size)
        assertEquals(2, page("a\r\nb\r\n").lines.size)
        assertEquals(0, page("").lines.size)
    }

    @Test
    fun readsAtxHeadingsWithLevelAndText() {
        assertEquals(
            listOf(Heading(1, 1, "T"), Heading(3, 2, "A b"), Heading(4, 6, "Six")),
            page("# T\n\n## A b ##\n###### Six").headings,
        )
        assertEquals(listOf<Heading>(), page("#NoSpace\n####### Seven\n").headings)
    }

    @Test
    fun ignoresHeadingsInsideFences() {
        val text: String = listOf(
            "# T",
            "```",
            "## no",
            "```",
            "~~~",
            "## no",
            "~~~",
            "````",
            "```",
            "## no",
            "````",
            "## yes",
        ).joinToString("\n")
        assertEquals(listOf(Heading(1, 1, "T"), Heading(12, 2, "yes")), page(text).headings)
    }

    @Test
    fun measuresBytesInUtf8() {
        assertEquals(4L, page("—\n").bytes)
    }

    @Test
    fun keepsThePathItWasReadWith() {
        root.resolve("docs").mkdirs()
        root.resolve("docs/a.md").writeText("# A\n")
        assertEquals("docs/a.md", DocPage.read(root, "docs/a.md").path)
    }
}
