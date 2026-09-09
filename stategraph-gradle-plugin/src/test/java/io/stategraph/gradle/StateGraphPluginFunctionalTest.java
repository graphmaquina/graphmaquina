package io.stategraph.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests proving the StateGraph Gradle plugin applies via TestKit without the root build being published.
 */
class StateGraphPluginFunctionalTest {

    @TempDir
    Path tempDirectory;

    @Test
    void appliesWithoutPublishingTheRootBuild() throws IOException {
        PluginFixture fixture = PluginFixture.applicationOnly(tempDirectory);
        BuildResult result = fixture.run("tasks", "--all", "--stacktrace");
        assertTrue(result.getOutput().contains("generateStateGraphApi"));
        assertTrue(result.getOutput().contains("generateTestStateGraphApi"));
    }
}
