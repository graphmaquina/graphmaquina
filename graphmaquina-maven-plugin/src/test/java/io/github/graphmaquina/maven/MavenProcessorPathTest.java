package io.github.graphmaquina.maven;

import com.google.gson.Gson;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static io.github.graphmaquina.maven.BuildAssertions.assertBuildPublishesTheSameIndex;
import static io.github.graphmaquina.maven.BuildAssertions.assertNothingWasRetired;
import static io.github.graphmaquina.maven.BuildAssertions.assertNothingWasRetiredOrPublished;
import static io.github.graphmaquina.maven.BuildAssertions.assertRefusedBeforeAnythingIsRetired;
import static io.github.graphmaquina.maven.MavenFixture.MAIN_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.arguments;
import static io.github.graphmaquina.maven.MavenFixture.fakeJdk;
import static io.github.graphmaquina.maven.MavenFixture.graphmaquinaVersion;
import static io.github.graphmaquina.maven.MavenFixture.requireProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to and runs its goals. Nothing here instantiates a Mojo.
 *
 * <p>These tests cover the checks that the StateGraph annotation processor is the one javac runs: where the
 * processor path and the {@code -processor} list come from, and which of them javac takes.</p>
 */
class MavenProcessorPathTest {

    /** The two annotation processors Lombok registers, as a {@code -processor} list names them. */
    private static final List<String> LOMBOK_PROCESSORS = List.of(
            "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
            "lombok.launch.AnnotationProcessorHider$ClaimingProcessor");

    /** The StateGraph annotation processor, as a {@code -processor} list names it. */
    private static final String STATEGRAPH_PROCESSOR = "io.github.graphmaquina.processor.StateGraphProcessor";

    /** The processor path of the fixture POM, as {@code it/basic/pom.xml} spells it, Lombok first. */
    private static final String PROCESSOR_PATHS = """
                      <annotationProcessorPaths>
                        <!-- Lombok first: the processor reads the accessors Lombok generates on Order. -->
                        <path>
                          <groupId>org.projectlombok</groupId>
                          <artifactId>lombok</artifactId>
                          <version>${lombok.version}</version>
                        </path>
                        <path>
                          <groupId>io.github.graphmaquina</groupId>
                          <artifactId>graphmaquina-processor</artifactId>
                          <version>${graphmaquina.version}</version>
                        </path>
                      </annotationProcessorPaths>
            """.strip();

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    /**
     * The annotation processor is not optional: it is the only thing that publishes the index of what javac
     * accepted, so a compilation that would run without it — because annotation processing is off, or because
     * the processor is on no path javac reads — is refused by {@code generate}, before the preparation retires
     * the outputs of the previous cycle.
     */
    @Test
    void theAnnotationProcessorMustBeOnTheCompilerPath() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        MavenResult off = fixture.run("-Dmaven.compiler.proc=none", "package");

        assertNotEquals(0, off.exitCode(), off.output());
        assertTrue(off.output().contains("maven.compiler.proc=none switches annotation processing off"),
                off.output());
        assertNothingWasRetiredOrPublished(fixture);

        fixture.editPom("""
                            <path>
                              <groupId>io.github.graphmaquina</groupId>
                              <artifactId>graphmaquina-processor</artifactId>
                              <version>${graphmaquina.version}</version>
                            </path>
                """, "");

        MavenResult missing = fixture.run("package");

