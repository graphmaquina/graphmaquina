package io.github.graphmaquina.gradle;

import com.google.gson.JsonObject;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.github.graphmaquina.gradle.PluginFixture.FIXTURE_REPOSITORY;
import static io.github.graphmaquina.gradle.PluginFixture.MAIN_EXPORTS;
import static io.github.graphmaquina.gradle.PluginFixture.MAIN_PENDING_INVENTORY;
import static io.github.graphmaquina.gradle.PluginFixture.testDomain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Functional tests driving the StateGraph Gradle plugin through TestKit over the settings of the compilation the
 * generation follows: its release and encoding, its toolchain and launcher, its processor path and processing
 * options, its argument providers and debug settings, and the output locations of the compilation and of the
 * generation task.
 */
class CompilationSettingsFunctionalTest {

    /** Where a compilation publishes its index, relative to whatever class output it was given. */
    private static final String EXPORTS_ENTRY = "META-INF/graphmaquina/exports.json";

    /** The two annotation processors Lombok registers, as a {@code -processor} list names them. */
    private static final String LOMBOK_PROCESSORS = "lombok.launch.AnnotationProcessorHider$AnnotationProcessor,"
            + "lombok.launch.AnnotationProcessorHider$ClaimingProcessor";

    /** The StateGraph annotation processor, as a {@code -processor} list names it. */
    private static final String STATEGRAPH_PROCESSOR = "io.github.graphmaquina.processor.StateGraphProcessor";

    /**
     * An argument provider of the main compilation that reads its arguments from a file the task
     * {@code writeArguments} writes, which runs after the generation, and that prints a marker whenever it runs.
     */
    private static final String FILE_ARGUMENTS = """
            abstract class WriteArguments : DefaultTask() {
                @get:OutputFile abstract val output: RegularFileProperty
                @TaskAction fun write() { output.get().asFile.writeText("-parameters\\n") }
            }
            abstract class FileArguments : CommandLineArgumentProvider {
                @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val argumentsFile: RegularFileProperty
                override fun asArguments(): Iterable<String> {
                    println("FILE_ARGUMENTS_READ")
                    return argumentsFile.get().asFile.readLines()
                }
            }
            val writeArguments = tasks.register<WriteArguments>("writeArguments") {
                output = layout.buildDirectory.file("arguments/compiler.txt")
                mustRunAfter("generateStateGraphApi")
            }
            tasks.named<JavaCompile>("compileJava") {
                options.compilerArgumentProviders.add(objects.newInstance<FileArguments>().apply {
                    argumentsFile = writeArguments.flatMap { it.output }
                })
            }
            """;

    /** An argument provider of the main compilation that gives the arguments of its {@code values}. */
    private static final String FIXED_ARGUMENTS = """
            abstract class FixedArguments : CommandLineArgumentProvider {
                @get:Input abstract val values: ListProperty<String>
                override fun asArguments(): Iterable<String> = values.get()
            }
            tasks.named<JavaCompile>("compileJava") {
                options.compilerArgumentProviders.add(objects.newInstance<FixedArguments>().apply {
                    values = listOf("--release=17")
                })
            }
            """;

    @TempDir
    Path tempDirectory;

