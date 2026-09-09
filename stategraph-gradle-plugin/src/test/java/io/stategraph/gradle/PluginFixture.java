package io.stategraph.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only helper that materializes minimal Gradle projects applying the StateGraph plugin and runs them through
 * TestKit. This is test-support code, not a production API.
 */
public final class PluginFixture {

    private final Path directory;

    private PluginFixture(Path directory) {
        this.directory = directory;
    }

    /**
     * Writes a settings file and a build script that applies only the {@code java} and {@code io.stategraph}
     * plugins, with no further configuration.
     *
     * @param directory the project directory to materialize the build into
     * @return a fixture bound to the written project
     * @throws IOException if the build files cannot be written
     */
    public static PluginFixture applicationOnly(Path directory) throws IOException {
        Files.writeString(directory.resolve("settings.gradle.kts"), """
                rootProject.name = "stategraph-plugin-fixture"
                """);
        Files.writeString(directory.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("io.stategraph")
                }
                """);
        return new PluginFixture(directory);
    }

    /**
     * Runs a build expected to succeed.
     *
     * @param arguments the Gradle command-line arguments
     * @return the build result
     */
    public BuildResult run(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    /**
     * Runs a build expected to fail.
     *
     * @param arguments the Gradle command-line arguments
     * @return the build result
     */
    public BuildResult runFailing(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(directory.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .buildAndFail();
    }

    /**
     * The fixture's project directory.
     *
     * @return the project directory
     */
    public Path projectDirectory() {
        return directory;
    }
}
