package io.github.graphmaquina.buildlogic.docs

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The check reads the root README and the Markdown under docs/, never the internal records git ignores there. */
class DocsScopeTest {

    @TempDir
    lateinit var root: File

    @Test
    fun selectsTheReadmeAndPublicDocs() {
        listOf(
            "README.md",
            "docs/a.md",
            "docs/images/README.md",
            "docs/x.svg",
            "docs/private/p.md",
            "docs/y-verification.md",
            "docs/audit-64-decisions.md",
            "other/z.md",
        ).forEach { path: String ->
            val file: File = root.resolve(path)
            file.parentFile.mkdirs()
            file.writeText("# X\n")
        }
        assertEquals(listOf("README.md", "docs/a.md", "docs/images/README.md"), DocsScope.files(root))
    }
}