    /**
     * The class output and the processor's source output are outputs of {@code JavaCompile}, so the generation
     * task only names them in the request. A build that moves either of them must still write the request again,
     * or the next lifecycle step would retire and publish under the paths of the previous layout. This test moves
     * the processor's source output alone, then the class output.
     */
    @Test
    void movingEitherOutputOfTheCompilationRewritesTheRequest() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("classes");
        assertEquals(TaskOutcome.UP_TO_DATE, fixture.run("classes").task(":generateStateGraphApi").getOutcome());
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.generatedSourceOutputDirectory = layout.buildDirectory.dir("elsewhere/annotations")
                }
                """);

        BuildResult movedSources = fixture.run("classes", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, movedSources.task(":generateStateGraphApi").getOutcome());
        String processorSourcesOutput = fixture.request("main").get("processorSourcesOutput").getAsString();
        assertTrue(processorSourcesOutput.endsWith("/build/elsewhere/annotations"), processorSourcesOutput);
        assertTrue(fixture.exists("build/elsewhere/annotations/fx/OrdersRuntime.java"), processorSourcesOutput);
        assertTrue(fixture.inventoryIsSealed("main"));

        fixture.relocateClassOutput("elsewhere/classes");

        BuildResult movedClasses = fixture.run("classes", "--stacktrace");

        assertEquals(TaskOutcome.SUCCESS, movedClasses.task(":generateStateGraphApi").getOutcome());
        String classesOutput = fixture.request("main").get("classesOutput").getAsString();
        assertTrue(classesOutput.endsWith("/build/elsewhere/classes"), classesOutput);
        assertTrue(fixture.exists("build/elsewhere/classes/" + EXPORTS_ENTRY), classesOutput);
    }

    /**
     * The generator runs under the launcher the project's Java toolchain selected, which is the JDK javac compiles
     * with when the compile task is given no other: the generation task's own launcher is the one
     * {@code launcherFor(java.toolchain)} reports, of the toolchain's Java 21, and the process the build started is
     * that very executable. The daemon of the build may run on that same JDK, so this test cannot tell the toolchain
     * from the daemon's JVM; {@code launchesSelectedToolchainInsteadOfDaemonJdk} can.
     */
    @Test
    void launchesTheLauncherOfTheSelectedToolchain() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        BuildResult generation = fixture.run("generateStateGraphApi", "--info");
        Map<String, String> reported = report(fixture.run("printGeneratorLauncher"));

        String launcher = reported.get("GENERATOR_LAUNCHER");
        assertEquals(reported.get("TOOLCHAIN_LAUNCHER"), launcher);
        assertEquals("21", reported.get("GENERATOR_LANGUAGE_VERSION"));
        assertTrue(generation.getOutput().contains("Starting process 'command '" + launcher + "''"),
                "the generator did not run under the toolchain launcher " + launcher
                        + "; the daemon runs on " + reported.get("DAEMON_JAVA_HOME"));
        assertEquals("fx.build:fixture:main", fixture.request("main").get("compilationId").getAsString());
        assertTrue(fixture.exists("build/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * The generator runs under the launcher the project's Java toolchain selected, not under the JVM the Gradle
     * daemon runs on: with a toolchain of another language version than the daemon's, the generation task's own
     * launcher is the toolchain's, of that version and outside the daemon's JDK, and the process the build started
     * is that very executable. The test is skipped when Gradle detects no JDK 21 or later of another language version
     * than the daemon's: a second JDK of the daemon's own language version does not count.
     */
    @Test
    void launchesSelectedToolchainInsteadOfDaemonJdk() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Toolchains toolchains = toolchains(fixture);
        Optional<Integer> other = toolchains.detected().stream()
                .filter((Integer version) -> version != toolchains.daemon())
                .findFirst();
        assumeTrue(other.isPresent(),
                "Gradle detects no JDK 21 or later of another language version than the one its daemon runs on");
        fixture.replaceInBuildScript("languageVersion = JavaLanguageVersion.of(21)",
                "languageVersion = JavaLanguageVersion.of(" + other.get() + ")");

        BuildResult generation = fixture.run("generateStateGraphApi", "--info");
        Map<String, String> reported = report(fixture.run("printGeneratorLauncher"));

        String launcher = reported.get("GENERATOR_LAUNCHER");
        assertEquals(reported.get("TOOLCHAIN_LAUNCHER"), launcher);
        assertEquals(String.valueOf(other.get()), reported.get("GENERATOR_LANGUAGE_VERSION"));
        assertFalse(Path.of(launcher).startsWith(Path.of(reported.get("DAEMON_JAVA_HOME"))), reported::toString);
        assertTrue(generation.getOutput().contains("Starting process 'command '" + launcher + "''"),
                "the generator did not run under the toolchain launcher " + launcher
                        + "; the daemon runs on " + reported.get("DAEMON_JAVA_HOME"));
    }

    /**
     * The generator runs on the JDK javac compiles with, also when the compile task does not take it from the
     * project's toolchain: from a toolchain of its own, or from the {@code forkOptions.javaHome} Gradle starts javac
     * from. With no release given, javac compiles at the language version of that JDK, above the project's Java 21,
     * and the generator, on that very JDK, parses the sources at that release, in a build that stores its
     * configuration cache entry. The test is skipped when Gradle detects no JDK above Java 21.
     */
    @Test
    void runsTheGeneratorOnTheJdkTheCompileTaskCompilesWith() throws IOException {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("toolchain", """
                tasks.named<JavaCompile>("compileJava") {
                    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(%d) }
                }
                """);
        shapes.put("fork", """
                tasks.named<JavaCompile>("compileJava") {
                    options.isFork = true
                    options.forkOptions.javaHome = javaToolchains
                        .compilerFor { languageVersion = JavaLanguageVersion.of(%d) }
                        .get().metadata.installationPath.asFile
                }
                """);
        Optional<Integer> later = toolchains(PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("detection")), FIXTURE_REPOSITORY)).detected().stream()
                .filter((Integer version) -> version > 21)
                .findFirst();
        assumeTrue(later.isPresent(), "Gradle detects no JDK above Java 21");
        for (Map.Entry<String, String> shape : shapes.entrySet()) {
            PluginFixture fixture = PluginFixture.basic(
                    Files.createDirectories(tempDirectory.resolve(shape.getKey())), FIXTURE_REPOSITORY);
            fixture.replaceInBuildScript("options.release = 21\n", "");
            fixture.appendToBuildScript(shape.getValue().formatted(later.get()));

            BuildResult result = fixture.run("jar", "--info", "--configuration-cache");
            Map<String, String> reported = report(fixture.run("printGeneratorLauncher"));

            assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome(), shape.getKey());
            assertTrue(result.getOutput().contains("Configuration cache entry stored."), result::getOutput);
            assertEquals(later.get().intValue(), fixture.request("main").get("release").getAsInt(), shape.getKey());
            String launcher = reported.get("GENERATOR_LAUNCHER");
            assertEquals(String.valueOf(later.get()), reported.get("GENERATOR_LANGUAGE_VERSION"), shape.getKey());
            assertTrue(Path.of(launcher).startsWith(Path.of(reported.get("COMPILER_HOME"))), reported::toString);
            assertTrue(result.getOutput().contains("Starting process 'command '" + launcher + "''"),
                    shape.getKey() + ": the generator did not run under " + launcher);
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"), shape.getKey());
        }
    }

    /**
     * A launcher a build gives the generation task wins over the JDK javac compiles with, but a JDK cannot parse the
     * sources at a release later than its own: the generation refuses such a launcher before the generator runs,
     * naming {@code javaLauncher}, instead of failing inside the generator on a release its JDK does not know. The
     * test is skipped when Gradle detects no JDK above Java 21.
     */
    @Test
    void refusesALauncherOlderThanTheReleaseBeforeTheGeneratorRuns() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Optional<Integer> later = toolchains(fixture).detected().stream()
                .filter((Integer version) -> version > 21)
                .findFirst();
        assumeTrue(later.isPresent(), "Gradle detects no JDK above Java 21");
        fixture.replaceInBuildScript("options.release = 21\n", "");
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(%d) }
                }
                tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                    javaLauncher = javaToolchains.launcherFor(java.toolchain)
                }
                """.formatted(later.get()));

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":generateStateGraphApi").getOutcome());
        assertTrue(refused.getOutput().contains("the StateGraph generator cannot parse the sources of task"
                + " ':generateStateGraphApi' at release " + later.get() + " under its javaLauncher, Java 21 at "),
                refused::getOutput);
        assertTrue(refused.getOutput().contains("Give javaLauncher a JDK " + later.get() + " or later"),
                refused::getOutput);
        assertFalse(refused.getOutput().contains("not supported"), refused::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * A location a build sets on a generation task wins whenever the build sets it: a block that configures
     * every generation task before a source set even exists still moves that source set's generated sources,
     * its manifest, its request and the inventory its compilation seals.
     */
    @Test
    void honorsLocationsABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeFile("src/extra/java/" + source.getKey().replace('.', '/') + ".java", source.getValue());
        }
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>().configureEach {
                    sourcesOutput = layout.buildDirectory.dir("custom/generated/" + name)
                    workDirectory = layout.buildDirectory.dir("custom/work/" + name)
                }
                sourceSets.create("extra")
                dependencies {
                    "extraImplementation"("io.github.graphmaquina:graphmaquina-core:%1$s")
                    "extraAnnotationProcessor"("io.github.graphmaquina:graphmaquina-processor:%1$s")
                }
                """.formatted(PluginFixture.version()));

        BuildResult result = fixture.run("extraClasses");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileExtraJava").getOutcome());
        assertTrue(fixture.exists("build/custom/generated/generateExtraStateGraphApi/fxt/TicketsGraph.java"));
        assertTrue(fixture.exists("build/custom/work/generateExtraStateGraphApi/annotation-manifest.json"));
        assertTrue(fixture.exists("build/custom/work/generateExtraStateGraphApi/request.json"));
        assertTrue(fixture.exists("build/custom/work/generateExtraStateGraphApi/owned-outputs.json"));
        assertFalse(fixture.exists("build/graphmaquina/extra"));
        assertFalse(fixture.exists("build/generated/sources/graphmaquina/extra"));
    }

    /**
     * What the generation task only names stays the compiler's or the generator's even when a build sets it
     * before the source set exists, which is before the plugin configured that source set's task: the value is
     * refused then too, instead of being silently replaced.
     */
    @Test
    void refusesADerivedLocationSetBeforeTheSourceSetExists() throws IOException {
        for (String property : List.of("annotationManifest", "classesOutput", "processorSourcesOutput")) {
            PluginFixture fixture = PluginFixture.basic(
                    Files.createDirectories(tempDirectory.resolve(property)), FIXTURE_REPOSITORY);
            String location = property.equals("annotationManifest")
                    ? "layout.buildDirectory.file(\"custom/annotation-manifest.json\")"
                    : "layout.buildDirectory.dir(\"custom/" + property + "\")";
            fixture.appendToBuildScript("""
                    tasks.withType<GenerateGraphApi>()
                            .matching { it.name == "generateExtraStateGraphApi" }
                            .configureEach {
                                %s = %s
                            }
                    sourceSets.create("extra")
                    """.formatted(property, location));

            BuildResult result = fixture.runFailing("generateExtraStateGraphApi");

            assertTrue(result.getOutput().contains(
                    "task ':generateExtraStateGraphApi' property '" + property + "' cannot be changed"),
                    result::getOutput);
        }
    }

    /**
     * The generation task's own locations are the ones the build uses: moving where it writes the vocabulary
     * moves the source root javac compiles, and moving its work directory moves the manifest javac is pointed
     * at and the inventory the compilation seals.
     */
    @Test
    void followsTheLocationsTheGenerationTaskWasGiven() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                    sourcesOutput = layout.buildDirectory.dir("custom/generated")
                    workDirectory = layout.buildDirectory.dir("custom/work")
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.exists("build/custom/generated/fx/OrdersGraph.java"));
        assertTrue(fixture.exists("build/custom/work/annotation-manifest.json"));
        assertTrue(fixture.exists("build/custom/work/owned-outputs.json"));
        assertFalse(fixture.exists("build/custom/work/owned-outputs.pending.json"));
        assertFalse(fixture.exists("build/generated/sources/graphmaquina/main"));
        assertTrue(fixture.mainSourceRoots().stream()
                .anyMatch((String root) -> root.endsWith("/build/custom/generated")));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.jarContains(EXPORTS_ENTRY));
    }

    /**
     * What the generation task only names is not its to move: the class output and the processor's source
     * output belong to javac, and the generator writes the annotation manifest into the work directory. A
     * build script that sets one of them on the generation task is refused, instead of a request that names
     * one place while javac uses another.
     */
    @Test
    void refusesToMoveWhatTheGenerationOnlyNames() throws IOException {
        for (String property : List.of("annotationManifest", "classesOutput", "processorSourcesOutput")) {
            PluginFixture fixture = PluginFixture.basic(
                    Files.createDirectories(tempDirectory.resolve(property)), FIXTURE_REPOSITORY);
            String location = property.equals("annotationManifest")
                    ? "layout.buildDirectory.file(\"custom/annotation-manifest.json\")"
                    : "layout.buildDirectory.dir(\"custom/" + property + "\")";
            fixture.appendToBuildScript("""
                    tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                        %s = %s
                    }
                    """.formatted(property, location));

            BuildResult result = fixture.runFailing("jar");

            assertTrue(result.getOutput().contains("property '" + property + "' cannot be changed"),
                    result::getOutput);
        }
    }

    /**
     * The lifecycle completes only what javac wrote, so a compilation that can end without its classes is
     * refused before the preparation starts, which leaves no pending inventory, and so before javac runs:
     * {@code failOnError = false} lets a rejected compilation succeed, and {@code -proc:only} runs the processors
     * without writing a class at all.
     */
    @Test
    void refusesACompilationThatCanEndWithoutItsClasses() throws IOException {
        PluginFixture lenient = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("lenient")), FIXTURE_REPOSITORY);
        Map<String, String> broken = PluginFixture.domainSources();
        broken.put("fx.Broken", """
                package fx;

                /** A declaration javac refuses, so the compilation of this fixture fails. */
                public final class Broken {

                    private final int count = "not a number";
                }
                """);
        lenient.writeSources(broken);
        lenient.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.isFailOnError = false
                }
                """);

        BuildResult refusedLenient = lenient.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refusedLenient.task(":compileJava").getOutcome());
        assertTrue(refusedLenient.getOutput().contains("cannot run with options.failOnError = false"),
                refusedLenient::getOutput);
        assertFalse(refusedLenient.getOutput().contains("-g:none"), refusedLenient::getOutput);
        assertFalse(refusedLenient.getOutput().contains("incompatible types"), "javac never ran");
        assertFalse(lenient.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        PluginFixture processorsOnly = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("processors-only")), FIXTURE_REPOSITORY);
        processorsOnly.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.add("-proc:only")
                }
                """);

        BuildResult refusedProcessorsOnly = processorsOnly.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refusedProcessorsOnly.task(":compileJava").getOutcome());
        assertTrue(refusedProcessorsOnly.getOutput().contains("cannot run with -proc:only"),
                refusedProcessorsOnly::getOutput);
        assertFalse(refusedProcessorsOnly.getOutput().contains("-g:none"), refusedProcessorsOnly::getOutput);
        assertFalse(processorsOnly.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * javac runs no annotation processor under {@code -proc:none}, and given a {@code -processor} list it runs only
     * the processors the last such list names. A compilation that would run without the StateGraph processor
     * either way is refused before the preparation starts, so it leaves no pending inventory and javac never runs;
     * a list that names the StateGraph processor builds and publishes the index the first build published.
     */
    @Test
    void refusesACompilationThatRunsWithoutTheStateGraphProcessor() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.run("jar");
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("-proc:none"))
                }
                """);

        BuildResult off = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, off.task(":compileJava").getOutcome());
        assertTrue(off.getOutput().contains("cannot run with -proc:none"), off::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
        assertFalse(off.getOutput().contains("complete-compile"), off::getOutput);

        String lombokOnly = LOMBOK_PROCESSORS.replace("$", "\\$");
        fixture.replaceInBuildScript("listOf(\"-proc:none\")", "listOf(\"-processor\", \"" + lombokOnly + "\")");

        BuildResult unlisted = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, unlisted.task(":compileJava").getOutcome());
        assertTrue(unlisted.getOutput().contains("cannot run with -processor '" + LOMBOK_PROCESSORS
                + "', which leaves out " + STATEGRAPH_PROCESSOR), unlisted::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
        assertFalse(unlisted.getOutput().contains("No processor claimed"), "javac never ran");

        fixture.replaceInBuildScript(lombokOnly, lombokOnly + "," + STATEGRAPH_PROCESSOR);

        BuildResult listed = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, listed.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
    }

    /**
     * Gradle gives javac a {@code -proc:none} of its own when the compilation's processor path is empty or null, so
     * javac would run no annotation processor and the StateGraph processor would write no index for the completion
     * step to publish: such a compilation is refused before the preparation starts. A later {@code -proc:full} changes
     * nothing for Gradle's own Java compiler, in process or in its compiler daemon, which runs only the processors of
     * the processor path, so it is refused too. Only a javac Gradle starts from the command line takes that later
     * {@code -proc:full} and looks for processors on the class path, so with the processors there it builds.
     */
    @Test
    void refusesAnEmptyProcessorPath() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.annotationProcessorPath = files()
                }
                """);

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":compileJava").getOutcome());
        assertTrue(refused.getOutput().contains("cannot run with an options.annotationProcessorPath that is empty or"
                + " null: Gradle then gives javac -proc:none"), refused::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.annotationProcessorPath = files()",
                "options.annotationProcessorPath = files()\n    options.compilerArgs.add(\"-proc:full\")");

        BuildResult inProcess = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, inProcess.task(":compileJava").getOutcome());
        assertTrue(inProcess.getOutput().contains("cannot run with an options.annotationProcessorPath that is empty or"
                + " null: Gradle's Java compiler, in process or in its compiler daemon, runs only the annotation"
                + " processors of options.annotationProcessorPath"), inProcess::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.compilerArgs.add(\"-proc:full\")", """
                options.compilerArgs.add("-proc:full")
                    classpath += configurations.annotationProcessor.get()
                    options.isFork = true
                    options.forkOptions.javaHome = javaCompiler.get().metadata.installationPath.asFile""");

        BuildResult commandLine = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, commandLine.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * javac takes the last {@code -proc:} option, and Gradle's Java compiler takes the last {@code -processor} list,
     * splits it on every comma, keeping empty names, and loads each name exactly as written; the preparation reads
     * both the same way. {@code -proc:none} followed by {@code -proc:full} builds, while {@code -proc:full} followed
     * by {@code -proc:none}, a {@code -processor} list that names the StateGraph processor followed by one that does
     * not, and a list holding a name with a space in it or an empty name after its last comma, neither of them a
     * valid qualified class name, are refused before the preparation starts.
     */
    @Test
    void readsTheProcessingOptionsTheWayTheCompilationTakesThem() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        String lombokOnly = LOMBOK_PROCESSORS.replace("$", "\\$");
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("-proc:none", "-proc:full"))
                }
                """);

        BuildResult full = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, full.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));

        fixture.replaceInBuildScript("listOf(\"-proc:none\", \"-proc:full\")",
                "listOf(\"-proc:full\", \"-proc:none\")");

        BuildResult none = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, none.task(":compileJava").getOutcome());
        assertTrue(none.getOutput().contains("cannot run with -proc:none"), none::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("listOf(\"-proc:full\", \"-proc:none\")", "listOf(\"-processor\", \""
                + lombokOnly + "," + STATEGRAPH_PROCESSOR + "\", \"-processor\", \"" + lombokOnly + "\")");

        BuildResult lists = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, lists.task(":compileJava").getOutcome());
        assertTrue(lists.getOutput().contains("cannot run with -processor '" + LOMBOK_PROCESSORS
                + "', which leaves out " + STATEGRAPH_PROCESSOR), lists::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        String spaced = LOMBOK_PROCESSORS.substring(0, LOMBOK_PROCESSORS.indexOf(',')) + ", " + STATEGRAPH_PROCESSOR;
        fixture.replaceInBuildScript("listOf(\"-processor\", \"" + lombokOnly + "," + STATEGRAPH_PROCESSOR
                + "\", \"-processor\", \"" + lombokOnly + "\")",
                "listOf(\"-processor\", \"" + spaced.replace("$", "\\$") + "\")");

        BuildResult spaces = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, spaces.task(":compileJava").getOutcome());
        assertTrue(spaces.getOutput().contains("cannot run with -processor '" + spaced + "', whose name ' "
                + STATEGRAPH_PROCESSOR + "' is not a valid qualified class name"), spaces::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript(spaced.replace("$", "\\$"), lombokOnly + "," + STATEGRAPH_PROCESSOR + ",");

        BuildResult trailing = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, trailing.task(":compileJava").getOutcome());
        assertTrue(trailing.getOutput().contains("cannot run with -processor '" + LOMBOK_PROCESSORS + ","
                + STATEGRAPH_PROCESSOR + ",', whose name '' is not a valid qualified class name"),
                trailing::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * javac leaves the {@code SourceFile} attribute out of every class file once it is given {@code -g:none} or a
     * {@code -g:} list and no {@code -g:} list it is given names {@code source}, and the completion step links each
     * class file to its source through that attribute. Gradle passes {@code options.debug = false} as {@code -g:none}
     * and {@code options.debugOptions.debugLevel} as a {@code -g:} list, ahead of the compiler arguments. Each of
     * those settings, and such a compiler argument, is refused before the preparation starts, so javac never runs; a
     * {@code -g:} list that names {@code source} anywhere keeps the attribute, so it builds even when {@code -g:none}
     * and a {@code -g:} list without {@code source} follow it, and a {@code -g:} option javac rejects is left to
     * javac.
     */
    @Test
    void refusesADebugSettingThatDropsTheSourceFileAttribute() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.isDebug = false
                }
                """);

        BuildResult noDebug = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, noDebug.task(":compileJava").getOutcome());
        assertTrue(noDebug.getOutput().contains("cannot run with options.debug = false: javac then leaves the"
                + " SourceFile attribute out of every class file"), noDebug::getOutput);
        assertFalse(noDebug.getOutput().contains("[SG602]"), noDebug::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.isDebug = false",
                "options.debugOptions.debugLevel = \"lines,vars\"");

        BuildResult level = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, level.task(":compileJava").getOutcome());
        assertTrue(level.getOutput().contains(
                "cannot run with options.debugOptions.debugLevel = \"lines,vars\": javac then leaves"),
                level::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.debugOptions.debugLevel = \"lines,vars\"",
                "options.compilerArgs.add(\"-g:lines,vars\")");

        BuildResult argument = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, argument.task(":compileJava").getOutcome());
        assertTrue(argument.getOutput().contains(
                "cannot run with the compiler argument -g:lines,vars: javac then leaves"), argument::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("options.compilerArgs.add(\"-g:lines,vars\")",
                "options.compilerArgs.add(\"-g:foo\")");

        BuildResult rejected = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, rejected.task(":compileJava").getOutcome());
        assertTrue(rejected.getOutput().contains("invalid flag: -g:foo"), rejected::getOutput);
        assertFalse(rejected.getOutput().contains("SourceFile attribute"), rejected::getOutput);

        fixture.replaceInBuildScript("options.compilerArgs.add(\"-g:foo\")",
                "options.debugOptions.debugLevel = \"source\"\n"
                        + "    options.compilerArgs.addAll(listOf(\"-g:none\", \"-g:lines,vars\"))");

        BuildResult kept = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, kept.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * javac reads the sources of a project in the encoding its compilation declares and at the release it
     * compiles for, so the generator is told both: an accented identifier of a project kept in ISO-8859-1 reaches
     * the generated vocabulary intact, and javac reads that vocabulary back in the same encoding.
     */
    @Test
    void passesTheEncodingAndTheReleaseOfTheCompilationToTheGenerator() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"", "options.encoding = \"ISO-8859-1\"");
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        JsonObject request = fixture.request("main");
        assertEquals(21, request.get("release").getAsInt(), request::toString);
        assertEquals("ISO-8859-1", request.get("encoding").getAsString(), request::toString);
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), fixture.localExports()::toString);
        assertTrue(fixture.jarContains("fx/ActionsRefs$ApproveRésumé.class"));
    }

    /**
     * A compilation that names no encoding gives the request none, and the generator then reads UTF-8. This one
     * names no release either, so the request carries 21, the language version of the fixture's toolchain, which a
     * constant 21 would match too; {@code passesTheReleaseOfTheToolchainWhenTheCompilationNamesNone} tells the two
     * apart.
     */
    @Test
    void passesNoEncodingWhenTheCompilationNamesNone() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"\n", "");
        fixture.replaceInBuildScript("options.release = 21\n", "");

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        JsonObject request = fixture.request("main");
        assertEquals(21, request.get("release").getAsInt(), request::toString);
        assertFalse(request.has("encoding"), request::toString);
    }

    /**
     * A compilation that names no release compiles against the whole API of its toolchain's JDK, so the request
     * carries the language version of that toolchain: with a toolchain above Java 21, that version and not 21. The
     * test is skipped when Gradle detects no JDK above Java 21.
     */
    @Test
    void passesTheReleaseOfTheToolchainWhenTheCompilationNamesNone() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Optional<Integer> later = toolchains(fixture).detected().stream()
                .filter((Integer version) -> version > 21)
                .findFirst();
        assumeTrue(later.isPresent(), "Gradle detects no JDK above Java 21");
        fixture.replaceInBuildScript("languageVersion = JavaLanguageVersion.of(21)",
                "languageVersion = JavaLanguageVersion.of(" + later.get() + ")");
        fixture.replaceInBuildScript("options.release = 21\n", "");

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        JsonObject request = fixture.request("main");
        assertEquals(later.get().intValue(), request.get("release").getAsInt(), request::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * The generator is told the encoding javac reads the sources in. Gradle passes {@code options.encoding} as an
     * {@code -encoding} of its own ahead of the compiler arguments, and javac takes the last {@code -encoding} it is
     * given, but Gradle's own Java compiler, in process or in a compiler daemon, opens its file manager in
     * {@code options.encoding}, which then wins. So a project kept in ISO-8859-1 builds with its accented identifier
     * intact when the last {@code -encoding} of its compiler arguments names that encoding and it sets no
     * {@code options.encoding}, and, with the javac Gradle starts from the command line for
     * {@code forkOptions.javaHome}, when the last {@code -encoding} of the arguments names it, whatever
     * {@code options.encoding} says. In process, javac still writes the sources an annotation processor generates in
     * the last {@code -encoding}, and reads them back in {@code options.encoding}, so a last {@code -encoding} that
     * names another charset than {@code options.encoding} is refused before the preparation starts, in process and in
     * the compiler daemon alike, while one that names the same charset under another name, {@code latin1}, builds.
     */
    @Test
    void passesTheEncodingJavacReadsTheSourcesIn() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"\n", "");
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);
        String arguments = """
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("-encoding", "UTF-16", "-encoding", "ISO-8859-1"))
                }
                """;
        fixture.appendToBuildScript(arguments);

        BuildResult lastArgument = fixture.run("jar");

        assertAccentedSourcesBuilt(fixture, lastArgument);

        String alias = """
                tasks.named<JavaCompile>("compileJava") {
                    options.encoding = "ISO-8859-1"
                    options.compilerArgs.addAll(listOf("-encoding", "latin1"))
                }
                """;
        fixture.replaceInBuildScript(arguments, alias);

        BuildResult sameCharset = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, sameCharset.task(":compileJava").getOutcome());
        assertEquals("ISO-8859-1", fixture.request("main").get("encoding").getAsString());
        assertTrue(fixture.jarContains("fx/ActionsRefs$ApproveRésumé.class"));

        String contradicting = alias.replace("\"latin1\"", "\"UTF-8\"");
        fixture.replaceInBuildScript(alias, contradicting);

        BuildResult refused = fixture.runFailing("jar");

        assertEncodingContradictionRefused(fixture, refused);

        String daemon = contradicting.replace("options.encoding = \"ISO-8859-1\"",
                "options.encoding = \"ISO-8859-1\"\n    options.isFork = true");
        fixture.replaceInBuildScript(contradicting, daemon);

        BuildResult refusedInDaemon = fixture.runFailing("jar");

        assertEncodingContradictionRefused(fixture, refusedInDaemon);

        fixture.replaceInBuildScript(daemon, """
                tasks.named<JavaCompile>("compileJava") {
                    options.encoding = "UTF-8"
                    options.compilerArgs.addAll(listOf("-encoding", "ISO-8859-1"))
                    options.isFork = true
                    options.forkOptions.javaHome = javaCompiler.get().metadata.installationPath.asFile
                }
                """);

        BuildResult commandLine = fixture.run("jar");

        assertAccentedSourcesBuilt(fixture, commandLine);
    }

    /**
     * The generation reads the release and the encoding from the compilation's options and
     * {@code options.compilerArgs} alone, never from its argument providers, which run only with the compilation: a
     * provider that reads a file another task writes does not run when the generation runs on its own.
     */
    @Test
    void aCompilerArgumentProviderDoesNotRunForTheGenerationAlone() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FILE_ARGUMENTS);

        BuildResult result = fixture.run("generateStateGraphApi");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertFalse(result.getOutput().contains("FILE_ARGUMENTS_READ"), result::getOutput);
    }

    /**
     * An argument provider that reads a file another task writes runs with the compilation, once that task wrote it,
     * even when that task runs after the generation; the generation never runs it.
     */
    @Test
    void aCompilerArgumentProviderRunsOnlyWithTheCompilation() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FILE_ARGUMENTS);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains("FILE_ARGUMENTS_READ"), result::getOutput);
        assertTrue(fixture.inventoryIsSealed("main"));
    }

    /**
     * Storing the configuration cache runs no argument provider of the compilation either: the generation's release
     * and encoding are stored without one, so a provider that reads a file another task writes runs only with the
     * compilation, and the next build reuses the entry.
     */
    @Test
    void aCompilerArgumentProviderDoesNotRunWhenTheConfigurationCacheIsStored() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FILE_ARGUMENTS);

        BuildResult result = fixture.run("jar", "--configuration-cache");

        assertTrue(result.getOutput().contains("Configuration cache entry stored."), result::getOutput);
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));

        BuildResult reused = fixture.run("jar", "--configuration-cache");

        assertTrue(reused.getOutput().contains("Reusing configuration cache."), reused::getOutput);
        assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":compileJava").getOutcome());
    }

    /**
     * The argument providers of a compilation run just before javac, so the preparation, which runs then, derives the
     * release and the encoding javac takes from all its compiler arguments, and refuses a compilation whose providers
     * change either from the one the generation was given: a provider's {@code --release=17} beside
     * {@code options.release = 21}, and a provider's {@code -encoding ISO-8859-1} where nothing else names an encoding,
     * so the generator reads UTF-8. A provider that repeats the effective value changes nothing and builds, and one
     * whose last {@code --release} javac refuses is left to javac, which names it.
     */
    @Test
    void refusesArgumentProvidersThatChangeTheReleaseOrTheEncoding() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript(FIXED_ARGUMENTS);

        BuildResult release = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, release.task(":compileJava").getOutcome());
        assertTrue(release.getOutput().contains("cannot run with the compiler arguments its argument providers give:"
                + " they make javac compile at release 17 instead of release 21, the one options.release gives and"
                + " the generation reads"), release::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("listOf(\"--release=17\")", "listOf(\"--release=21\")");

        BuildResult sameRelease = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, sameRelease.task(":compileJava").getOutcome());

        fixture.replaceInBuildScript("options.encoding = \"UTF-8\"\n", "");
        fixture.replaceInBuildScript("listOf(\"--release=21\")", "listOf(\"-encoding\", \"ISO-8859-1\")");

        BuildResult encoding = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, encoding.task(":compileJava").getOutcome());
        assertTrue(encoding.getOutput().contains("cannot run with the compiler arguments its argument providers give:"
                + " they make javac read the sources in ISO-8859-1 instead of UTF-8, which the generator reads when"
                + " options.encoding and options.compilerArgs name no encoding"), encoding::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("listOf(\"-encoding\", \"ISO-8859-1\")", "listOf(\"-encoding\", \"UTF-8\")");

        BuildResult sameEncoding = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, sameEncoding.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));

        fixture.replaceInBuildScript("listOf(\"-encoding\", \"UTF-8\")", "listOf(\"--release=1.8\")");
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.add("--release=17")
                }
                """);

        BuildResult refusedRelease = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refusedRelease.task(":compileJava").getOutcome());
        assertTrue(refusedRelease.getOutput().contains("release version 1.8 not supported"),
                refusedRelease::getOutput);
        assertFalse(refusedRelease.getOutput().contains("its argument providers give"), refusedRelease::getOutput);
    }

    /**
     * A refusal of argument providers that change the release names where the release the generation reads comes
     * from: the language version of the compilation's toolchain when neither {@code options.release} nor
     * {@code options.compilerArgs} gives one, else the last {@code --release} of {@code options.compilerArgs}.
     */
    @Test
    void aReleaseRefusalNamesWhereTheGenerationsReleaseComesFrom() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.replaceInBuildScript("options.release = 21\n", "");
        fixture.appendToBuildScript(FIXED_ARGUMENTS);

        BuildResult toolchain = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, toolchain.task(":compileJava").getOutcome());
        assertTrue(toolchain.getOutput().contains("they make javac compile at release 17 instead of release 21, the"
                + " language version of the compilation's toolchain, which the generation reads when options.release"
                + " and options.compilerArgs give none"), toolchain::getOutput);

        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("--release", "21"))
                }
                """);

        BuildResult arguments = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, arguments.task(":compileJava").getOutcome());
        assertTrue(arguments.getOutput().contains("they make javac compile at release 17 instead of release 21, the"
                + " one options.compilerArgs give and the generation reads"), arguments::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * javac takes the last {@code --release} it is given, spelled {@code --release N} or {@code --release=N}, and
     * Gradle passes {@code options.release} ahead of the compiler arguments, so the last {@code --release} among those
     * arguments is the release javac compiles at; the generator is told that one, whether the compilation also sets
     * {@code options.release} or not. A last value that is not a plain decimal number, which javac refuses, is passed
     * over, so the generation still runs, at the language version of the toolchain. Only the generation runs: javac
     * would compile the domain at release 17 against a library built for 21.
     */
    @Test
    void passesTheReleaseJavacTakesFromTheCompilerArguments() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.addAll(listOf("--release=17"))
                }
                """);

        fixture.run("generateStateGraphApi");

        JsonObject overriding = fixture.request("main");
        assertEquals(17, overriding.get("release").getAsInt(), overriding::toString);

        fixture.replaceInBuildScript("options.release = 21\n", "");
        fixture.replaceInBuildScript("listOf(\"--release=17\")",
                "listOf(\"--release\", \"21\", \"--release\", \"17\")");

        fixture.run("generateStateGraphApi");

        JsonObject last = fixture.request("main");
        assertEquals(17, last.get("release").getAsInt(), last::toString);

        fixture.replaceInBuildScript("listOf(\"--release\", \"21\", \"--release\", \"17\")",
                "listOf(\"--release\", \"21\", \"--release\", \"1.8\")");

        fixture.run("generateStateGraphApi");

        JsonObject refused = fixture.request("main");
        assertEquals(21, refused.get("release").getAsInt(), refused::toString);
    }

    /**
     * The request file is a convention as well: a build that sets it on every generation task before a source set
     * exists has that source set's request written there, and the compilation's lifecycle reads it from there.
     */
    @Test
    void honorsARequestFileABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeFile("src/extra/java/" + source.getKey().replace('.', '/') + ".java", source.getValue());
        }
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>().configureEach {
                    requestFile = layout.buildDirectory.file("custom/requests/" + name + ".json")
                }
                sourceSets.create("extra")
                dependencies {
                    "extraImplementation"("io.github.graphmaquina:graphmaquina-core:%1$s")
                    "extraAnnotationProcessor"("io.github.graphmaquina:graphmaquina-processor:%1$s")
                }
                """.formatted(PluginFixture.version()));

        BuildResult result = fixture.run("extraClasses");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileExtraJava").getOutcome());
        assertTrue(fixture.exists("build/custom/requests/generateExtraStateGraphApi.json"));
        assertFalse(fixture.exists("build/graphmaquina/extra/request.json"));
        assertTrue(fixture.exists("build/graphmaquina/extra/owned-outputs.json"));
        assertFalse(fixture.exists("build/graphmaquina/extra/owned-outputs.pending.json"));
    }

    /**
     * A launcher a build gives the generation task runs every step of that compilation: the preparation and the
     * completion around javac as well as the generation, so the three never run under two different JDKs. The
     * launcher of the test is a POSIX shell script, so the test is skipped on Windows.
     */
    @Test
    void runsEveryStepUnderTheLauncherTheGenerationTaskWasGiven() throws IOException {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "the logging launcher of this test is a POSIX shell script");
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Path log = fixture.projectDirectory().resolve("launcher.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        fixture.writeFile("logging-java", "#!/bin/sh\necho \"$*\" >> '" + log + "'\nexec '" + java + "' \"$@\"\n");
        if (!fixture.projectDirectory().resolve("logging-java").toFile().setExecutable(true)) {
            throw new IllegalStateException("cannot make the logging launcher executable");
        }
        fixture.appendToBuildScript("""
                val toolchainLauncher = javaToolchains.launcherFor(java.toolchain)
                val loggingJava = layout.projectDirectory.file("logging-java")
                tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                    javaLauncher = toolchainLauncher.map { real ->
                        object : JavaLauncher {
                            override fun getMetadata(): JavaInstallationMetadata = real.metadata
                            override fun getExecutablePath(): RegularFile = loggingJava
                        }
                    }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> launched = Files.readAllLines(log, StandardCharsets.UTF_8);
        for (String operation : List.of("generate", "prepare-compile", "complete-compile")) {
            assertTrue(launched.stream().anyMatch((String line) -> line.contains(" " + operation + " --request ")),
                    operation + " did not run under the launcher of the generation task: " + launched);
        }
    }

    /**
     * A generator classpath a build gives the generation task runs every step of that compilation, as its launcher
     * does: the preparation and the completion around javac run over the very classpath the generation ran over.
     */
    @Test
    void runsEveryStepOverTheGeneratorClasspathTheGenerationTaskWasGiven() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        try (ZipOutputStream marker = new ZipOutputStream(
                Files.newOutputStream(fixture.projectDirectory().resolve("marker.jar")))) {
            marker.putNextEntry(new ZipEntry("marker.txt"));
            marker.closeEntry();
        }
        fixture.appendToBuildScript("""
                tasks.named<GenerateGraphApi>("generateStateGraphApi") {
                    generatorClasspath.from(files("marker.jar"))
                }
                """);

        BuildResult result = fixture.run("jar", "--info");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        for (String operation : List.of("generate", "prepare-compile", "complete-compile")) {
            List<String> launches = result.getOutput().lines()
                    .filter((String line) -> line.contains("CodegenMain " + operation + " --request"))
                    .toList();
            assertFalse(launches.isEmpty(), "no launch of " + operation + " was logged");
            assertTrue(launches.stream().allMatch((String line) -> line.contains("marker.jar")),
                    operation + " did not run over the generator classpath of the generation task: " + launches);
        }
    }

    /**
     * Whether a generation takes part and where its compilation writes are conventions of the task too, so values
     * a build sets for them before the source set exists are kept rather than silently replaced.
     */
    @Test
    void keepsTheParticipationAndTheOutputLocationsABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>()
                        .matching { it.name == "generateExtraStateGraphApi" }
                        .configureEach {
                            participates = false
                            compilationOutputLocations = "set by the build"
                        }
                sourceSets.create("extra")
                dependencies {
                    "extraImplementation"("io.github.graphmaquina:graphmaquina-core:%1$s")
                    "extraAnnotationProcessor"("io.github.graphmaquina:graphmaquina-processor:%1$s")
                }
                tasks.register("printExtraGeneration") {
                    val generation = tasks.named<GenerateGraphApi>("generateExtraStateGraphApi")
                    val participates = generation.flatMap { it.participates }
                    val locations = generation.flatMap { it.compilationOutputLocations }
                    doLast {
                        println("EXTRA_PARTICIPATES=" + participates.get())
                        println("EXTRA_LOCATIONS=" + locations.get())
                    }
                }
                """.formatted(PluginFixture.version()));

        Map<String, String> reported = report(fixture.run("printExtraGeneration"));

        assertEquals("false", reported.get("EXTRA_PARTICIPATES"), reported::toString);
        assertEquals("set by the build", reported.get("EXTRA_LOCATIONS"), reported::toString);
    }

    /**
     * The generator classpath is a convention of the generation task too: a classpath a build sets on the
     * generation task of a source set before that source set exists is the whole classpath, which the plugin's
     * default does not join afterwards, while a generation task the build leaves alone keeps that default.
     */
    @Test
    void keepsAGeneratorClasspathABuildSetsBeforeTheSourceSetExists() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.withType<GenerateGraphApi>()
                        .matching { it.name == "generateExtraStateGraphApi" }
                        .configureEach {
                            generatorClasspath.setFrom(files("set-by-the-build.jar"))
                        }
                sourceSets.create("extra")
                tasks.register("printGeneratorClasspaths") {
                    val extra = tasks.named<GenerateGraphApi>("generateExtraStateGraphApi")
                    val main = tasks.named<GenerateGraphApi>("generateStateGraphApi")
                    doLast {
                        println("EXTRA_CLASSPATH=" + extra.get().generatorClasspath.files.map { it.name })
                        println("MAIN_CLASSPATH=" + main.get().generatorClasspath.files.map { it.name })
                    }
                }
                """);

        Map<String, String> reported = report(fixture.run("printGeneratorClasspaths"));

        assertEquals("[set-by-the-build.jar]", reported.get("EXTRA_CLASSPATH"), reported::toString);
        assertTrue(reported.get("MAIN_CLASSPATH").contains("graphmaquina-codegen-" + PluginFixture.version() + ".jar"),
                reported::toString);
    }

    /** The domain with its action spelled with an accent, as a project kept in ISO-8859-1 may spell it. */
    private static Map<String, String> accentedDomain() {
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.Actions", """
                package fx;

                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** The action host of the graph, whose action approves a résumé. */
                public final class Actions {

                    @GraphAction
                    public ActionOutcome approveRésumé(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Orders", PluginFixture.orders("Orders", "ApproveRésumé", "approveRésumé"));
        return sources;
    }

    /** Asserts that {@code result} compiled the {@link #accentedDomain()} the generator read in ISO-8859-1. */
    private static void assertAccentedSourcesBuilt(PluginFixture fixture, BuildResult result) {
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals("ISO-8859-1", fixture.request("main").get("encoding").getAsString());
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), fixture.localExports()::toString);
        assertTrue(fixture.jarContains("fx/ActionsRefs$ApproveRésumé.class"));
    }

    /**
     * Asserts that {@code refused} is the refusal of an {@code -encoding} compiler argument that names UTF-8 where
     * {@code options.encoding} names ISO-8859-1, which the preparation gave before it retired anything.
     */
    private static void assertEncodingContradictionRefused(PluginFixture fixture, BuildResult refused) {
        assertEquals(TaskOutcome.FAILED, refused.task(":compileJava").getOutcome());
        assertTrue(refused.getOutput().contains("cannot run with options.encoding = \"ISO-8859-1\" and the compiler"
                + " argument -encoding UTF-8:"), refused::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * The JDKs Gradle detects on this machine for the build of {@code fixture}, as its {@code javaToolchains} report
     * lists them, and the language version of the JVM the daemon of that build runs on. An installation counts as a
     * JDK when its {@code bin} directory holds {@code javac}; a runtime without one is left out.
     */
    private static Toolchains toolchains(PluginFixture fixture) {
        BuildResult result = fixture.run("javaToolchains", "printGeneratorLauncher");
        Set<Integer> detected = detectedJdkVersions(result);
        return new Toolchains(Integer.parseInt(report(result).get("DAEMON_LANGUAGE_VERSION")),
                List.copyOf(detected));
    }

    /** The language versions, 21 or later, of the JDKs the {@code javaToolchains} report in {@code result} lists. */
    private static Set<Integer> detectedJdkVersions(BuildResult result) {
        Pattern location = Pattern.compile("\\| Location:\\s+(.+)");
        Pattern version = Pattern.compile("\\| Language Version:\\s+(\\d+)");
        Set<Integer> detected = new TreeSet<>();
        Path installation = null;
        for (String line : result.getOutput().lines().toList()) {
            Matcher installed = location.matcher(line);
            Matcher language = version.matcher(line);
            if (installed.find()) {
                installation = Path.of(installed.group(1).strip());
            } else if (language.find() && installation != null) {
                addIfJdk(detected, installation, Integer.parseInt(language.group(1)));
            }
        }
        return detected;
    }

    /** Adds {@code feature} to {@code detected} when it is 21 or later and {@code installation} has a javac. */
    private static void addIfJdk(Set<Integer> detected, Path installation, int feature) {
        Path bin = installation.resolve("bin");
        if (feature >= 21 && (Files.isRegularFile(bin.resolve("javac"))
                || Files.isRegularFile(bin.resolve("javac.exe")))) {
            detected.add(feature);
        }
    }

    /**
     * The JDKs a build of a fixture can select as its toolchain.
     *
     * @param daemon the language version of the JVM the daemon of the build runs on
     * @param detected the language versions, 21 or later, of the JDKs Gradle detects, those whose {@code bin}
     *     directory holds {@code javac}, in ascending order
     */
    private record Toolchains(int daemon, List<Integer> detected) {
    }

    /** The {@code KEY=value} lines a reporting task of the fixture printed. */
    private static Map<String, String> report(BuildResult result) {
        Map<String, String> reported = new LinkedHashMap<>();
        for (String line : result.getOutput().lines().toList()) {
            int separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).matches("[A-Z_]+")) {
                reported.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return reported;
    }
}
