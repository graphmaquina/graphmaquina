package io.github.graphmaquina.buildlogic.docs

import java.io.File
import java.io.IOException
import org.gradle.api.GradleException

/**
 * The pages the documentation check reads: every `*.md` file the repository versions, wherever it is. In a git
 * checkout git answers, with the files it tracks and the new ones no ignore rule covers, so a page is checked before
 * it is added and an ignored one, such as the benchmarks or the internal records under `docs/`, never is. A checkout
 * without `.git` is read as a source export, which holds only versioned files: every `*.md` outside the `build` and
 * `.gradle` output directories.
 */
object DocsScope {

    private val LIST_VERSIONED: List<String> =
        listOf("ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "*.md")
    private val OUTPUTS: Set<String> = setOf("build", ".gradle")

    /** The pages in scope under [root], as sorted paths relative to it with `/` separators, listed by [git]. */
    fun files(root: File, git: String = "git"): List<String> {
        val paths: List<String> = if (root.resolve(".git").exists()) versioned(root, git) else exported(root)
        // Git's index still lists a tracked file deleted from the working tree, and a conflicted file once per stage.
        return paths.filter { path: String -> root.resolve(path).isFile }.distinct().sorted()
    }

    private fun versioned(root: File, git: String): List<String> {
        val errors: File = File.createTempFile("git-ls-files", ".err")
        try {
            val process: Process = try {
                ProcessBuilder(listOf(git) + LIST_VERSIONED).directory(root).redirectError(errors).start()
            } catch (absent: IOException) {
                throw GradleException("The documentation check needs git on the PATH to list the versioned pages.")
            }
            val output: String = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val exit: Int = process.waitFor()
            if (exit != 0) {
                throw GradleException("git ls-files failed with exit $exit: ${errors.readText().trim()}")
            }
            return output.split('\u0000').filter { path: String -> path.isNotEmpty() }
        } finally {
            errors.delete()
        }
    }

    private fun exported(root: File): List<String> =
        root.walkTopDown()
            .onEnter { directory: File -> directory == root || directory.name !in OUTPUTS }
            .filter { file: File -> file.isFile && file.name.endsWith(".md") }
            .map { file: File -> file.relativeTo(root).invariantSeparatorsPath }
            .toList()
}
