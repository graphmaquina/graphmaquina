package io.github.graphmaquina.buildlogic.docs

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Rules 1 to 5 of the documentation check, at their exact limits. */
class DocsRulesTest {

    @TempDir
    lateinit var root: File

    private fun page(text: String, path: String = "page.md"): DocPage {
        val file: File = root.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        return DocPage.read(root, path)
    }

    private fun lines(count: Int): String = (1..count).joinToString("") { "x\n" }

    private fun texts(violations: List<Violation>): List<String> = violations.map { it.toString() }

    @Test
    fun fileSizeAllowsTheLimits() {
        assertEquals(listOf<Violation>(), DocsRules.fileSize(page(lines(500))))
        assertEquals(listOf<Violation>(), DocsRules.fileSize(page("x".repeat(40_959) + "\n")))
    }

    @Test
    fun fileSizeReportsLinesAndBytes() {
        assertEquals(listOf("page.md:1: file-size 501 lines, limit 500"), texts(DocsRules.fileSize(page(lines(501)))))
        val heavy: DocPage = page(lines(399) + "x".repeat(40_961 - 798))
        assertEquals(400, heavy.lines.size)
        assertEquals(listOf("page.md:1: file-size 40961 bytes, limit 40960"), texts(DocsRules.fileSize(heavy)))
    }

    @Test
    fun fileSizeCountsUtf8Bytes() {
        val text: String = "x".repeat(40_958) + "—"
        assertEquals(40_959, text.length)
        assertEquals(listOf("page.md:1: file-size 40961 bytes, limit 40960"), texts(DocsRules.fileSize(page(text))))
    }

    @Test
    fun contentsAreRequiredOnlyAboveTheLimit() {
        val headed: String = "# T\n## A\n## B\n"
        assertEquals(listOf<Violation>(), DocsRules.contents(page(headed + lines(97))))
        assertEquals(
            listOf("page.md:1: contents list must link the 2 ## headings in order"),
            texts(DocsRules.contents(page(headed + lines(98)))),
        )
    }

    @Test
    fun contentsMustMatchTheHeadingsInOrder() {
        val body: String = "## A\n## B b\n" + lines(100)
        assertEquals(listOf<Violation>(), DocsRules.contents(page("# T\n- [A](#a)\n- [B b](#b-b)\n" + body)))
        assertEquals(
            listOf<Violation>(),
            DocsRules.contents(page("# T\n## Contents\n- [A](#a)\n  - [Sub](#sub)\n* [B b](#b-b)\n" + body)),
        )
        assertEquals(
            listOf<Violation>(),
            DocsRules.contents(page("# T\n- [A](#a)\n- [Elsewhere](other.md)\n- [B b](#b-b)\n" + body)),
        )
        val violation: List<String> = listOf("page.md:1: contents list must link the 2 ## headings in order")
        assertEquals(violation, texts(DocsRules.contents(page("# T\n- [A](#a)\n" + body))))
        assertEquals(violation, texts(DocsRules.contents(page("# T\n- [B b](#b-b)\n- [A](#a)\n" + body))))
        assertEquals(violation, texts(DocsRules.contents(page("# T\n- [A](#a)\n- [B](#b-b)\n" + body))))
    }

    @Test
    fun sectionsAllowTheLimit() {
        assertEquals(listOf<Violation>(), DocsRules.sections(page("# T\n" + lines(149))))
    }

    @Test
    fun sectionsReportTheLongOnes() {
        assertEquals(
            listOf("page.md:3: section-size 151 lines, limit 150: Big"),
            texts(DocsRules.sections(page("# T\n## A\n### Big\n" + lines(150) + "## After\n"))),
        )
        assertEquals(
            listOf("page.md:1: section-size 151 lines, limit 150: (before the first heading)"),
            texts(DocsRules.sections(page(lines(151) + "# T\n"))),
        )
        val fenced: String = "# T\n```\n" + lines(70) + "## not a heading\n" + lines(70) + "```\n" + "x\n".repeat(7)
        assertEquals(
            listOf("page.md:1: section-size 151 lines, limit 150: T"),
            texts(DocsRules.sections(page(fenced))),
        )
    }

    @Test
    fun headingsNeedOneLeadingTitle() {
        val message: String = "a page needs exactly one, as its first heading"
        assertEquals(listOf("page.md:1: headings found 0 # headings; $message"), texts(DocsRules.headings(page("x\n"))))
        assertEquals(
            listOf("page.md:1: headings found 2 # headings; $message"),
            texts(DocsRules.headings(page("# A\n# B\n"))),
        )
        assertEquals(
            listOf("page.md:1: headings found 1 # headings; $message"),
            texts(DocsRules.headings(page("## A\n# B\n"))),
        )
        assertEquals(listOf<Violation>(), DocsRules.headings(page("# A\n## B\n### C\n")))
        assertEquals(
            listOf("page.md:3: headings jumps from level 2 to 4"),
            texts(DocsRules.headings(page("# A\n## B\n#### C\n"))),
        )
    }

    @Test
    fun indexMustExistAndLinkEveryOtherPage() {
        val scope: List<String> = listOf("README.md", "docs/README.md", "docs/guide.md", "docs/images/README.md",
            "docs/testing.md", "module/README.md")
        assertEquals(
            listOf("docs/README.md:1: index is missing; it must link every page other than itself and README.md"),
            texts(DocsRules.index(root, scope)),
        )
        page("# Docs\n- [Guide](guide.md)\n- [Testing](./testing.md#x)\n- [Images](images/README.md)\n" +
            "- [Module](../module/README.md)\n", "docs/README.md")
        assertEquals(listOf<Violation>(), DocsRules.index(root, scope))
        page("# Docs\n- [Guide](guide.md)\n- [Images](images/README.md)\n- [Module](../module/README.md)\n",
            "docs/README.md")
        assertEquals(
            listOf("docs/README.md:1: index does not link docs/testing.md"),
            texts(DocsRules.index(root, scope)),
        )
    }
}
