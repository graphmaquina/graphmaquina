package io.github.graphmaquina.maven;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static io.github.graphmaquina.maven.MavenFixture.EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.requireProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two reference scenarios of composing machines across modules, built by a real {@code mvn} and run: a booking
 * whose deposit, a payment of the producer, is captured leaves for {@code CONFIRMED}, and a paid order ships once the
 * last of its items, items of the producer, is packed. The consumer receives the producer once as the jar the
 * producer's own build installed, and once as a module of the same reactor; an installed jar stripped of its export
 * index is refused, naming the jar.
 */
class CrossModuleIntegrationTest {

    /**
     * What {@code app.Scenario} prints: the booking and its deposit once the deposit is captured, then the order after
     * its first item is packed and after its last.
     */
    private static final String BOTH_SCENARIOS = "CONFIRMED CAPTURED / PAID SHIPPED";

    /** The packages of the producer, which no source of the consumer of its installed jar may lie in. */
    private static final List<String> PRODUCER_PACKAGES = List.of("pay", "stock");

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    /**
     * The consumer, the {@code it/consumer} project, holds only its own sources and depends on the jar the producer's
     * build installed; the line it prints, over that jar and nothing else of the producer, is the one both scenarios
     * report.
     */
    @Test
    void anInstalledProducerAndAnIndependentConsumerRunBothScenarios() throws IOException {
        installProducer();
        MavenFixture consumer = MavenFixture.consumer(tempDirectory, fixtureRepository);
        consumer.writeSources(MavenFixture.crossModuleSources("consumer"));

        MavenResult result = consumer.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of(), producerSourcesIn(consumer.projectDirectory()));
        String printed = consumer.runMain("app.Scenario",
                List.of(MavenFixture.installedJar("fx.build", "fixture", "1.0.0")));
        assertTrue(printsBothScenarios(printed), printed);
    }

    /**
     * The parent builds the modules {@code producer} and {@code consumer} in one reactor, the consumer depending on
     * {@code fx.build:producer:1.0.0}, which the reactor resolves to the module it just built, never to an installed
     * artifact; the line the consumer prints over the producer's classes is the one both scenarios report.
     */
    @Test
    void aReactorBuildRunsBothScenarios() throws IOException {
        MavenFixture parent = MavenFixture.project(tempDirectory, fixtureRepository, Map.of());
        MavenFixture consumer = parent.reactor();

        MavenResult result = parent.run("package");

        assertEquals(0, result.exitCode(), result.output());
        String printed = consumer.runMain("app.Scenario",
                List.of(parent.projectDirectory().resolve("producer/target/classes")));
        assertTrue(printsBothScenarios(printed), printed);
    }

    /**
     * The installed jar without its export index still holds the class files of the payments and the items, which
     * javac would read; the consumer's generation refuses the machines it cannot trust, naming the jar of the local
     * repository they come from. The installed jar is put back as the producer's build installed it however the build
     * ends, since every fixture build of this module shares that repository.
     */
    @Test
    void anInstalledProducerWithoutItsIndexIsRefusedNamingTheJar() throws IOException {
        installProducer();
        Path installed = MavenFixture.installedJar("fx.build", "fixture", "1.0.0");
        byte[] original = Files.readAllBytes(installed);
        MavenResult result;
        try {
            Files.write(installed, withoutIndex(installed));
            MavenFixture consumer = MavenFixture.consumer(tempDirectory, fixtureRepository);
            consumer.writeSources(MavenFixture.crossModuleSources("consumer"));
            result = consumer.run("package");
        } finally {
            Files.write(installed, original);
        }

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[" + DiagnosticCode.SG602 + "]"), result.output());
        assertTrue(result.output().contains(installed + ", which carries no export index"), result.output());
    }

    /**
     * Installs the producer, {@code fx.build:fixture:1.0.0}, into the fixtures' local repository with its own build,
     * which publishes the export index inside its jar.
     */
    private void installProducer() throws IOException {
        MavenFixture producer = MavenFixture.project(tempDirectory, fixtureRepository,
                MavenFixture.crossModuleSources("producer"));
        MavenResult result = producer.run("install");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(producer.jarContains(EXPORTS));
    }

    /** Whether {@code output} holds the line of both scenarios, as a whole line. */
    private static boolean printsBothScenarios(String output) {
        return output.lines().anyMatch((String line) -> line.equals(BOTH_SCENARIOS));
    }

    /**
     * The Java sources anywhere under {@code project}, its own and the generated ones alike, whose directory is named
     * after a package of the producer.
     */
    private static List<Path> producerSourcesIn(Path project) throws IOException {
        try (Stream<Path> walk = Files.walk(project)) {
            return walk.filter((Path file) -> file.toString().endsWith(".java"))
                    .filter((Path file) -> PRODUCER_PACKAGES.contains(file.getParent().getFileName().toString()))
                    .toList();
        }
    }

    /** The entries of {@code jar}, in its order, all but the export index. */
    private static byte[] withoutIndex(Path jar) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipFile source = new ZipFile(jar.toFile(), StandardCharsets.UTF_8);
                ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (ZipEntry entry : source.stream().filter((ZipEntry kept) -> !EXPORTS.equals(kept.getName())).toList()) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream content = source.getInputStream(entry)) {
                    content.transferTo(out);
                }
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
