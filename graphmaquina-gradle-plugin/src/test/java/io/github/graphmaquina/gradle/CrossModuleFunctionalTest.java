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
import java.nio.file.StandardOpenOption;
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
 * refused, naming the jar, and a library producer that keeps the module of its enums off its consumers' compile
 * classpath is reported as missing them.
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

    /** The enums of the payments, which {@link #splitDomain} moves out of the producer into {@code domain}. */
    private static final List<String> PAYMENT_ENUMS = List.of("pay/PaymentStatus.java", "pay/PaymentTransition.java");

    /** The build script of {@code domain}: a library of plain enums, compiled as every fixture compiles. */
    private static final String DOMAIN_BUILD = """
            plugins {
                `java-library`
            }

            java {
                toolchain { languageVersion = JavaLanguageVersion.of(21) }
            }

            tasks.withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
                options.release = 21
            }
            """;

    /** What a build script of {@link #splitDomain} appends to depend on {@code domain}. */
    private static final String DEPENDS_ON_DOMAIN = """

            dependencies {
                implementation(project(":domain"))
            }
            """;

    /** The one class of {@code unused} and of {@code fixed}, which uses nothing of the producer. */
    private static final String NOTE = """
            package other;

            /** A class that uses nothing of the producer. */
            public final class Note {
            }
            """;

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
     * A producer that applies {@code java-library} and declares the module of the payments' enums, {@code domain}, with
     * {@code implementation}, which leaves that module off the compile classpath of its consumers, the classpath the
     * plugin hands the generator and javac. {@code unused}, which uses no machine of the producer, is refused by the
     * processor, which checks the export index of every dependency, as missing the state enum; {@code consumer},
     * whose bookings declare the payments a peer, is refused by the generator the same way, naming the producer's
     * directory of classes. {@code fixed}, the same as {@code unused} with {@code domain} as a dependency of its own,
     * compiles.
     */
    @Test
    void aLibraryProducerThatKeepsItsEnumsFromItsConsumersIsReportedAsMissingThem() throws IOException {
        PluginFixture build = PluginFixture.multiProjectWithLibraryProducer(tempDirectory, FIXTURE_REPOSITORY);
        splitDomain(build);

        BuildResult result = build.runFailing(":unused:compileJava", ":consumer:compileJava", ":fixed:compileJava",
                "--continue", "--refresh-dependencies", "--stacktrace");

        Path classes = build.projectDirectory().resolve("producer").toRealPath().resolve("build/classes/java/main");
        assertEquals(TaskOutcome.SUCCESS, result.task(":fixed:compileJava").getOutcome());
        assertEquals(TaskOutcome.FAILED, result.task(":unused:compileJava").getOutcome());
        assertTrue(result.getOutput().contains("[" + DiagnosticCode.SG602 + "] the annotation manifest "),
                result::getOutput);
        assertTrue(result.getOutput().contains(" describes the machine pay.Payments over the state enum"
                + " pay.PaymentStatus, which that machine passes to Machine but this compilation's classpath does not"
                + " declare: add the dependency that declares that type to this compilation's classpath"),
                result::getOutput);
        assertEquals(TaskOutcome.FAILED, result.task(":consumer:generateStateGraphApi").getOutcome());
        assertTrue(result.getOutput().contains("[" + DiagnosticCode.SG602 + "] the peer machine pay.Payments declared"
                + " by book.Bookings comes from " + classes + ", but the state type pay.PaymentStatus that it passes"
                + " to Machine is missing from this classpath"), result::getOutput);
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
     * Rewrites the build {@link PluginFixture#multiProjectWithLibraryProducer} wrote: the payments' enums move into the
     * new library {@code domain}, which the producer declares with {@code implementation}, and two more consumers of
     * the producer, {@code unused} and {@code fixed}, each with the consumer's build script and one class that uses
     * nothing of the producer; {@code fixed} also depends on {@code domain}.
     */
    private static void splitDomain(PluginFixture build) throws IOException {
        Path root = build.projectDirectory();
        Path settings = root.resolve("settings.gradle.kts");
        String included = "include(\"producer\", \"consumer\")";
        String content = Files.readString(settings, StandardCharsets.UTF_8);
        assertTrue(content.contains(included), content);
        Files.writeString(settings, content.replace(included,
                "include(\"producer\", \"consumer\", \"domain\", \"unused\", \"fixed\")"), StandardCharsets.UTF_8);
        build.writeFile("domain/build.gradle.kts", DOMAIN_BUILD);
        for (String source : PAYMENT_ENUMS) {
            Path target = Files.createDirectories(root.resolve("domain/src/main/java/pay")).resolve(Path.of(source)
                    .getFileName());
            Files.move(root.resolve("producer/src/main/java").resolve(source), target);
        }
        Files.writeString(root.resolve("producer/build.gradle.kts"), DEPENDS_ON_DOMAIN, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        String consumer = Files.readString(root.resolve("consumer/build.gradle.kts"), StandardCharsets.UTF_8);
        build.writeFile("unused/build.gradle.kts", consumer);
        build.writeFile("unused/src/main/java/other/Note.java", NOTE);
        build.writeFile("fixed/build.gradle.kts", consumer + DEPENDS_ON_DOMAIN);
        build.writeFile("fixed/src/main/java/other/Note.java", NOTE);
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
