package io.github.graphmaquina.buildlogic.docs

import java.io.File
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The whole check over a tree: every rule's violations in one sorted list, and the failure that lists them. */
class DocsCheckTest {

    @TempDir
    lateinit var root: File

    @BeforeEach
    fun requireLychee() {
        assumeTrue(Lychee.installedVersion("lychee")?.startsWith("0.24.") == true, "lychee 0.24 is not installed")
    }

    private fun write(path: String, text: String) {
        val file: File = root.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    @Test
    fun listsEveryViolationSorted() {
        write("README.md", "# R\n")
        write("docs/a.md", "# A\n" + "x\n".repeat(500))
        write("docs/b.md", "# B\n\n[x](a.md#nope)\n")
        val expected: List<String> = listOf(
            "docs/README.md:1: index is missing; it must link every page under docs/",
            "docs/a.md:1: file-size 501 lines, limit 500",
            "docs/a.md:1: section-size 501 lines, limit 150: A",
            "docs/b.md:3: links Cannot find fragment: docs/a.md#nope",
        )
        assertEquals(expected, DocsCheck.violations(root, "lychee"))
        val failure: GradleException = assertThrows(GradleException::class.java) { DocsCheck.verify(root) }
        assertEquals(
            listOf("Documentation check failed with 4 violations:") + expected + (
                "Split the page or section, add the contents list or the index link, or fix the heading or link. " +
                    "Do not raise a limit."
                ),
            (failure.message ?: "").split("\n"),
        )
    }

    @Test
    fun passesACleanTree() {
        write("README.md", "# R\n")
        write("docs/README.md", "# Docs\n\n- [A](a.md)\n- [B](b.md)\n")
        write("docs/a.md", "# A\n")
        write("docs/b.md", "# B\n\n[x](a.md#a)\n")
        assertEquals(listOf<String>(), DocsCheck.violations(root, "lychee"))
        DocsCheck.verify(root)
    }
}
