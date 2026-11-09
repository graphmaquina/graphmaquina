package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.graphmaquina.maven.MavenFixture.requireProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two export goals, run by a real {@code mvn} under the goal prefix a user types: each forks the lifecycle up to
 * its compilation, so it generates, compiles and completes that compilation first, then writes the diagram of every
 * machine it published as the exporter renders its companion's {@code structure()}. A project that compiles nothing
 * and a module that publishes no index are skipped.
 */
class MavenExportDotTest {

    /** Where {@code export-dot} writes the diagram of {@code fx.Orders}. */
    private static final String ORDERS_DIAGRAM = "target/graphmaquina/main/dot/fx.Orders.dot";

    /** A program of the fixture that prints the reference rendering of {@code fx.Orders}. */
    private static final String REFERENCE = """
            package fx;

            import io.github.graphmaquina.export.GraphView;
            import io.github.graphmaquina.export.GraphvizExporter;

            /** Prints what the exporter renders for the structure of Orders. */
            public final class Reference {

                private Reference() {
                }

                public static void main(String[] arguments) {
                    System.out.print(GraphvizExporter.render(GraphView.of(OrdersRuntime.structure())));
                }
            }
            """;

    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    @Test
    void exportDotAloneCompilesTheProjectAndWritesTheDiagramOfEveryMachine() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeFile("src/main/java/fx/Reference.java", REFERENCE.getBytes(StandardCharsets.UTF_8));

        MavenResult result = fixture.run("graphmaquina:export-dot");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(":complete (sg-complete)"), result.output());
        Path diagram = fixture.projectDirectory().resolve(ORDERS_DIAGRAM);
        assertTrue(result.output().contains(diagram.toRealPath().toString()), result.output());
        assertEquals(fixture.runMain("fx.Reference"), Files.readString(diagram, StandardCharsets.UTF_8));
    }

    @Test
    void exportDotAfterCompileWritesTheSameDiagram() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeFile("src/main/java/fx/Reference.java", REFERENCE.getBytes(StandardCharsets.UTF_8));

        MavenResult result = fixture.run("compile", "graphmaquina:export-dot");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(fixture.runMain("fx.Reference"),
                Files.readString(fixture.projectDirectory().resolve(ORDERS_DIAGRAM), StandardCharsets.UTF_8));
    }

    @Test
    void exportDotTestWritesTheDiagramsOfTheTestCompilation() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeTestSource("fx.Audits", MavenFixture.orders("Audits", "Approve", "approve"));

        MavenResult result = fixture.run("graphmaquina:export-dot-test");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains(":complete-test (sg-complete-test)"), result.output());
        assertTrue(fixture.exists("target/graphmaquina/test/dot/fx.Audits.dot"), result.output());
        assertFalse(fixture.exists("target/graphmaquina/test/dot/fx.Orders.dot"), result.output());
    }

    /**
     * Run from the parent of a reactor, the goal runs in every project: the parent, packaged as {@code pom}, is
     * skipped as every goal of the plugin skips it, and each module writes its own diagrams, the consumer's booking
     * drawing the producer's payment machine as a cluster.
     */
    @Test
    void exportDotFromTheParentOfAReactorExportsEveryModule() throws IOException {
        MavenFixture parent = MavenFixture.project(tempDirectory, fixtureRepository, Map.of());
        parent.reactor();

        MavenResult result = parent.run("graphmaquina:export-dot");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph skips fx.build:fixture:main: the project is packaged as pom"),
                result.output());
        String bookings = Files.readString(
                parent.projectDirectory().resolve("consumer/target/graphmaquina/main/dot/book.Bookings.dot"),
                StandardCharsets.UTF_8);
        assertTrue(bookings.contains("subgraph cluster"), bookings);
        assertTrue(bookings.contains(": Payment/PaymentStatus</font>>;"), bookings);
        assertTrue(parent.exists("producer/target/graphmaquina/main/dot/pay.Payments.dot"), result.output());
    }

    /**
     * A module that does not declare the plugin compiles without publishing an index. Maven resolves the goal prefix
     * only in a project that declares the plugin, so the export runs from the parent by the plugin's coordinates, which
     * every module resolves: it skips that module and still exports the modules that take part.
     */
    @Test
    void exportDotSkipsAModuleThatPublishesNoIndex() throws IOException {
        MavenFixture parent = MavenFixture.project(tempDirectory, fixtureRepository, Map.of());
        parent.reactor();
        parent.editPom("<module>consumer</module>", "<module>consumer</module>\n    <module>plain</module>");
        Path plain = Files.createDirectories(parent.projectDirectory().resolve("plain/src/main/java/plain"));
        Files.writeString(parent.projectDirectory().resolve("plain/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fx.build</groupId>
                  <artifactId>plain</artifactId>
                  <version>1.0.0</version>
                  <packaging>jar</packaging>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Files.writeString(plain.resolve("Plain.java"),
                "package plain;\n\n/** Plain. */\npublic final class Plain {\n}\n");

        MavenResult result = parent.run(MavenFixture.goalPrefix() + "export-dot");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph skips fx.build:plain:main: the compilation publishes no"
                + " StateGraph index at "), result.output());
        assertTrue(parent.exists("consumer/target/graphmaquina/main/dot/book.Bookings.dot"), result.output());
        assertFalse(parent.exists("plain/target/graphmaquina"), result.output());
    }
}
