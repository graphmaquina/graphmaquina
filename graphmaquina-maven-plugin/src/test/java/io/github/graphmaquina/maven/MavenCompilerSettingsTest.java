package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.graphmaquina.maven.BuildAssertions.assertBuildPublishesTheSameIndex;
import static io.github.graphmaquina.maven.BuildAssertions.assertNothingWasRetired;
import static io.github.graphmaquina.maven.BuildAssertions.assertRefusedBeforeAnythingIsRetired;
import static io.github.graphmaquina.maven.MavenFixture.MAIN_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.TEST_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.arguments;
import static io.github.graphmaquina.maven.MavenFixture.fakeJavaHome;
import static io.github.graphmaquina.maven.MavenFixture.fakeJdk;
import static io.github.graphmaquina.maven.MavenFixture.graphmaquinaVersion;
import static io.github.graphmaquina.maven.MavenFixture.requireProperty;
import static io.github.graphmaquina.maven.MavenFixture.testDomain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to and runs its goals. Nothing here instantiates a Mojo.
 *
 * <p>These tests cover the settings of maven-compiler-plugin that the generator and the completion have to agree
 * with, and the ways Maven can give them: the release, source, target and encoding; the JDK, the toolchain and the
 * forked javac; the {@code debug}, {@code proc}, {@code failOnError} and {@code useIncrementalCompilation} settings,
 * which decide what javac writes for the completion to read; the output directories; the manifest argument
 * and the other compiler arguments, as a list, as the deprecated map and in an execution of their own; and settings
 * given as properties. The {@code .mavenrc} test is here because the tests about the JDK compare the compiler with
 * the JDK running Maven, which such a file could replace.</p>
 */
class MavenCompilerSettingsTest {

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    /**
     * The generator runs under the {@code java} the build selected for it, not under the default the JVM running
     * Maven provides. The selection is that very JDK reached through a link of its own — javac compiles inside
     * that JVM, so the generator must be on the same JDK — and only its path tells it apart from the default.
     */
    @Test
    void runsTheGeneratorUnderTheSelectedJavaExecutable() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path linked = Files.createSymbolicLink(tempDirectory.resolve("linked-jdk"),
                Path.of(System.getProperty("java.home")));
        String selected = linked.resolve("bin").resolve("java").toString();

