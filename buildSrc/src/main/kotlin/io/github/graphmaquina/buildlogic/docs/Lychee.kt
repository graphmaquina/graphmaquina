package io.github.graphmaquina.buildlogic.docs

import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import org.gradle.api.GradleException

/**
 * Rule 6 of the documentation check, run by lychee: every relative link of a page resolves to a file and every anchor
 * to a heading, with GitHub's anchor rules. lychee is an external program that must be on the PATH at version 0.24;
 * the build never downloads it. Its JSON report, which names the page and line of each broken link, is rendered in the
 * check's own `<path>:<line>: links <reason>: <target>` shape.
 */
object Lychee {

    const val REQUIRED_MINOR: String = "0.24"

    private val VERSION: Regex = Regex("^lychee (\\d+\\.\\d+\\.\\d+)")
    private const val INSTALL: String = "Install it with 'cargo install lychee --locked --version 0.24.2', with " +
        "'brew install lychee' while Homebrew ships 0.24, or from " +
        "https://github.com/lycheeverse/lychee/releases/tag/lychee-v0.24.2."
    private const val LINKS_FAILED: Int = 2

    /** The version [executable] reports, or null when it cannot start or does not answer as lychee. */
    fun installedVersion(executable: String): String? {
        val process: Process = try {
            ProcessBuilder(executable, "--version").redirectErrorStream(true).start()
        } catch (missing: IOException) {
            return null
        }
        val output: String = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return VERSION.find(output.trim())?.groupValues?.get(1)
    }

    /** The version of [executable], which must be a lychee 0.24 release; otherwise the build stops and says why. */
    fun requireVersion(executable: String): String {
        val version: String = installedVersion(executable)
            ?: throw GradleException("The documentation check needs lychee $REQUIRED_MINOR on the PATH. $INSTALL")
        if (!version.startsWith("$REQUIRED_MINOR.")) {
            throw GradleException("The documentation check needs lychee $REQUIRED_MINOR, found $version. $INSTALL")
        }
        return version
    }

    /**
     * The broken links and anchors of [files], paths relative to [root], sorted by page, line and detail. lychee runs
     * in an empty directory of its own, so a `lychee.toml` or `.lycheeignore` in the repository cannot exclude links.
     */
    fun brokenLinks(root: File, files: List<String>, executable: String): List<Violation> {
        val workspace: File = Files.createTempDirectory("lychee").toFile()
        try {
            val errors: File = workspace.resolve("stderr")
            val command: List<String> = listOf(executable, "--offline", "--include-fragments=anchor-only",
                "--no-progress", "--format", "json") + files.map { path: String -> root.resolve(path).absolutePath }
            val process: Process = ProcessBuilder(command).directory(workspace).redirectError(errors).start()
            val report: String = process.inputStream.bufferedReader().readText()
            val exit: Int = process.waitFor()
            return when (exit) {
                0 -> listOf()
                LINKS_FAILED -> violations(root, report)
                else -> throw GradleException("lychee failed with exit $exit: ${errors.readText().trim()}")
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun violations(root: File, report: String): List<Violation> {
        val errorMap: Map<*, *> = (JsonSlurper().parseText(report) as Map<*, *>)["error_map"] as Map<*, *>
        return errorMap.flatMap { (source: Any?, entries: Any?) ->
            (entries as List<*>).map { entry: Any? ->
                val failure: Map<*, *> = entry as Map<*, *>
                val reason: String = (failure["status"] as Map<*, *>)["text"].toString()
                val line: Int = ((failure["span"] as Map<*, *>?)?.get("line") as Number?)?.toInt() ?: 1
                val target: String = target(root, failure["url"].toString())?.let { ": $it" } ?: ""
                Violation(relative(root, File(source.toString())), line, "links", reason + target)
            }
        }.sortedWith(compareBy<Violation>({ it.path }, { it.line }, { it.detail }))
    }

    /**
     * The link's target relative to [root], with its fragment; the URL itself when it is not a file; null when lychee
     * could not build one (a root-relative link reports `error:`), whose reason then says it all.
     */
    private fun target(root: File, url: String): String? {
        val uri: URI = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "file") {
            return url
        }
        val fragment: String = uri.rawFragment?.let { "#$it" } ?: ""
        return relative(root, File(uri.path)) + fragment
    }

    private fun relative(root: File, file: File): String =
        file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
}
