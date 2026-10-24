package io.github.graphmaquina.buildlogic.docs

import java.io.File
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The lychee prerequisite, checked against stub executables, and rule 6, checked against the real lychee when version
 * 0.24 is installed. Without it the documentation check itself stops at the prerequisite.
 */
class LycheeTest {

    @TempDir
    lateinit var temp: File

    private fun stub(name: String, script: String): String {
        val file: File = temp.resolve(name)
        file.writeText("#!/bin/sh\n$script\n")
        file.setExecutable(true)
        return file.absolutePath
    }

    private fun write(root: File, path: String, text: String) {
        val file: File = root.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun assumeLychee() {
        assumeTrue(Lychee.installedVersion("lychee")?.startsWith("0.24.") == true, "lychee 0.24 is not installed")
    }

    @Test
    fun missingExecutableNamesTheInstallCommands() {
        val failure: GradleException =
            assertThrows(GradleException::class.java) { Lychee.requireVersion(temp.resolve("absent").path) }
        assertEquals(
            "The documentation check needs lychee 0.24 on the PATH. Install it with " +
                "'cargo install lychee --locked --version 0.24.2', with 'brew install lychee' while Homebrew ships " +
                "0.24, or from https://github.com/lycheeverse/lychee/releases/tag/lychee-v0.24.2.",
            failure.message,
        )
    }

    @Test
    fun wrongVersionIsRefused() {
        val failure: GradleException = assertThrows(GradleException::class.java) {
            Lychee.requireVersion(stub("lychee", "echo 'lychee 0.23.1'"))
        }
        val message: String = failure.message ?: ""
        assertTrue(message.startsWith("The documentation check needs lychee 0.24, found 0.23.1."), message)
        assertTrue(message.contains("'cargo install lychee --locked --version 0.24.2'"), message)
    }

    @Test
    fun matchingVersionIsAccepted() {
        assertEquals("0.24.9", Lychee.requireVersion(stub("lychee", "echo 'lychee 0.24.9'")))
    }

    @Test
    fun unexpectedExitFails() {
        val executable: String = stub(
            "lychee",
            "if [ \"\$1\" = \"--version\" ]; then echo 'lychee 0.24.2'; exit 0; fi\necho boom >&2\nexit 1",
        )
        write(temp, "docs/a.md", "# A\n")
        val failure: GradleException = assertThrows(GradleException::class.java) {
            Lychee.brokenLinks(temp, listOf("docs/a.md"), executable)
        }
        val message: String = failure.message ?: ""
        assertTrue(message.contains("exit 1") && message.contains("boom"), message)
    }

    @Test
    fun acceptsGitHubAnchors() {
        assumeLychee()
        write(temp, "docs/a.md", listOf(
            "# A", "", "## Configure Gradle", "", "### settings.gradle.kts", "", "## The call's flow", "",
            "## Debug a call: logs and the call's flow", "", "## `TransitionOutcome` and its reasons", "",
            "## Limits", "", "text", "", "## Limits", "", "text", "", "## Processor output failures (SG599)", "",
        ).joinToString("\n"))
        write(temp, "docs/b.md", listOf(
            "# B", "", "## Local heading", "",
            "- [1](a.md#configure-gradle)", "- [2](a.md#settingsgradlekts)", "- [3](a.md#the-calls-flow)",
            "- [4](a.md#debug-a-call-logs-and-the-calls-flow)", "- [5](a.md#transitionoutcome-and-its-reasons)",
            "- [6](a.md#limits)", "- [7](a.md#limits-1)", "- [8](a.md#processor-output-failures-sg599)",
            "- [9](#local-heading)", "- [10](a.md)", "",
        ).joinToString("\n"))
        assertEquals(listOf<Violation>(), Lychee.brokenLinks(temp, listOf("docs/a.md", "docs/b.md"), "lychee"))
    }

    @Test
    fun reportsRootRelativeLinksInsteadOfFailing() {
        assumeLychee()
        write(temp, "docs/a.md", "# A\n")
        write(temp, "docs/b.md", "# B\n\n[x](/docs/a.md)\n")
        assertEquals(
            listOf(
                "docs/b.md:3: links Error building URL for \"/docs/a.md\" (Attribute: Some(\"href\")): " +
                    "Cannot resolve root-relative link '/docs/a.md'",
            ),
            Lychee.brokenLinks(temp, listOf("docs/a.md", "docs/b.md"), "lychee").map { it.toString() },
        )
    }

    @Test
    fun ignoresLycheeConfigurationInTheRoot() {
        assumeLychee()
        write(temp, ".lycheeignore", ".*\n")
        write(temp, "lychee.toml", "exclude = [\".*\"]\n")
        write(temp, "docs/a.md", "# A\n\n[x](missing.md)\n")
        assertEquals(
            listOf("docs/a.md:3: links File not found. Check if file exists and path is correct: docs/missing.md"),
            Lychee.brokenLinks(temp, listOf("docs/a.md"), "lychee").map { it.toString() },
        )
    }

    @Test
    fun reportsBrokenLinksRelativeToTheRoot() {
        assumeLychee()
        val root: File = temp.resolve("with space")
        write(root, "docs/a.md", "# A\n")
        write(root, "docs/b.md", "# B\n\n- [1](a.md#nope)\n- [2](missing.md)\n- [3](#local-nope)\n")
        assertEquals(
            listOf(
                "docs/b.md:3: links Cannot find fragment: docs/a.md#nope",
                "docs/b.md:4: links File not found. Check if file exists and path is correct: docs/missing.md",
                "docs/b.md:5: links Cannot find fragment: docs/b.md#local-nope",
            ),
            Lychee.brokenLinks(root, listOf("docs/a.md", "docs/b.md"), "lychee").map { it.toString() },
        )
    }
}