        MavenResult result = fixture.run("-X", "-Dgraphmaquina.javaExecutable=" + selected, "generate-sources");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("running the StateGraph generator: " + selected + " -cp"),
                "the generator did not run under " + selected);
        assertTrue(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
        // The link leads out of the temporary directory, which JUnit warns about when it deletes that directory.
        Files.delete(linked);
    }

    /**
     * Maven converts the deprecated {@code <compilerArguments>} map into a sorted map, so maven-compiler-plugin
     * passes its entries by key, each key once with its last value, and the checks read them the same way: of two
     * manifest arguments the last one is the one javac reads, and of {@code proc:none} and {@code proc:full},
     * written in that order, javac gets {@code -proc:none} last, which is refused before anything is retired.
     */
    @Test
    void theCompilerArgumentsMapIsReadByKeyWithTheLastValueOfEachKey() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/"
                + "annotation-manifest.json</arg>\n", "");
        fixture.editPom("<id>default-compile</id>\n            <configuration>",
                "<id>default-compile</id>\n            <configuration>\n"
                        + "              <compilerArguments>\n"
                        + "                <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/"
                        + "elsewhere/annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                        + "                <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/"
                        + "main/annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                        + "              </compilerArguments>");

        MavenResult last = fixture.run("compile");

        assertEquals(0, last.exitCode(), last.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);

        fixture.editPom("              </compilerArguments>",
                "                <proc:none/>\n                <proc:full/>\n              </compilerArguments>");

        MavenResult sorted = fixture.run("compile");

        assertNotEquals(0, sorted.exitCode(), sorted.output());
        assertTrue(sorted.output().contains("the compiler argument -proc:none switches annotation processing off"),
                sorted.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * A compiler setting that comes from a {@code maven.compiler.*} property is checked the same way as one
     * configured in XML, whether it is a release below Java 21 or a javac forked at another JDK, and a project
     * that configures no release at all is refused as well: the default source and target of
     * {@code maven-compiler-plugin} are far below what the generated vocabulary parses.
     */
    @Test
    void propertyConfiguredCompilerSettingsAreCheckedToo() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "");
        fixture.editPom("<lombok.version>1.18.48</lombok.version>",
                "<lombok.version>1.18.48</lombok.version>\n"
                        + "    <maven.compiler.release>17</maven.compiler.release>");

        MavenResult property = fixture.run("generate-sources");

        assertNotEquals(0, property.exitCode(), property.output());
        assertTrue(property.output().contains("compiles with maven.compiler.release=17"), property.output());

        // Back to the supported configuration, then take the release away entirely.
        fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "");

        MavenResult absent = fixture.run("generate-sources");

        assertNotEquals(0, absent.exitCode(), absent.output());
        assertTrue(absent.output().contains("configures no release"), absent.output());

        fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        MavenResult forked = fixture.run("-Dmaven.compiler.fork=true",
                "-Dmaven.compiler.executable=/nonexistent/javac", "generate-sources");

        assertNotEquals(0, forked.exitCode(), forked.output());
        assertTrue(forked.output().contains("forks javac at /nonexistent/javac"), forked.output());
    }

    /**
     * A compiler property set both for the JVM running Maven and in the POM is read the way Maven evaluates the
     * compiler's own parameter: the property of the session wins over the property of the project, so the
     * release this check reads is the release javac compiles with.
     */
    @Test
    void aJvmSystemPropertyWinsOverTheProjectProperty() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "");
        fixture.editPom("<lombok.version>1.18.48</lombok.version>",
                "<lombok.version>1.18.48</lombok.version>\n"
                        + "    <maven.compiler.release>17</maven.compiler.release>");
        // A project sets a system property of the JVM running its build here, as MAVEN_OPTS does.
        Path options = Files.createDirectories(fixture.projectDirectory().resolve(".mvn")).resolve("jvm.config");
        Files.writeString(options, "-Dmaven.compiler.release=21\n", StandardCharsets.UTF_8);

        MavenResult result = fixture.run("compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.exists("target/classes/fx/OrdersGraph.class"));
    }

    /**
     * Maven merges the configuration the compiler declares for all of its executions into each execution, and
     * a list the execution declares itself replaces the plugin's: the test compilation of this POM compiles
     * with its own arguments alone, so the main manifest the plugin level names is not one it reads.
     */
    @Test
    void anExecutionsCompilerArgumentsReplaceThoseOfThePlugin() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String mainArguments = """
                          <compilerArgs>
                            <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/\
                annotation-manifest.json</arg>
                            <arg>-Xlint:all,-serial</arg>
                            <arg>-Werror</arg>
                          </compilerArgs>
                """;
        fixture.editPom("""
                          <execution>
                            <id>default-compile</id>
                            <configuration>
                """ + mainArguments.indent(4) + """
                            </configuration>
                          </execution>
                """, "");
        fixture.editPom("<release>21</release>\n", "<release>21</release>\n" + mainArguments);

        MavenResult result = fixture.run("test-compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.inventoryIsSealed("test"));
    }

    /**
     * maven-compiler-plugin still passes the entries of the deprecated {@code <compilerArguments>} map to javac,
     * with a dash prepended to a key that has none, and the test compilation takes {@code <testCompilerArguments>}
     * over that map.
     * The checks of the generation read the same arguments: a manifest argument given only as an entry of the map
     * is the one javac reads, so both compilations build, and a {@code proc:none} entry switches annotation
     * processing off and is refused before anything is retired.
     */
    @Test
    void theCompilerArgumentsMapIsReadTheWayTheCompilerReadsIt() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (String sourceSet : List.of("main", "test")) {
            fixture.editPom("<arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/"
                    + sourceSet + "/annotation-manifest.json</arg>\n", "");
        }
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <compilerArguments>\n"
                + "            <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/main/"
                + "annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                + "          </compilerArguments>");
        fixture.editPom("<id>default-testCompile</id>\n            <configuration>",
                "<id>default-testCompile</id>\n            <configuration>\n"
                        + "              <testCompilerArguments>\n"
                        + "                <Agraphmaquina.annotationManifest>${project.build.directory}/graphmaquina/"
                        + "test/annotation-manifest.json</Agraphmaquina.annotationManifest>\n"
                        + "              </testCompilerArguments>");

        MavenResult mapped = fixture.run("test-compile");

        assertEquals(0, mapped.exitCode(), mapped.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.inventoryIsSealed("test"));
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        assertTrue(published.contains("fx.Orders"), published::toString);
        fixture.editPom("<compilerArguments>\n", "<compilerArguments>\n            <proc:none/>\n");

        MavenResult off = fixture.run("test-compile");

        assertNotEquals(0, off.exitCode(), off.output());
        assertTrue(off.output().contains("the compiler argument -proc:none switches annotation processing off for"
                + " the default-compile execution of maven-compiler-plugin"), off.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * A compilation that can end without the classes the completion publishes is refused by the generation,
     * before the preparation retires anything: {@code failOnError=false} carries the build on past a
     * compilation javac rejected, and {@code proc=only}, as a parameter or as a compiler argument, runs the
     * annotation processors and compiles nothing.
     */
    @Test
    void aCompilationThatCanEndWithoutClassesIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        Map<String, String> broken = MavenFixture.domainSources();
        broken.put("fx.Broken", """
                package fx;

                /** A declaration javac refuses, so the compilation of this fixture fails. */
                public final class Broken {

                    private final int count = "not a number";
                }
                """);
        fixture.writeSources(broken);

        MavenResult failOnError = fixture.run("-Dmaven.compiler.failOnError=false", "package");

        assertNotEquals(0, failOnError.exitCode(), failOnError.output());
        assertTrue(failOnError.output().contains("maven.compiler.failOnError=false lets the build continue past a"
                + " compilation the default-compile execution of maven-compiler-plugin rejects"),
                failOnError.output());
        assertNothingWasRetired(fixture, published);

        MavenResult procOnly = fixture.run("-Dmaven.compiler.proc=only", "compile");

        assertNotEquals(0, procOnly.exitCode(), procOnly.output());
        assertTrue(procOnly.output().contains("maven.compiler.proc=only makes the default-compile execution of"
                + " maven-compiler-plugin run the annotation processors and compile nothing"), procOnly.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("graphmaquina/main/annotation-manifest.json</arg>",
                "graphmaquina/main/annotation-manifest.json</arg>\n                <arg>-proc:only</arg>");

        MavenResult argument = fixture.run("compile");

        assertNotEquals(0, argument.exitCode(), argument.output());
        assertTrue(argument.output().contains("the compiler argument -proc:only makes the default-compile"
                + " execution of maven-compiler-plugin run the annotation processors"), argument.output());
        assertTrue(argument.output().contains("Remove that argument from the configuration of that execution."),
                argument.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * The completion attributes each class file to the source javac compiled it from through its {@code SourceFile}
     * attribute, which javac leaves out when it is given a {@code -g:} option and none of its {@code -g:} options,
     * whatever their order, lists {@code source}. maven-compiler-plugin passes {@code <debuglevel>} as such an option
     * while {@code <debug>} is on, as it is by default. A debug level or a compiler argument that leaves the attribute
     * out is refused by the generation, before anything is retired; {@code <debug>false</debug>}, which passes no
     * {@code -g} option at all, and a {@code -g:source} before a {@code -g:none} each build and publish.
     */
    @Test
    void aDebugSettingThatLeavesOutTheSourceFileIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<release>21</release>",
                "<release>21</release>\n          <debuglevel>lines,vars</debuglevel>");

        MavenResult level = fixture.run("package");

        assertNotEquals(0, level.exitCode(), level.output());
        assertTrue(level.output().contains("<debuglevel>lines,vars</debuglevel> makes javac write the class files of"
                + " the default-compile execution of maven-compiler-plugin without the SourceFile attribute"),
                level.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<debuglevel>lines,vars</debuglevel>",
                "<debug>false</debug>\n          <debuglevel>lines,vars</debuglevel>");

        MavenResult off = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, off, published);

        fixture.editPom("\n          <debug>false</debug>\n          <debuglevel>lines,vars</debuglevel>", "");
        String manifest = "graphmaquina/main/annotation-manifest.json</arg>";
        fixture.editPom(manifest, manifest + "\n                <arg>-g:none</arg>");

        MavenResult none = fixture.run("package");

        assertNotEquals(0, none.exitCode(), none.output());
        assertTrue(none.output().contains("the compiler argument -g:none makes javac write the class files of the"
                + " default-compile execution of maven-compiler-plugin without the SourceFile attribute"),
                none.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<arg>-g:none</arg>", "<arg>-g:source</arg>\n                <arg>-g:none</arg>");

        MavenResult source = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, source, published);
    }

    /**
     * With {@code useIncrementalCompilation} false, maven-compiler-plugin compiles only the sources whose class file
     * is missing or older than the source, so a build over the outputs of a previous one would hand javac the
     * vocabulary the preparation retired without the hand-written declarations the annotation processor must see.
     * The setting is refused by the generation before the preparation retires anything, whether the configuration
     * of the execution sets it or the {@code maven.compiler.useIncrementalCompilation} property does, and the
     * default, {@code true}, builds.
     */
    @Test
    void aCompilationOfStaleSourcesOnlyIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<useIncrementalCompilation>true</useIncrementalCompilation>",
                "<useIncrementalCompilation>false</useIncrementalCompilation>");

        MavenResult configured = fixture.run("package");

        assertNotEquals(0, configured.exitCode(), configured.output());
        assertTrue(fixture.exists(MAIN_EXPORTS), "the index of the previous build was retired:\n"
                + configured.output());
        assertNothingWasRetired(fixture, published);
        assertTrue(configured.output().contains("<useIncrementalCompilation>false</useIncrementalCompilation> makes"
                + " the default-compile execution of maven-compiler-plugin compile only the sources"),
                configured.output());
        assertTrue(configured.output().contains("Leave useIncrementalCompilation at its default, true."),
                configured.output());

        fixture.editPom("<useIncrementalCompilation>false</useIncrementalCompilation>", "");

        MavenResult property = fixture.run("-Dmaven.compiler.useIncrementalCompilation=false", "package");

        assertNotEquals(0, property.exitCode(), property.output());
        assertTrue(property.output().contains("maven.compiler.useIncrementalCompilation=false makes the"
                + " default-compile execution of maven-compiler-plugin compile only the sources"), property.output());
        assertNothingWasRetired(fixture, published);

        MavenResult defaulted = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, defaulted, published);
    }

    /**
     * A compiler that would not run under the JDK of the generator, or under a release the generated
     * vocabulary does not compile with, stops the build with what disagrees and how to fix it.
     */
    @Test
    void aCompilerThatDisagreesWithTheGeneratorFailsTheBuild() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        fixture.editPom("<release>21</release>", "<release>17</release>");
        MavenResult release = fixture.run("generate-sources");
        assertNotEquals(0, release.exitCode(), release.output());
        assertTrue(release.output().contains("compiles with <release>17</release>"), release.output());
        fixture.editPom("<release>17</release>", "<release>21</release>");

        String forked = "<fork>true</fork><executable>/opt/other-jdk/bin/javac</executable>";
        fixture.editPom("<release>21</release>", "<release>21</release>" + forked);
        MavenResult forkedResult = fixture.run("generate-sources");
        assertNotEquals(0, forkedResult.exitCode(), forkedResult.output());
        assertTrue(forkedResult.output().contains("forks javac at /opt/other-jdk/bin/javac"),
                forkedResult.output());
        fixture.editPom(forked, "");

        fixture.editPom("<release>21</release>",
                "<release>21</release><jdkToolchain><version>77</version></jdkToolchain>");
        Path other = fakeJdk(tempDirectory, "jdk-77", null);
        Path declared = toolchains("toolchains.xml", other);
        Path none = toolchains("no-toolchains.xml", null);
        MavenResult toolchain = fixture.run("-t", declared.toString(), "-gt", none.toString(), "generate-sources");
        assertNotEquals(0, toolchain.exitCode(), toolchain.output());
        assertTrue(toolchain.output().contains("the jdk toolchain {version=77}, which selects "),
                toolchain.output());
        assertTrue(toolchain.output().contains("jdk-77"), toolchain.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * javac forked at the {@code javac} of the very JDK the generator runs on is on the same JDK, whatever path
     * names it, so the build compiles.
     */
    @Test
    void aCompilerForkedAtTheJdkOfTheGeneratorCompiles() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        // ${java.home} is the JVM running Maven, which is where the generator runs when nothing else is selected.
        fixture.editPom("<release>21</release>",
                "<release>21</release><fork>true</fork><executable>${java.home}/bin/javac</executable>");

        MavenResult result = fixture.run("compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.exists("target/classes/fx/OrdersGraph.class"));
    }

    /**
     * A javac that runs inside the JVM running Maven compiles on that JDK, so a generator configured to run on
     * another JDK is refused before anything is generated.
     */
    @Test
    void aGeneratorOnAnotherJdkThanJavacIsRefused() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path other = fakeJdk(tempDirectory, "other-jdk", null);

        MavenResult result = fixture.run("-Dgraphmaquina.javaExecutable=" + other.resolve("bin/java"),
                "generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the default-compile execution of maven-compiler-plugin compiles with"
                + " the JDK running Maven"), result.output());
        assertTrue(result.output().contains("while the StateGraph generator runs under " + other.resolve("bin/java")),
                result.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * A {@code <jdkToolchain>} that matches no toolchain of the build is no disagreement: maven-compiler-plugin
     * then compiles with the toolchain selected for the build or, without one, on the JDK running Maven, which
     * is where the generator runs too.
     */
    @Test
    void aJdkToolchainThatMatchesNoToolchainFallsBackLikeTheCompiler() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>",
                "<release>21</release><jdkToolchain><version>21</version></jdkToolchain>");
        Path none = toolchains("no-toolchains.xml", null);

        MavenResult result = fixture.run("-t", none.toString(), "-gt", none.toString(), "compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
    }

    /**
     * An empty {@code <jdkToolchain/>} is a requirement every jdk toolchain meets, so maven-compiler-plugin forks the
     * javac of the first jdk toolchain of the build, as it would for any requirement that toolchain meets. The check
     * compares that javac with the JDK of the generator, and refuses it here, where it belongs to another JDK, before
     * anything is generated.
     */
    @Test
    void anEmptyJdkToolchainSelectsTheFirstJdkToolchainLikeTheCompiler() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release><jdkToolchain/>");
        Path other = fakeJdk(tempDirectory, "jdk-77", null);
        Path declared = toolchains("toolchains.xml", other);
        Path none = toolchains("no-toolchains.xml", null);

        MavenResult result = fixture.run("-t", declared.toString(), "-gt", none.toString(), "generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the default-compile execution of maven-compiler-plugin compiles with the"
                + " first jdk toolchain of this build, as its empty <jdkToolchain> selects, "
                + other.resolve("bin/javac")), result.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * The generator parses sources with the jdk.compiler module and runs on Java 21 or later, so whatever
     * {@code java} it is given is checked before anything is generated: one with no javac beside it belongs to a
     * runtime, and one whose JDK declares an older Java in its release file is too old. Both are refused, although
     * either launcher would start the generator here, since both run the JDK of these tests.
     */
    @Test
    void theGeneratorIsStartedOnlyWithTheJavaOfAJdk21() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path runtime = fakeJavaHome(tempDirectory, "runtime", null, "java");

        MavenResult result = fixture.run("-Dgraphmaquina.javaExecutable=" + runtime.resolve("bin/java"),
                "generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the configured <javaExecutable>, " + runtime.resolve("bin/java")
                + ", has no javac beside it, so it belongs to a runtime, not a JDK"), result.output());

        Path older = fakeJdk(tempDirectory, "jdk-17", "17.0.2");

        MavenResult old = fixture.run("-Dgraphmaquina.javaExecutable=" + older.resolve("bin/java"),
                "generate-sources");

        assertNotEquals(0, old.exitCode(), old.output());
        assertTrue(old.output().contains("the StateGraph generator needs Java 21 or later, but the configured"
                + " <javaExecutable>, " + older.resolve("bin/java") + ", belongs to Java 17"), old.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * A compiler configured to read another annotation manifest than the one the generator writes is refused
     * before anything is generated, with the argument that execution must declare.
     */
    @Test
    void aCompilerThatReadsAnotherManifestFailsTheBuild() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("graphmaquina/main/annotation-manifest.json",
                "graphmaquina/elsewhere/annotation-manifest.json");

        MavenResult result = fixture.run("generate-sources");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("graphmaquina/elsewhere/annotation-manifest.json"), result.output());
        assertTrue(result.output().contains("Declare exactly this compiler argument for that execution:"
                + " -Agraphmaquina.annotationManifest="), result.output());
        assertFalse(fixture.exists("target/graphmaquina/main/annotation-manifest.json"));
    }

    /**
     * javac resolves a relative manifest path against the directory it runs in. A javac that compiles inside Maven
     * runs in the directory Maven was started in, which in a multi-module build is the directory of the parent, not of
     * the module, while a forked javac runs in the directory of the module. The check resolves the path the same
     * way: in the module of a build started from its parent, a relative manifest path is refused before anything is
     * retired, naming the file javac would read, and the same path builds with a forked javac.
     */
    @Test
    void aRelativeManifestPathIsResolvedWhereJavacRuns() throws IOException {
        MavenFixture parent = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenFixture module = parent.module();
        assertEquals(0, parent.run("package").exitCode());
        List<String> published = module.localExportsOf(MAIN_EXPORTS);
        parent.editPom("-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/",
                "-Agraphmaquina.annotationManifest=target/graphmaquina/main/");

        MavenResult inside = parent.run("package");

        assertNotEquals(0, inside.exitCode(), inside.output());
        assertTrue(inside.output().contains("reads the annotation manifest"
                + " target/graphmaquina/main/annotation-manifest.json, which javac, compiling inside the JVM running"
                + " Maven, resolves against the directory Maven was started in, to " + parent.projectDirectory()
                .toRealPath().resolve("target/graphmaquina/main/annotation-manifest.json")), inside.output());
        assertNothingWasRetired(module, published);

        // ${java.home} is the JVM running Maven, which is where the generator runs when nothing else is selected.
        parent.editPom("<release>21</release>",
                "<release>21</release><fork>true</fork><executable>${java.home}/bin/javac</executable>");

        MavenResult forked = parent.run("package");

        assertBuildPublishesTheSameIndex(module, forked, published);
    }

    /**
     * The completion reads the class output of the project, where javac writes the classes of a compilation unless its
     * compiler execution moves them: through {@code <outputDirectory>}, through the
     * {@code maven.compiler.outputDirectory} property, which only the main compilation reads, or through
     * {@code <multiReleaseOutput>}, which moves the classes of the main compilation below {@code META-INF/versions}.
     * Each is refused by the generation of that compilation before anything is retired, while an
     * {@code <outputDirectory>} that names the class output of the project, here relative to the project directory,
     * builds.
     */
    @Test
    void aCompilerThatWritesItsClassesElsewhereIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        List<String> testPublished = fixture.localExportsOf(TEST_EXPORTS);
        String main = "<id>default-compile</id>\n            <configuration>";
        String elsewhere = "<outputDirectory>${project.build.directory}/elsewhere-classes</outputDirectory>";
        fixture.editPom(main, main + "\n              " + elsewhere);

        MavenResult moved = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, moved,
                "/elsewhere-classes</outputDirectory> makes the default-compile execution of maven-compiler-plugin"
                        + " write its classes to ");

        fixture.editPom(elsewhere, "<multiReleaseOutput>true</multiReleaseOutput>");

        MavenResult versioned = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, versioned,
                "<multiReleaseOutput>true</multiReleaseOutput> makes the default-compile execution of"
                        + " maven-compiler-plugin write its classes to ");

        String relative = "<outputDirectory>target/classes</outputDirectory>";
        fixture.editPom("<multiReleaseOutput>true</multiReleaseOutput>", relative);

        MavenResult same = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, same, published);

        fixture.editPom("\n              " + relative, "");
        Path classes = fixture.projectDirectory().resolve("target/property-classes");

        MavenResult property = fixture.run("-Dmaven.compiler.outputDirectory=" + classes, "package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, property,
                "maven.compiler.outputDirectory=" + classes + " makes the default-compile execution of"
                        + " maven-compiler-plugin write its classes to ");

        String test = "<id>default-testCompile</id>\n            <configuration>";
        fixture.editPom(test, test + "\n              "
                + "<outputDirectory>${project.build.directory}/elsewhere-test-classes</outputDirectory>");

        MavenResult tests = fixture.run("package");

        assertNotEquals(0, tests.exitCode(), tests.output());
        assertTrue(tests.output().contains("/elsewhere-test-classes</outputDirectory> makes the default-testCompile"
                + " execution of maven-compiler-plugin write its classes to "), tests.output());
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(testPublished, fixture.localExportsOf(TEST_EXPORTS));
    }

    /**
     * Maven evaluates a parameter of the compiler before it hands it over, completing what the effective model leaves
     * for it, such as {@code ${session.executionRootDirectory}}, the directory the build was started in, and so does
     * the generation. An {@code <outputDirectory>} that names the class output of the project through that expression
     * builds and publishes, and so does the {@code maven.compiler.outputDirectory} property spelled the same way, while
     * one that names another directory through it is refused before anything is retired, naming the directory it
     * evaluates to.
     */
    @Test
    void anOutputDirectoryIsReadAsMavenEvaluatesIt() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String main = "<id>default-compile</id>\n            <configuration>";
        String classes = "<outputDirectory>${session.executionRootDirectory}/target/classes</outputDirectory>";
        fixture.editPom(main, main + "\n              " + classes);

        MavenResult same = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, same, published);

        String elsewhere = "<outputDirectory>${session.executionRootDirectory}/target/elsewhere-classes"
                + "</outputDirectory>";
        fixture.editPom(classes, elsewhere);

        MavenResult moved = fixture.run("package");

        assertNotEquals(0, moved.exitCode(), moved.output());
        Matcher refusal = Pattern.compile(Pattern.quote(elsewhere + " makes the default-compile execution of"
                + " maven-compiler-plugin write its classes to ") + "(.+?), while StateGraph completes")
                .matcher(moved.output());
        assertTrue(refusal.find(), moved.output());
        Path written = Path.of(refusal.group(1));
        assertEquals(fixture.projectDirectory().resolve("target").toRealPath(), written.getParent().toRealPath(),
                moved.output());
        assertEquals("elsewhere-classes", written.getFileName().toString(), moved.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("\n              " + elsewhere, "");

        MavenResult property = fixture.run(
                "-Dmaven.compiler.outputDirectory=${session.executionRootDirectory}/target/classes", "package");

        assertBuildPublishesTheSameIndex(fixture, property, published);
    }

    /**
     * javac takes the last {@code -d} and the last {@code -s} it is given, and maven-compiler-plugin passes its own
     * ahead of the compiler arguments. A {@code -d} compiler argument therefore moves the classes, and the index the
     * annotation processor publishes beside them, away from the class output the completion reads, and a {@code -s}
     * one moves the sources that processor writes away from the directory the preparation retires them from. Either
     * is refused before anything is retired, while a {@code -d} and a {@code -s} that name the directories of the
     * project build, and so does a relative {@code -d} that names the class output through a symbolic link followed
     * by {@code ..}, which javac resolves as the file system does: the link first, then the {@code ..}. That part is
     * aborted where symbolic links cannot be created.
     */
    @Test
    void outputArgumentsThatMoveWhatJavacWritesAreRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String manifest = "graphmaquina/main/annotation-manifest.json</arg>";
        String classes = arguments(List.of("-d", "${project.build.directory}/elsewhere-classes"));
        fixture.editPom(manifest, manifest + classes);

        MavenResult moved = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, moved,
                "/elsewhere-classes of the default-compile execution of maven-compiler-plugin makes javac write its"
                        + " classes to ");

        String sources = arguments(List.of("-s", "${project.build.directory}/elsewhere-sources"));
        fixture.editPom(manifest + classes, manifest + sources);

        MavenResult generated = fixture.run("package");

        assertRefusedBeforeAnythingIsRetired(fixture, published, generated,
                "/elsewhere-sources of the default-compile execution of maven-compiler-plugin makes the annotation"
                        + " processor write its sources to ");

        String same = arguments(List.of("-d", "${project.build.outputDirectory}", "-s",
                "${project.build.directory}/generated-sources/annotations"));
        fixture.editPom(manifest + sources, manifest + same);

        MavenResult kept = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, kept, published);

        Path inner = Files.createDirectories(fixture.projectDirectory().resolve("target/inner"));
        try {
            Files.createSymbolicLink(fixture.projectDirectory().resolve("inner"), inner);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        String throughLink = arguments(List.of("-d", "inner/../classes"));
        fixture.editPom(manifest + same, manifest + throughLink);

        MavenResult linked = fixture.run("package");

        assertBuildPublishesTheSameIndex(fixture, linked, published);
    }

    /**
     * javac reads the sources of a project in the encoding the compiler is given, which defaults to
     * {@code project.build.sourceEncoding}, and at the release it compiles for, so the generator is told both: an
     * accented identifier of a project kept in ISO-8859-1 reaches the generated vocabulary intact.
     */
    @Test
    void passesTheSourceEncodingAndTheReleaseOfTheProjectToTheGenerator() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                "<project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>");
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("21", fixture.requestValue("main", "release"));
        assertEquals("ISO-8859-1", fixture.requestValue("main", "encoding"));
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), result.output());
    }

    /**
     * The encoding the compiler is configured with wins over the one of the project, and a compiler that names no
     * release compiles for its target, so the generator is told those.
     *
     * <p>The javac of JDK 25 warns at {@code -source 21} without {@code --release} that the location of system
     * modules is not set, since 21 is below its own release, and {@code -Werror} would turn that into a failure.
     * Both compilations of this fixture therefore turn off javac's {@code options} lint, which that warning belongs
     * to, so the test passes on JDK 25 as well.</p>
     */
    @Test
    void passesTheEncodingAndTheTargetOfTheCompilerToTheGenerator() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<source>21</source>\n"
                + "          <target>21</target>\n"
                + "          <encoding>ISO-8859-1</encoding>");
        for (String sourceSet : List.of("main", "test")) {
            String manifest = "graphmaquina/" + sourceSet + "/annotation-manifest.json</arg>\n";
            fixture.editPom(manifest + "                <arg>-Xlint:all,-serial</arg>",
                    manifest + "                <arg>-Xlint:all,-serial,-options</arg>");
        }
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("21", fixture.requestValue("main", "release"));
        assertEquals("ISO-8859-1", fixture.requestValue("main", "encoding"));
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), result.output());
    }

    /**
     * A javac that compiles inside Maven reads the sources through a file manager maven-compiler-plugin opens in the
     * encoding it is configured with, which then wins over every {@code -encoding} compiler argument, and without one
     * in the last {@code -encoding} compiler argument. The generator is told the encoding javac reads in either way: an
     * accented identifier of a project kept in ISO-8859-1 reaches the generated vocabulary intact, whether the project
     * names no encoding and passes {@code -encoding ISO-8859-1}, or names ISO-8859-1 and passes {@code -encoding latin1},
     * an alias of that charset, the request then naming the configured ISO-8859-1.
     */
    @Test
    void aJavacInsideMavenReadsTheConfiguredEncodingElseTheLastEncodingArgument() throws IOException {
        MavenFixture argument = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("argument")),
                fixtureRepository);
        argument.editPom("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>", "");
        addMainCompilerArguments(argument, "<arg>-encoding</arg><arg>ISO-8859-1</arg>");
        MavenFixture configured = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("configured")),
                fixtureRepository);
        configured.editPom("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
                "<project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>");
        addMainCompilerArguments(configured, "<arg>-encoding</arg><arg>latin1</arg>");

        for (MavenFixture fixture : List.of(argument, configured)) {
            fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

            MavenResult result = fixture.run("package");

            assertEquals(0, result.exitCode(), result.output());
            assertEquals("ISO-8859-1", fixture.requestValue("main", "encoding"));
            assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), result.output());
        }
    }

    /**
     * {@code <forceJavacCompilerUse>} or {@code maven.compiler.forceLegacyJavacApi} keeps javac inside Maven but runs it
     * through {@code com.sun.tools.javac.Main}, which reads and writes every source in the last {@code -encoding} it is
     * given, as a forked javac does. A project kept in ISO-8859-1 whose {@code project.build.sourceEncoding} says UTF-8
     * builds with {@code -encoding ISO-8859-1} as a compiler argument, either way: the generator is told ISO-8859-1,
     * and the accented identifier reaches the generated vocabulary intact.
     */
    @Test
    void aForcedLegacyJavacReadsTheLastEncodingArgumentLikeAForkedOne() throws IOException {
        MavenFixture configured = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("configured")),
                fixtureRepository);
        configured.editPom("<release>21</release>",
                "<release>21</release><forceJavacCompilerUse>true</forceJavacCompilerUse>");
        MavenFixture property = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("property")),
                fixtureRepository);

        for (MavenFixture fixture : List.of(configured, property)) {
            addMainCompilerArguments(fixture, "<arg>-encoding</arg><arg>ISO-8859-1</arg>");
            fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

            MavenResult result = fixture == configured
                    ? fixture.run("package")
                    : fixture.run("-Dmaven.compiler.forceLegacyJavacApi=true", "package");

            assertEquals(0, result.exitCode(), result.output());
            assertEquals("ISO-8859-1", fixture.requestValue("main", "encoding"));
            assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), result.output());
        }
    }

    /**
     * A javac that compiles inside Maven reads every source, those the annotation processors generate included, in the
     * encoding maven-compiler-plugin is configured with, but writes the sources an annotation processor generates in
     * the last {@code -encoding} it is given, so it would read them back in another charset than they were written in:
     * here the StateGraph processor's sources, written in UTF-16 and read back in UTF-8, would fail javac after the
     * preparation retired the previous outputs. An {@code -encoding} compiler argument that names another charset than
     * the configured encoding is therefore refused by the generation, before anything is retired, while one that names
     * an alias of the configured charset builds.
     */
    @Test
    void anEncodingArgumentThatDisagreesWithTheConfiguredEncodingInsideMavenIsRefusedBeforeAnythingIsRetired()
            throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeSources(accentedDomain(), StandardCharsets.UTF_8);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        addMainCompilerArguments(fixture, "<arg>-encoding</arg><arg>UTF-16</arg>");

        MavenResult refused = fixture.run("package");

        assertNotEquals(0, refused.exitCode(), refused.output());
        assertTrue(refused.output().contains("project.build.sourceEncoding=UTF-8 and the compiler argument -encoding"
                + " UTF-16 of the default-compile execution of maven-compiler-plugin name two charsets for a javac that"
                + " compiles inside Maven"), refused.output());
        assertTrue(refused.output().contains("Remove that -encoding from the compiler arguments of that execution, or"
                + " configure <encoding>UTF-16</encoding> for it."), refused.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<arg>UTF-16</arg>", "<arg>utf8</arg>");

        MavenResult alias = fixture.run("package");

        assertEquals(0, alias.exitCode(), alias.output());
        assertEquals("UTF-8", fixture.requestValue("main", "encoding"));
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), alias.output());
    }

    /**
     * A javac that maven-compiler-plugin forks gets the encoding the compiler is configured with as an
     * {@code -encoding} ahead of the compiler arguments, and takes the last one, so an {@code -encoding} compiler
     * argument wins over the configured encoding, and the generator is told that one: a project kept in ISO-8859-1
     * whose {@code project.build.sourceEncoding} says UTF-8 builds with a forked javac given
     * {@code -encoding ISO-8859-1}, its accented identifier intact.
     */
    @Test
    void aForkedJavacReadsTheLastEncodingArgumentOverTheConfiguredEncoding() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>",
                "<release>21</release><fork>true</fork><executable>${java.home}/bin/javac</executable>");
        addMainCompilerArguments(fixture, "<arg>-encoding</arg><arg>ISO-8859-1</arg>");
        fixture.writeSources(accentedDomain(), StandardCharsets.ISO_8859_1);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertEquals("ISO-8859-1", fixture.requestValue("main", "encoding"));
        assertTrue(fixture.localExports().contains("fx.ActionsRefs.ApproveRésumé"), result.output());
    }

    /**
     * The test compilation of maven-compiler-plugin compiles at {@code <testRelease>} when one is set, else at
     * {@code <release>}, and takes {@code <testSource>} and {@code <testTarget>} over {@code <source>} and
     * {@code <target>} the same way. A test release or test source below Java 21 is therefore refused by the
     * {@code generate-test} goal, the one goal of the test compilation that checks the compiler, before the test
     * request is written, even when the main compilation is at Java 21, and the refusal names the setting that wins
     * and one that fixes it. The two generation goals run on their own, so no javac runs, whatever JDK runs these
     * tests.
     */
    @Test
    void theGenerateTestGoalRefusesATestReleaseOrATestSourceBelowJava21() throws IOException {
        String goal = "io.github.graphmaquina:graphmaquina-maven-plugin:" + graphmaquinaVersion() + ':';
        MavenFixture released = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("released")),
                fixtureRepository);
        released.editPom("<release>21</release>", "<release>21</release>\n          <testRelease>17</testRelease>");
        MavenFixture targeted = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("targeted")),
                fixtureRepository);
        targeted.editPom("<release>21</release>", "<source>21</source>\n"
                + "          <target>21</target>\n"
                + "          <testSource>17</testSource>\n"
                + "          <testTarget>17</testTarget>");

        MavenResult release = released.run(goal + "generate", goal + "generate-test");
        MavenResult source = targeted.run(goal + "generate", goal + "generate-test");

        assertNotEquals(0, release.exitCode(), release.output());
        assertTrue(release.output().contains("the default-testCompile execution of maven-compiler-plugin compiles"
                + " with <testRelease>17</testRelease>"), release.output());
        assertTrue(release.output().contains("Configure <testRelease>21</testRelease> for that execution."),
                release.output());
        assertNotEquals(0, source.exitCode(), source.output());
        assertTrue(source.output().contains("the default-testCompile execution of maven-compiler-plugin compiles"
                + " with <testSource>17</testSource>"), source.output());
        assertTrue(source.output().contains("Configure <release>21</release> for that execution."),
                source.output());
        for (MavenFixture fixture : List.of(released, targeted)) {
            assertEquals("21", fixture.requestValue("main", "release"));
            assertFalse(fixture.exists("target/graphmaquina/test/request.json"));
        }
    }

    /**
     * javac takes the last {@code --release} it is given, and maven-compiler-plugin passes the release it is configured
     * with ahead of the compiler arguments, so a {@code --release} compiler argument is the release javac compiles at.
     * The {@code generate} goal checks that release and the request carries it: a {@code --release 17} argument beside
     * {@code <release>21</release>} is refused before the request is written, naming the argument and what fixes it,
     * and a {@code --release=21} argument beside {@code <release>17</release>} is accepted, the request carrying 21.
     * Only the generation runs, so no javac does.
     */
    @Test
    void aReleaseCompilerArgumentWinsOverTheConfiguredRelease() throws IOException {
        String goal = "io.github.graphmaquina:graphmaquina-maven-plugin:" + graphmaquinaVersion() + ":generate";
        MavenFixture lowered = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("lowered")),
                fixtureRepository);
        addMainCompilerArguments(lowered, "<arg>--release</arg><arg>17</arg>");
        MavenFixture raised = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("raised")),
                fixtureRepository);
        raised.editPom("<release>21</release>", "<release>17</release>");
        addMainCompilerArguments(raised, "<arg>--release=21</arg>");

        MavenResult refused = lowered.run(goal);
        MavenResult accepted = raised.run(goal);

        assertNotEquals(0, refused.exitCode(), refused.output());
        assertTrue(refused.output().contains("the default-compile execution of maven-compiler-plugin compiles with the"
                + " compiler argument --release 17, but the vocabulary the StateGraph generator emits needs Java 21 or"
                + " later. Give that argument a release of 21 or later, or remove it."), refused.output());
        assertFalse(lowered.exists("target/graphmaquina/main/request.json"));
        assertEquals(0, accepted.exitCode(), accepted.output());
        assertEquals("21", raised.requestValue("main", "release"));
    }

    /**
     * The {@code generate-test} goal checks the release of the test compilation alone: a {@code <testRelease>} of
     * Java 21 is what the test compilation compiles at, whatever older release the main compilation is configured
     * with, and so is a {@code <testTarget>} of Java 21 over an older {@code <target>}. The {@code generate-test}
     * goal accepts both, and the test request carries 21 rather than the older release or target.
     */
    @Test
    void theGenerateTestGoalAcceptsATestReleaseOrTestTargetOfJava21OverAnOlderOne() throws IOException {
        MavenFixture released = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("released")),
                fixtureRepository);
        released.editPom("<release>21</release>", "<release>17</release>\n          <testRelease>21</testRelease>");
        MavenFixture targeted = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("targeted")),
                fixtureRepository);
        targeted.editPom("<release>21</release>", "<source>17</source>\n"
                + "          <target>17</target>\n"
                + "          <testSource>21</testSource>\n"
                + "          <testTarget>21</testTarget>");

        for (MavenFixture fixture : List.of(released, targeted)) {
            for (Map.Entry<String, String> source : testDomain().entrySet()) {
                fixture.writeTestSource(source.getKey(), source.getValue());
            }

            MavenResult result = fixture.run("io.github.graphmaquina:graphmaquina-maven-plugin:" + graphmaquinaVersion()
                    + ":generate-test");

            assertEquals(0, result.exitCode(), result.output());
            assertEquals("21", fixture.requestValue("test", "release"));
            assertTrue(fixture.exists("target/generated-test-sources/graphmaquina/fxt/TicketsGraph.java"));
        }
    }

    /**
     * The request of the test compilation carries the release that compilation uses, {@code <testRelease>} over
     * {@code <release>} and {@code <testTarget>} over {@code <target>}, while the main request keeps the main
     * release. Both have to be Java 21 or later, so telling them apart needs a later release, and a JDK that
     * compiles at it. The two generation goals run on their own, so no javac runs: on a later JDK, javac compiling
     * the main sources of the second fixture at {@code -source 21} warns that the location of system modules is not
     * set, and {@code -Werror} turns that into a failure. The test is skipped on a JDK below 22, since a javac of
     * JDK 21 compiles at no release above 21.
     */
    @Test
    void theTestRequestCarriesTheReleaseOfTheTestCompilation() throws IOException {
        assumeTrue(Runtime.version().feature() >= 22, "a JDK 21 javac compiles at no release above 21");
        String goal = "io.github.graphmaquina:graphmaquina-maven-plugin:" + graphmaquinaVersion() + ':';
        MavenFixture released = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("released")),
                fixtureRepository);
        released.editPom("<release>21</release>", "<release>21</release>\n          <testRelease>22</testRelease>");
        MavenFixture targeted = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("targeted")),
                fixtureRepository);
        targeted.editPom("<release>21</release>", "<source>21</source>\n"
                + "          <target>21</target>\n"
                + "          <testSource>22</testSource>\n"
                + "          <testTarget>22</testTarget>");

        for (MavenFixture fixture : List.of(released, targeted)) {
            MavenResult result = fixture.run(goal + "generate", goal + "generate-test");

            assertEquals(0, result.exitCode(), result.output());
            assertEquals("21", fixture.requestValue("main", "release"));
            assertEquals("22", fixture.requestValue("test", "release"), result.output());
        }
    }

    /**
     * {@code mvn} reads a {@code .mavenrc} from the home directory, which may export another {@code JAVA_HOME};
     * every fixture build runs on the JDK of these tests whatever such a file says, since the JDK they reason
     * about is that one.
     */
    @Test
    void aMavenrcCannotReplaceTheJdkOfAFixtureBuild() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path home = Files.createDirectories(tempDirectory.resolve("home"));
        Files.writeString(home.resolve(".mavenrc"), "echo MAVENRC-WAS-READ\nJAVA_HOME='"
                + tempDirectory.resolve("no-such-jdk") + "'\n", StandardCharsets.UTF_8);

        MavenResult result = fixture.run(Map.of("HOME", home.toString()), "compile");

        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("MAVENRC-WAS-READ"), result.output());
    }

    /** The domain with its action spelled with an accent, as a project kept in ISO-8859-1 may spell it. */
    private static Map<String, String> accentedDomain() {
        Map<String, String> sources = MavenFixture.domainSources();
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
        sources.put("fx.Orders", MavenFixture.orders("Orders", "ApproveRésumé", "approveRésumé"));
        return sources;
    }

    /** Adds {@code arguments}, as {@code <arg>} elements, to the compiler arguments of the main compilation. */
    private static void addMainCompilerArguments(MavenFixture fixture, String arguments) throws IOException {
        String manifest = "graphmaquina/main/annotation-manifest.json</arg>";
        fixture.editPom(manifest, manifest + arguments);
    }

    /**
     * A toolchains file that declares one jdk toolchain of version {@code 77} at {@code jdk}, or none at all when
     * {@code jdk} is {@code null}. A build given it for both the user and the installation level sees exactly
     * these toolchains, whatever the machine running the tests declares.
     */
    private Path toolchains(String name, Path jdk) throws IOException {
        String declared = jdk == null ? "" : """
                  <toolchain>
                    <type>jdk</type>
                    <provides><version>77</version></provides>
                    <configuration><jdkHome>%s</jdkHome></configuration>
                  </toolchain>
                """.formatted(jdk);
        Path file = tempDirectory.resolve(name);
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<toolchains>\n" + declared
                + "</toolchains>\n", StandardCharsets.UTF_8);
        return file;
    }
}
