package io.github.graphmaquina.buildlogic.docs

import java.io.File
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The check reads every Markdown page the repository versions, wherever it is: in a git checkout what git tracks or
 * would track, and without `.git` every page outside the build outputs.
 */
class DocsScopeTest {

    @TempDir
    lateinit var root: File

    private fun write(vararg paths: String) {
        paths.forEach { path: String ->
            val file: File = root.resolve(path)
            file.parentFile.mkdirs()
            file.writeText("# X\n")
        }
    }

    private fun git(vararg arguments: String) {
        val process: Process = ProcessBuilder(listOf("git") + arguments).directory(root).inheritIO().start()
        assertEquals(0, process.waitFor())
    }

    @Test
    fun selectsThePagesGitVersionsOrWouldVersion() {
        git("init", "-q")
        root.resolve(".gitignore").writeText("build/\nignored/\ndocs/private/\n")
        write("README.md", "docs/a.md", "docs/images/README.md", "docs/x.svg", "module/README.md", "deleted.md")
        git("add", ".")
        write("ignored/forced.md")
        git("add", "-f", "ignored/forced.md")
        root.resolve("deleted.md").delete()
        write("new.md", "with space/page.md", "ignored/x.md", "module/build/out.md", "docs/private/p.md")
        assertEquals(
            listOf("README.md", "docs/a.md", "docs/images/README.md", "ignored/forced.md", "module/README.md", "new.md",
                "with space/page.md"),
            DocsScope.files(root),
        )
    }

    @Test
    fun readsACheckoutWithoutGitAsVersionedOutsideTheBuildOutputs() {
        write("README.md", "docs/a.md", "docs/x.svg", "other/z.md", "build/x.md", "module/build/y.md", ".gradle/z.md")
        assertEquals(listOf("README.md", "docs/a.md", "other/z.md"), DocsScope.files(root))
    }

    @Test
    fun stopsWhenGitIsMissingFromAGitCheckout() {
        root.resolve(".git").mkdir()
        val failure: GradleException =
            assertThrows(GradleException::class.java) { DocsScope.files(root, "git-that-is-not-installed") }
        assertEquals("The documentation check needs git on the PATH to list the versioned pages.", failure.message)
    }

    @Test
    fun stopsWhenGitCannotListThePages() {
        root.resolve(".git").writeText("not a git directory\n")
        val failure: GradleException = assertThrows(GradleException::class.java) { DocsScope.files(root) }
        assertTrue((failure.message ?: "").startsWith("git ls-files failed with exit 128: "), failure.message)
    }
}