        assertNotEquals(0, missing.exitCode(), missing.output());
        assertTrue(missing.output().contains("io.github.graphmaquina:graphmaquina-processor is not on the"
                + " <annotationProcessorPaths> of the default-compile execution"), missing.output());
        assertNothingWasRetiredOrPublished(fixture);
    }

    /**
     * The refusal of a missing processor comes from the generation, before the preparation: it says what the
     * preparation would retire, not that anything has been retired, and the index and the sealed inventory of
     * the previous build are still there afterwards.
     */
    @Test
    void aMissingProcessorIsRefusedBeforeTheIndexIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("""
                            <path>
                              <groupId>io.github.graphmaquina</groupId>
                              <artifactId>graphmaquina-processor</artifactId>
                              <version>${graphmaquina.version}</version>
                            </path>
                """, "");

        MavenResult result = fixture.run("package");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("and the preparation that follows would retire the index of the"
                + " previous compilation"), result.output());
        assertFalse(result.output().contains("has already retired"), result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * maven-compiler-plugin treats an {@code <annotationProcessorPaths>} that holds no path as one that is absent: it
     * gives javac no processor path, so javac finds the annotation processors on the class path, among the
     * dependencies of the project. The check reads it the same way, so a processor that is a dependency of the project
     * builds and publishes. {@code <proc>full</proc>} is configured as well, since from Java 23 on javac runs a
     * processor found on the class path only when annotation processing is requested.
     */
    @Test
    void anEmptyProcessorPathIsNoProcessorPath() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        processorAsDependency(fixture, "<annotationProcessorPaths/>\n          <proc>full</proc>");

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
    }

    /**
     * From Java 23 on, javac runs no annotation processor it finds on the class path unless annotation processing is
     * requested: by {@code -proc:full}, by a {@code -processor} list or by a processor path. A StateGraph processor
     * that is only a dependency of the project is therefore refused by the generation when such a javac compiles,
     * before anything is retired, naming the settings that make javac run it; {@code <proc>full</proc>}, a
     * {@code -processor} list that names it and a processor path that a compiler argument gives javac each build and
     * publish the same index. The test is skipped on a JDK below 23, whose javac runs such a processor anyway.
     */
    @Test
    void aProcessorOnlyOnTheClassPathIsRefusedWhereJavacWouldNotRunIt() throws IOException {
        assumeTrue(Runtime.version().feature() >= 23, "a javac below 23 runs a processor it finds on the class path");
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        processorAsDependency(fixture, "");

        MavenResult implicit = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, implicit,
                "io.github.graphmaquina:graphmaquina-processor is only a dependency of this project, and the"
                        + " default-compile execution of maven-compiler-plugin, which declares no path in"
                        + " <annotationProcessorPaths>, compiles with the JDK running Maven",
                "a JDK of Java " + Runtime.version().feature() + ": from Java 23 on, javac runs no annotation"
                        + " processor it finds on the class path unless annotation processing is requested",
                "Configure <proc>full</proc> for that execution, or declare the processor in"
                        + " <annotationProcessorPaths> of that execution");

        fixture.editPom("<release>21</release>", "<release>21</release>\n          <proc>full</proc>");

        MavenResult full = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, full, published);

        List<String> withStateGraph = new ArrayList<>(LOMBOK_PROCESSORS);
        withStateGraph.add(STATEGRAPH_PROCESSOR);
        String list = "<annotationProcessors>" + annotationProcessors(withStateGraph) + "</annotationProcessors>";
        fixture.editPom("<proc>full</proc>", list);

        MavenResult listed = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, listed, published);

        // A processor path given as a compiler argument requests annotation processing too, and javac then finds the
        // processors on that path alone: Lombok, then the StateGraph processor with what it runs on.
        fixture.editPom(list, "");
        extraArguments(fixture, List.of(), List.of("-processorpath", processorPath(true)));

        MavenResult path = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, path, published);
    }

    /**
     * A processor path given as a compiler argument, {@code -processorpath} or {@code --processor-path}, with a space
     * or an {@code =}, is the only place javac looks for annotation processors, over {@code <annotationProcessorPaths>}
     * and over the class path, and of several such arguments javac takes the last one. So the check reads that last
     * argument, evaluated as Maven evaluates it for the compiler, which completes {@code ${settings.localRepository}},
     * and looks the processor up as the processor class loader of javac does. A path that does not reach the
     * StateGraph processor is refused before anything is retired, even with the processor among the dependencies; one
     * that reaches it builds and publishes: with the processor among no dependency, over an
     * {@code <annotationProcessorPaths>} that holds Lombok alone, through the {@code Class-Path} of a jar's manifest,
     * and from a multi-release jar that holds it only for Java 21 and later. A path argument that is one expression
     * naming an object Maven does not take for a {@code String}, such as {@code ${project.file}}, is left out, as Maven
     * leaves it out, so the argument after it is the path javac takes, and is refused. A processor module path is
     * refused, which StateGraph does not support.
     */
    @Test
    void aProcessorPathArgumentIsTheOnlyPlaceJavacLooksForTheProcessor() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        PathArguments pathArguments = new PathArguments(fixture, published);
        String lombok = processorPath(false);
        String full = processorPath(true);
        processorAsDependency(fixture, "");

        pathArguments.assertRefused(List.of("-processorpath", full, "-processorpath", lombok),
                "the compiler argument -processorpath " + lombok + " of the default-compile execution of"
                        + " maven-compiler-plugin gives javac the processor path it takes, the only place it then"
                        + " looks for annotation processors, and that path reaches no"
                        + " io/github/graphmaquina/processor/StateGraphProcessor.class");

        dropProcessorDependency(fixture);
        pathArguments.assertBuilds(List.of("-processorpath", lombok, "--processor-path", full));

        leaveLombokOnTheProcessorPaths(fixture);
        pathArguments.assertBuilds(List.of("--processor-path=" + full));

        String repository = requireProperty("graphmaquina.mavenLocalRepository");
        pathArguments.assertBuilds(List.of("--processor-path="
                + full.replace(repository, "${settings.localRepository}")));
        pathArguments.assertRefused(List.of("--processor-path="
                + lombok.replace(repository, "${settings.localRepository}")),
                "the compiler argument --processor-path=" + lombok + " of the default-compile execution of"
                        + " maven-compiler-plugin gives javac the processor path it takes");

        pathArguments.assertBuilds(List.of("-processorpath",
                pathingJar(tempDirectory.resolve("pathing.jar"), full).toString()));

        Path processorJar = Path.of(repository).resolve("io/github/graphmaquina/graphmaquina-processor/"
                + graphmaquinaVersion() + "/graphmaquina-processor-" + graphmaquinaVersion() + ".jar");
        Path versionedJar = multiReleaseJar(processorJar, tempDirectory.resolve("versioned processor.jar"), 21);
        pathArguments.assertBuilds(List.of("-processorpath", full.replace(processorJar.toString(),
                versionedJar.toString())));

        // Maven evaluates an argument that is one expression naming an object it does not take for a String to
        // nothing, and leaves it out, so javac takes the argument after it as the processor path.
        pathArguments.assertRefused(List.of("-processorpath", "${project.file}"),
                "the compiler argument -processorpath -Xlint:all,-serial of the default-compile execution of"
                        + " maven-compiler-plugin gives javac the processor path it takes");

        pathArguments.assertRefused(List.of("--processor-module-path", full),
                "the compiler argument --processor-module-path " + full + " of the default-compile execution of"
                        + " maven-compiler-plugin makes javac look for annotation processors on a module path, which"
                        + " StateGraph does not support");
    }

    /**
     * Maven evaluates the compiler arguments when the compiler runs, so a property a plugin sets after
     * {@code generate} has run still reaches javac. A processor path argument that is one such property, not yet set
     * when {@code generate} checks it, is left as it is written and unchecked: here build-helper-maven-plugin sets it
     * in {@code process-sources} to a path that reaches the processor, and the build publishes the same index.
     */
    @Test
    void aProcessorPathPropertySetAfterGenerateIsLeftUnchecked() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<plugins>", "<plugins>" + """
                      <plugin>
                        <groupId>org.codehaus.mojo</groupId>
                        <artifactId>build-helper-maven-plugin</artifactId>
                        <version>3.5.0</version>
                        <executions>
                          <execution>
                            <id>late-processor-path</id>
                            <phase>process-sources</phase>
                            <goals><goal>regex-property</goal></goals>
                            <configuration>
                              <name>late.processor.path</name>
                              <value>%s</value>
                              <regex>^(.*)$</regex>
                              <replacement>$1</replacement>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                """.formatted(processorPath(true)).stripTrailing());
        extraArguments(fixture, List.of(), List.of("-processorpath", "${late.processor.path}"));

        MavenResult late = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, late, published);
    }

    /**
     * javac reads a multi-release jar on its processor path for the release of its own JDK, which need not be the
     * release of the JDK running Maven. So a processor path whose jar holds the processor only for Java 22 and later
     * is refused, before anything is retired, for a forked javac whose JDK declares Java 21, whatever JDK runs Maven:
     * here the one running these tests, which also runs that javac.
     */
    @Test
    void aForkedJavacReadsTheProcessorPathForTheReleaseOfItsOwnJdk() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        Path jdk21 = fakeJdk(tempDirectory, "jdk-21", "21");
        fixture.editPom("<release>21</release>",
                "<release>21</release><fork>true</fork><executable>" + jdk21.resolve("bin/javac") + "</executable>");
        Path processorJar = Path.of(requireProperty("graphmaquina.mavenLocalRepository"))
                .resolve("io/github/graphmaquina/graphmaquina-processor/" + graphmaquinaVersion()
                        + "/graphmaquina-processor-" + graphmaquinaVersion() + ".jar");
        Path versionedJar = multiReleaseJar(processorJar, tempDirectory.resolve("processor for 22.jar"), 22);
        extraArguments(fixture, List.of(), List.of("-processorpath",
                processorPath(true).replace(processorJar.toString(), versionedJar.toString())));

        MavenResult forked = fixture.run("-Dgraphmaquina.javaExecutable=" + jdk21.resolve("bin/java"), "package");

        assertNotEquals(0, forked.exitCode(), forked.output());
        assertTrue(forked.output().contains("of the default-compile execution of maven-compiler-plugin gives javac"
                + " the processor path it takes, the only place it then looks for annotation processors, and that"
                + " path reaches no io/github/graphmaquina/processor/StateGraphProcessor.class"), forked.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * javac runs only the annotation processors a {@code -processor} list names. maven-compiler-plugin turns
     * {@code <annotationProcessors>} into such a list ahead of the compiler arguments, even when it names nothing,
     * and javac takes the last list it is given, so a {@code -processor} compiler argument wins over it. A list that
     * leaves out the StateGraph processor, in either form and an empty {@code <annotationProcessors>} included, is
     * refused by the generation before the preparation retires anything, naming the list javac would take; a list
     * that names it builds and publishes the same index.
     */
    @Test
    void aProcessorListWithoutTheStateGraphProcessorIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        List<String> withStateGraph = new ArrayList<>(LOMBOK_PROCESSORS);
        withStateGraph.add(STATEGRAPH_PROCESSOR);
        String lombokOnly = String.join(",", LOMBOK_PROCESSORS);
        fixture.editPom("<release>21</release>", "<release>21</release>\n          <annotationProcessors/>");

        MavenResult empty = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, empty,
                "the <annotationProcessors> of the default-compile execution of maven-compiler-plugin give javac the"
                        + " processor list '', whose name '' is not a valid qualified class name");

        fixture.editPom("<annotationProcessors/>", "<annotationProcessors>" + annotationProcessors(LOMBOK_PROCESSORS)
                + "</annotationProcessors>");

        MavenResult listed = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, listed,
                "the <annotationProcessors> of the default-compile execution of maven-compiler-plugin give javac the"
                        + " processor list '" + lombokOnly + "', which leaves out " + STATEGRAPH_PROCESSOR);

        fixture.editPom(annotationProcessors(LOMBOK_PROCESSORS), annotationProcessors(withStateGraph));

        MavenResult named = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, named, published);

        fixture.editPom("graphmaquina/main/annotation-manifest.json</arg>",
                "graphmaquina/main/annotation-manifest.json</arg>\n                <arg>-processor</arg>\n"
                        + "                <arg>" + lombokOnly + "</arg>");

        MavenResult argument = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, argument,
                "the compiler argument -processor of the default-compile execution of maven-compiler-plugin gives"
                        + " javac the processor list '" + lombokOnly + "', which leaves out " + STATEGRAPH_PROCESSOR);

        fixture.editPom("<arg>" + lombokOnly + "</arg>", "<arg>" + String.join(",", withStateGraph) + "</arg>");

        MavenResult argumentNamed = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, argumentNamed, published);
    }

    /**
     * The list javac gets from {@code <annotationProcessors>} is the one Maven and maven-compiler-plugin build:
     * Maven splits a text value on every comma into the names of the list, plexus joins the names with commas into
     * one {@code -processor} value, and javac splits that value on every comma, dropping the empty names at its
     * end, and takes each name exactly as written. So a text value and one element holding a comma-separated list
     * that name the StateGraph processor build, the same forms without it are refused, a {@code -processor} list
     * ending with a comma builds, and a name with a space in it, which is not a valid qualified class name, is refused
     * before anything is retired.
     */
    @Test
    void aProcessorListIsReadTheWayJavacGetsIt() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String lombokOnly = String.join(",", LOMBOK_PROCESSORS);
        fixture.editPom("<release>21</release>", "<release>21</release>\n          <annotationProcessors>"
                + lombokOnly + "," + STATEGRAPH_PROCESSOR + "</annotationProcessors>");

        MavenResult text = fixture.run("package");

        assertEquals(0, text.exitCode(), text.output());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);

        fixture.editPom("," + STATEGRAPH_PROCESSOR + "</annotationProcessors>", "</annotationProcessors>");

        MavenResult textWithout = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, textWithout,
                "the <annotationProcessors> of the default-compile execution of maven-compiler-plugin give javac the"
                        + " processor list '" + lombokOnly + "', which leaves out " + STATEGRAPH_PROCESSOR);

        fixture.editPom("<annotationProcessors>" + lombokOnly + "</annotationProcessors>",
                "<annotationProcessors><annotationProcessor>" + lombokOnly + "," + STATEGRAPH_PROCESSOR
                        + "</annotationProcessor></annotationProcessors>");

        MavenResult element = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, element, published);

        fixture.editPom("," + STATEGRAPH_PROCESSOR + "</annotationProcessor>", "</annotationProcessor>");

        MavenResult elementWithout = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, elementWithout,
                "give javac the processor list '" + lombokOnly + "', which leaves out " + STATEGRAPH_PROCESSOR);

        String trailing = lombokOnly + "," + STATEGRAPH_PROCESSOR + ",";
        fixture.editPom("<annotationProcessors><annotationProcessor>" + lombokOnly
                + "</annotationProcessor></annotationProcessors>", "");
        fixture.editPom("graphmaquina/main/annotation-manifest.json</arg>",
                "graphmaquina/main/annotation-manifest.json</arg>\n                <arg>-processor</arg>\n"
                        + "                <arg>" + trailing + "</arg>");

        MavenResult trailingComma = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, trailingComma, published);

        String spaced = LOMBOK_PROCESSORS.get(0) + ", " + STATEGRAPH_PROCESSOR;
        fixture.editPom("<arg>" + trailing + "</arg>", "<arg>" + spaced + "</arg>");

        MavenResult spaces = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, spaces,
                "the compiler argument -processor of the default-compile execution of maven-compiler-plugin gives"
                        + " javac the processor list '" + spaced + "', whose name ' " + STATEGRAPH_PROCESSOR
                        + "' is not a valid qualified class name");
    }

    /**
     * javac takes the last {@code -proc:} option and the last {@code -processor} list it is given, and so do the
     * checks: {@code -proc:none} followed by {@code -proc:full} builds, while {@code -proc:full} followed by
     * {@code -proc:none} is refused, and a {@code -processor} list that names the StateGraph processor followed by
     * one that does not is refused, each before anything is retired.
     */
    @Test
    void theLastProcessingOptionAndTheLastProcessorListAreTheOnesJavacTakes() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String manifest = "graphmaquina/main/annotation-manifest.json</arg>";
        fixture.editPom(manifest,
                manifest + "\n                <arg>-proc:none</arg>\n                <arg>-proc:full</arg>");

        MavenResult full = fixture.run("package");

        assertEquals(0, full.exitCode(), full.output());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);

        fixture.editPom("<arg>-proc:none</arg>\n                <arg>-proc:full</arg>",
                "<arg>-proc:full</arg>\n                <arg>-proc:none</arg>");

        MavenResult none = fixture.run("package");

        assertNotEquals(0, none.exitCode(), none.output());
        assertTrue(none.output().contains("the compiler argument -proc:none switches annotation processing off for"
                + " the default-compile execution of maven-compiler-plugin"), none.output());
        assertNothingWasRetired(fixture, published);

        String lombokOnly = String.join(",", LOMBOK_PROCESSORS);
        fixture.editPom("<arg>-proc:full</arg>\n                <arg>-proc:none</arg>",
                "<arg>-processor</arg>\n                <arg>" + lombokOnly + "," + STATEGRAPH_PROCESSOR + "</arg>\n"
                        + "                <arg>-processor</arg>\n                <arg>" + lombokOnly + "</arg>");

        MavenResult lists = fixture.run("package");

        assertNotEquals(0, lists.exitCode(), lists.output());
        assertTrue(lists.output().contains("gives javac the processor list '" + lombokOnly + "', which leaves out "
                + STATEGRAPH_PROCESSOR), lists.output());
        assertNothingWasRetired(fixture, published);
    }

    /** One {@code <annotationProcessor>} element per name, as {@code <annotationProcessors>} lists them. */
    private static String annotationProcessors(List<String> names) {
        StringBuilder elements = new StringBuilder();
        for (String name : names) {
            elements.append("<annotationProcessor>").append(name).append("</annotationProcessor>");
        }
        return elements.toString();
    }

    /**
     * Writes {@code replacement} where the fixture POM declares its processor path, and makes the StateGraph annotation
     * processor a {@code provided} dependency of the project instead, declared after Lombok, so that javac finds both
     * processors on the class path, Lombok first, when no processor path is left.
     */
    private static void processorAsDependency(MavenFixture fixture, String replacement) throws IOException {
        fixture.editPom(PROCESSOR_PATHS, replacement);
        fixture.editPom("  </dependencies>", """
                    <dependency>
                      <groupId>io.github.graphmaquina</groupId>
                      <artifactId>graphmaquina-processor</artifactId>
                      <version>${graphmaquina.version}</version>
                      <scope>provided</scope>
                    </dependency>
                  </dependencies>
                """.stripTrailing());
    }

    /**
     * Removes the StateGraph annotation processor from the dependencies of the fixture POM, where
     * {@link #processorAsDependency} declared it.
     */
    private static void dropProcessorDependency(MavenFixture fixture) throws IOException {
        fixture.editPom("""
                    <dependency>
                      <groupId>io.github.graphmaquina</groupId>
                      <artifactId>graphmaquina-processor</artifactId>
                      <version>${graphmaquina.version}</version>
                      <scope>provided</scope>
                    </dependency>
                """, "");
    }

    /** Leaves Lombok alone on the {@code <annotationProcessorPaths>} of the fixture POM. */
    private static void leaveLombokOnTheProcessorPaths(MavenFixture fixture) throws IOException {
        fixture.editPom("<release>21</release>", "<release>21</release>\n" + PROCESSOR_PATHS.replace("""
                            <path>
                              <groupId>io.github.graphmaquina</groupId>
                              <artifactId>graphmaquina-processor</artifactId>
                              <version>${graphmaquina.version}</version>
                            </path>
                """, ""));
    }

    /**
     * A processor path of jars of the local repository the fixture builds share, with absolute paths: Lombok, and with
     * {@code stateGraph} the StateGraph annotation processor and the jars it runs on after it. A build that declared
     * the processor before has resolved them all there.
     */
    private static String processorPath(boolean stateGraph) {
        Path repository = Path.of(requireProperty("graphmaquina.mavenLocalRepository"));
        List<String> entries = new ArrayList<>();
        entries.add(repository.resolve("org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar").toString());
        if (stateGraph) {
            for (String artifact : List.of("graphmaquina-processor", "graphmaquina-codegen", "graphmaquina-core")) {
                entries.add(repository.resolve("io/github/graphmaquina/" + artifact + '/' + graphmaquinaVersion()
                        + '/' + artifact + '-' + graphmaquinaVersion() + ".jar").toString());
            }
            entries.add(jarOf(Gson.class).toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    /**
     * Writes a jar at {@code jar} that holds nothing but a manifest whose {@code Class-Path} names every entry of
     * {@code path} as a file URL, so that a processor path of that jar alone reaches them all.
     */
    private static Path pathingJar(Path jar, String path) throws IOException {
        List<String> urls = new ArrayList<>();
        for (String entry : path.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toString());
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", urls));
        new JarOutputStream(Files.newOutputStream(jar), manifest).close();
        return jar;
    }

    /**
     * Copies {@code jar} to {@code target} as a multi-release jar that holds everything outside {@code META-INF} only
     * under {@code META-INF/versions/<release>}, so that a JVM of that release or later reads its classes there and
     * nothing reads them at the top level.
     */
    private static Path multiReleaseJar(Path jar, Path target, int release) throws IOException {
        try (JarFile source = new JarFile(jar.toFile())) {
            Manifest manifest = multiReleaseManifest(source);
            try (JarOutputStream copy = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                for (JarEntry entry : Collections.list(source.entries())) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equals(JarFile.MANIFEST_NAME)) {
                        continue;
                    }
                    copy.putNextEntry(new JarEntry(name.startsWith("META-INF/") ? name
                            : "META-INF/versions/" + release + '/' + name));
                    try (InputStream content = source.getInputStream(entry)) {
                        content.transferTo(copy);
                    }
                    copy.closeEntry();
                }
            }
        }
        return target;
    }

    /** The manifest of {@code source}, or an empty one, marked as the manifest of a multi-release jar. */
    private static Manifest multiReleaseManifest(JarFile source) throws IOException {
        Manifest manifest = source.getManifest() == null ? new Manifest() : new Manifest(source.getManifest());
        manifest.getMainAttributes().putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        return manifest;
    }

    /**
     * Replaces, in the compiler arguments of both compiler executions, the arguments {@code before} that follow the
     * manifest argument with {@code after}.
     */
    private static void extraArguments(MavenFixture fixture, List<String> before, List<String> after)
            throws IOException {
        for (String sourceSet : List.of("main", "test")) {
            String manifest = "graphmaquina/" + sourceSet + "/annotation-manifest.json</arg>";
            fixture.editPom(manifest + arguments(before), manifest + arguments(after));
        }
    }

    /** The jar {@code type} was loaded from, on the classpath of these tests. */
    private static Path jarOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException unusable) {
            throw new IllegalStateException("cannot locate the jar of " + type.getName(), unusable);
        }
    }

    /**
     * The compiler arguments that follow the manifest argument in the fixture POM, replaced one build after another,
     * and what each build is asserted to do with them.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class PathArguments {

        private final MavenFixture fixture;
        private final List<String> published;
        private List<String> current = List.of();

        /** Gives javac {@code next} instead of the previous arguments and asserts that the build publishes. */
        void assertBuilds(List<String> next) throws IOException {
            replaceWith(next);
            assertBuildPublishesTheSameIndex(fixture, fixture.run("package"), published);
        }

        /** Gives javac {@code next} instead of the previous arguments and asserts that the build is refused. */
        void assertRefused(List<String> next, String message) throws IOException {
            replaceWith(next);
            assertRefusedBeforeAnythingIsRetired(fixture, published, fixture.run("package"), message);
        }

        private void replaceWith(List<String> next) throws IOException {
            extraArguments(fixture, current, next);
            current = next;
        }
    }
}
