package io.github.graphmaquina.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.graphmaquina.gradle.PluginFixture.FIXTURE_REPOSITORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests of the export task through TestKit: it compiles the source set first, writes the diagram of every
 * machine as the exporter renders its companion's {@code structure()}, keeps up to date and reuses the configuration
 * cache, and skips a source set that declares no StateGraph processor.
 */
class ExportDotFunctionalTest {

    /** Where the export of the main source set writes the diagram of {@code fx.Orders}. */
    private static final String ORDERS_DIAGRAM = "build/graphmaquina/main/dot/fx.Orders.dot";

    /** A program of the fixture that writes the reference rendering of {@code fx.Orders} into the file it is given. */
    private static final String REFERENCE = """
            package fx;

            import io.github.graphmaquina.export.GraphView;
            import io.github.graphmaquina.export.GraphvizExporter;
            import java.io.IOException;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;

            /** Writes what the exporter renders for the structure of Orders. */
            public final class Reference {

                private Reference() {
                }

                public static void main(String[] arguments) throws IOException {
                    Files.writeString(Path.of(arguments[0]),
                            GraphvizExporter.render(GraphView.of(OrdersRuntime.structure())), StandardCharsets.UTF_8);
                }
            }
            """;

    /** The task that runs {@code fx.Reference} into {@code build/reference.dot}. */
    private static final String RUN_REFERENCE = """
            tasks.register<JavaExec>("reference") {
                classpath = sourceSets.getByName("main").runtimeClasspath
                mainClass = "fx.Reference"
                args(layout.buildDirectory.file("reference.dot").get().asFile.absolutePath)
            }
            """;

    @TempDir
    Path tempDirectory;

    @Test
    void exportStateGraphDotCompilesTheSourceSetAndWritesTheDiagramOfEveryMachine() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeFile("src/main/java/fx/Reference.java", REFERENCE);
        fixture.appendToBuildScript(RUN_REFERENCE);

        BuildResult result = fixture.run("exportStateGraphDot", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":exportStateGraphDot").getOutcome());
        Path diagram = fixture.projectDirectory().resolve(ORDERS_DIAGRAM);
        assertTrue(result.getOutput().contains(diagram.toRealPath().toString()), result.getOutput());
        fixture.run("reference");
        assertEquals(
                Files.readString(fixture.projectDirectory().resolve("build/reference.dot"), StandardCharsets.UTF_8),
                Files.readString(diagram, StandardCharsets.UTF_8));
    }

    @Test
    void anExportOfUnchangedClassesIsUpToDateAndReusesTheConfigurationCache() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);

        fixture.run("exportStateGraphDot", "--configuration-cache");
        BuildResult again = fixture.run("exportStateGraphDot", "--configuration-cache");

        assertTrue(again.getOutput().contains("Reusing configuration cache."), again.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":exportStateGraphDot").getOutcome());
    }

    @Test
    void aDiagramRemovedByHandIsWrittenAgain() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("exportStateGraphDot");
        Files.delete(fixture.projectDirectory().resolve(ORDERS_DIAGRAM));

        BuildResult again = fixture.run("exportStateGraphDot");

        assertEquals(TaskOutcome.SUCCESS, again.task(":exportStateGraphDot").getOutcome());
        assertTrue(Files.isRegularFile(fixture.projectDirectory().resolve(ORDERS_DIAGRAM)));
    }

    @Test
    void exportTestStateGraphDotWritesTheDiagramsOfTheTestSourceSet() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeTestSource("fx.Audits", PluginFixture.orders("Audits", "Approve", "approve"));

        BuildResult result = fixture.run("exportTestStateGraphDot");

        assertEquals(TaskOutcome.SUCCESS, result.task(":exportTestStateGraphDot").getOutcome());
        Path diagrams = fixture.projectDirectory().resolve("build/graphmaquina/test/dot");
        assertTrue(Files.isRegularFile(diagrams.resolve("fx.Audits.dot")), result.getOutput());
        assertFalse(Files.exists(diagrams.resolve("fx.Orders.dot")), result.getOutput());
    }

    @Test
    void theExportRequestFollowsTheWorkDirectoryOfTheGeneration() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<io.github.graphmaquina.gradle.GenerateGraphApi>("generateStateGraphApi") {
                    workDirectory = layout.buildDirectory.dir("moved-work")
                }
                """);

        fixture.run("exportStateGraphDot");

        assertTrue(Files.isRegularFile(fixture.projectDirectory().resolve("build/moved-work/export-request.json")));
        assertTrue(Files.isRegularFile(fixture.projectDirectory().resolve(ORDERS_DIAGRAM)));
    }

    @Test
    void aSourceSetWithoutTheProcessorSkipsItsExport() throws IOException {
        PluginFixture fixture = PluginFixture.plainJUnitTests(tempDirectory, FIXTURE_REPOSITORY);

        BuildResult result = fixture.run("exportTestStateGraphDot", "--info");

        assertEquals(TaskOutcome.SKIPPED, result.task(":exportTestStateGraphDot").getOutcome());
        assertTrue(result.getOutput().contains("Skipping task ':exportTestStateGraphDot' as task onlyIf 'the test"
                + " source set declares io.github.graphmaquina:graphmaquina-processor in testAnnotationProcessor'"
                + " is false."), result.getOutput());
        assertFalse(Files.exists(fixture.projectDirectory().resolve("build/graphmaquina/test/dot")));
    }

    @Test
    void theExportTasksAreListedInTheirGroup() throws IOException {
        PluginFixture fixture = PluginFixture.applicationOnly(tempDirectory);

        BuildResult result = fixture.run("tasks", "--group", "graphmaquina");

        assertTrue(result.getOutput().contains("exportStateGraphDot - Writes the Graphviz DOT of every StateGraph"
                + " machine of the 'main' source set."), result.getOutput());
        assertTrue(result.getOutput().contains("exportTestStateGraphDot"), result.getOutput());
    }
}
