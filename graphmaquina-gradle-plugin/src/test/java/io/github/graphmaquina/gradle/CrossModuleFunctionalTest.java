package io.github.graphmaquina.gradle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static io.github.graphmaquina.gradle.PluginFixture.EXPORTS;
import static io.github.graphmaquina.gradle.PluginFixture.FIXTURE_REPOSITORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two reference scenarios of composing machines across modules, built and run through the plugin: a booking whose
 * deposit, a payment of the producer, is captured leaves for {@code CONFIRMED}, and a paid order ships once the last
 * of its items, items of the producer, is packed. The consumer receives the producer once as the jar a real build of
 * the producer assembled, and once as a project of the same multi-project build; a jar stripped of its export index is
 * refused, naming the jar.
 */
class CrossModuleFunctionalTest {

    /**
     * What {@code app.Scenario} prints: the booking and its deposit once the deposit is captured, then the order after
     * its first item is packed and after its last.
     */
    private static final String BOTH_SCENARIOS = "CONFIRMED CAPTURED / PAID SHIPPED";

    /** Where the consumer of a jar keeps the producer's jar: under {@code lib}, which every fixture depends on. */
    private static final String PRODUCER_JAR = "lib/producer.jar";

    /** The packages of the producer, which no source root of the consumer of its jar may hold. */
    private static final List<String> PRODUCER_PACKAGES = List.of("pay", "stock");

    @TempDir
    Path tempDirectory;

    /**
     * The consumer holds only its own sources and the jar the producer's build assembled; the line it prints is the
     * one both scenarios report.
     */
    @Test
    void aConsumerOfAProducerJarRunsBothScenarios() throws IOException {
        PluginFixture consumer = consumerOf(Files.readAllBytes(builtProducerJar()));
        consumer.addRunScenario();

        BuildResult result = consumer.run("runScenario", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(printsBothScenarios(result), result::getOutput);
        assertEquals(List.of(), producerPackagesIn(consumer.mainSourceRoots()));
    }

    /**
     * {@code consumer} depends on {@code producer} with {@code project(":producer")}, and both resolve the plugin
     * by its version from the fixture repository, as a build outside this repository does.
     * {@code --refresh-dependencies} is deliberate: the plugin is republished under the same version before every
     * run.
     */
    @Test
    void aMultiProjectBuildRunsBothScenarios() throws IOException {
        PluginFixture build = PluginFixture.multiProject(tempDirectory, FIXTURE_REPOSITORY);

        BuildResult result = build.run(":consumer:runScenario", "--refresh-dependencies", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, result.task(":producer:generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":producer:jar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:compileJava").getOutcome());
        assertTrue(printsBothScenarios(result), result::getOutput);
    }

    /**
     * The same build with a producer that applies {@code java-library}, as a library module does: the consumer then
     * compiles against the producer's directory of classes, where the producer's compilation published its export
     * index, instead of against its jar. The consumer's generation request lists that directory and nothing else of
     * the producer, by the real path Gradle reads the build at.
     */
    @Test
    void aMultiProjectBuildWithALibraryProducerRunsBothScenarios() throws IOException {
        PluginFixture build = PluginFixture.multiProjectWithLibraryProducer(tempDirectory, FIXTURE_REPOSITORY);

        BuildResult result = build.run(":consumer:runScenario", "--refresh-dependencies", "--stacktrace");

        Path producer = build.projectDirectory().resolve("producer").toRealPath();
        assertEquals(TaskOutcome.SUCCESS, result.task(":producer:compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:compileJava").getOutcome());
        assertTrue(printsBothScenarios(result), result::getOutput);
        assertEquals(List.of(producer.resolve("build/classes/java/main").toString()),
                consumerClasspathUnder(build, producer));
    }

    /**
     * The producer's jar without its export index still holds the class files of the payments and the items, which
     * javac would read; the generation refuses the machines it cannot trust, naming the jar they come from by its
     * real path, the one Gradle reads the project at, under a directory whose name is not plain ASCII.
     */
    @Test
    void aProducerJarWithoutItsIndexIsRefusedNamingTheJar() throws IOException {
        PluginFixture consumer = consumerOf(withoutIndex(builtProducerJar()));

        BuildResult result = consumer.runFailing("classes", "--stacktrace");

        assertEquals(TaskOutcome.FAILED, result.task(":generateStateGraphApi").getOutcome());
        assertTrue(result.getOutput().contains("[" + DiagnosticCode.SG602 + "]"), result::getOutput);
        assertTrue(result.getOutput().contains(consumer.projectDirectory().resolve(PRODUCER_JAR).toRealPath()
                + ", which carries no export index"), result::getOutput);
    }

    /** Builds the producer's jar with its own build, which publishes the export index inside it. */
    private Path builtProducerJar() throws IOException {
        PluginFixture producer = PluginFixture.project(Files.createDirectories(tempDirectory.resolve("producer")),
                FIXTURE_REPOSITORY, PluginFixture.crossModuleSources("producer"));
        producer.run("jar", "--stacktrace");
        assertTrue(producer.jarContains(EXPORTS));
        return producer.projectDirectory().resolve("build/libs/fixture-1.0.0.jar");
    }

    /**
     * The consumer's own sources, with {@code producerJar} as {@value #PRODUCER_JAR}, under another group than the
     * producer's, so the two compilations keep two identities.
     */
    private PluginFixture consumerOf(byte[] producerJar) throws IOException {
        PluginFixture consumer = PluginFixture.project(Files.createDirectories(tempDirectory.resolve("consumer")),
                FIXTURE_REPOSITORY, PluginFixture.crossModuleSources("consumer"));
        consumer.replaceInBuildScript("group = \"fx.build\"", "group = \"cx.build\"");
        consumer.writeFile(PRODUCER_JAR, producerJar);
        return consumer;
    }

    /**
     * The entries of the classpath the consumer's generation request of {@code build} lists that lie under
     * {@code root}, in the request's order.
     */
    private static List<String> consumerClasspathUnder(PluginFixture build, Path root) throws IOException {
        Path request = build.projectDirectory().resolve("consumer/build/graphmaquina/main/request.json");
        JsonObject parsed = JsonParser.parseString(Files.readString(request, StandardCharsets.UTF_8))
                .getAsJsonObject();
        return parsed.getAsJsonArray("classpath").asList().stream()
                .map(JsonElement::getAsString)
                .filter((String entry) -> Path.of(entry).startsWith(root))
                .toList();
    }

    /** Whether the build printed the line of both scenarios, as a whole line. */
    private static boolean printsBothScenarios(BuildResult result) {
        return result.getOutput().lines().anyMatch((String line) -> line.equals(BOTH_SCENARIOS));
    }

    /** The directories of the producer's packages that any of {@code roots} holds. */
    private static List<Path> producerPackagesIn(List<String> roots) {
        return roots.stream()
                .flatMap((String root) -> PRODUCER_PACKAGES.stream().map((String name) -> Path.of(root, name)))
                .filter((Path directory) -> Files.exists(directory))
                .toList();
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
