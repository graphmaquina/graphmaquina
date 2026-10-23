package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.graphmaquina.maven.BuildAssertions.assertNothingWasRetired;
import static io.github.graphmaquina.maven.BuildAssertions.assertNothingWasRetiredOrPublished;
import static io.github.graphmaquina.maven.MavenFixture.MAIN_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.TEST_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.graphmaquinaVersion;
import static io.github.graphmaquina.maven.MavenFixture.requireProperty;
import static io.github.graphmaquina.maven.MavenFixture.testDomain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to and runs its goals. Nothing here instantiates a Mojo.
 *
 * <p>These tests cover the order the goals run in around the compiler executions of the build, which of those
 * executions the checks read, and the builds that skip a compilation.</p>
 */
class MavenExecutionOrderTest {

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    /**
     * The order of the lifecycle is the whole contract of this plugin: the goals of the packaging keep their
     * place in the execution plan, so the compiler of a phase runs before the StateGraph goal bound to it, and
     * completion sees the classes javac has just written.
     */
    @Test
    void completionRunsAfterTheCompilerOfItsOwnPhase() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);

        MavenResult result = fixture.run("test-compile");

        assertEquals(0, result.exitCode(), result.output());
        List<String> executed = goalExecutions(result.output());
        assertOrder(executed, ":generate (sg-generate)", ":prepare (sg-prepare)");
        assertOrder(executed, ":prepare (sg-prepare)", ":compile (default-compile)");
        assertOrder(executed, ":compile (default-compile)", ":complete (sg-complete)");
        assertOrder(executed, ":generate-test (sg-generate-test)", ":prepare-test (sg-prepare-test)");
        assertOrder(executed, ":prepare-test (sg-prepare-test)", ":testCompile (default-testCompile)");
        assertOrder(executed, ":testCompile (default-testCompile)", ":complete-test (sg-complete-test)");
    }

    /**
     * A plugin configured once in a {@code pom} parent is inherited by its modules and runs in the parent too,
     * which compiles nothing: there every goal does nothing and says so, and the module builds as it would
     * on its own.
     */
    @Test
    void aPomParentSkipsTheExecutionsItsModulesInherit() throws IOException {
        MavenFixture parent = MavenFixture.basic(tempDirectory, fixtureRepository);
        MavenFixture module = parent.module();

        MavenResult result = parent.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph skips fx.build:fixture:main: the project is packaged as"
                + " pom, which compiles no Java"), result.output());
        assertTrue(result.output().contains("StateGraph skips fx.build:fixture:test: the project is packaged as"
                + " pom, which compiles no Java"), result.output());
        assertFalse(parent.exists("target/graphmaquina"));
        assertTrue(module.inventoryIsSealed("main"));
        assertTrue(module.jarContains("fx/OrdersGraph.class"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                module.localExports());
    }

    /**
     * The two javac executions name the directory of the annotation processor's sources with two different
     * parameters, and each generation reads its own: a redirected test directory is reported by
     * {@code generate-test}, and a main directory set to its default is not reported by either.
     */
    @Test
    void eachCompilationChecksItsOwnGeneratedSourcesDirectory() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <generatedTestSourcesDirectory>${project.build.directory}/elsewhere"
                + "</generatedTestSourcesDirectory>");

        MavenResult redirected = fixture.run("generate-test-sources");

        assertNotEquals(0, redirected.exitCode(), redirected.output());
        List<String> executed = goalExecutions(redirected.output());
        assertTrue(indexOf(executed, ":generate (sg-generate)") >= 0, executed.toString());
        assertTrue(indexOf(executed, ":generate-test (sg-generate-test)") >= 0, executed.toString());
        assertTrue(redirected.output().contains("Failed to execute goal io.github.graphmaquina:"
                + "graphmaquina-maven-plugin:" + graphmaquinaVersion() + ":generate-test (sg-generate-test)"),
                redirected.output());
        assertTrue(redirected.output().contains("<generatedTestSourcesDirectory>"), redirected.output());

        fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <generatedSourcesDirectory>${project.build.directory}/generated-sources/"
                + "annotations</generatedSourcesDirectory>");

        MavenResult supported = fixture.run("generate-test-sources");

        assertEquals(0, supported.exitCode(), supported.output());
    }

    /**
     * A build that switched a compilation off skips the StateGraph goals of that compilation the way
     * maven-compiler-plugin skips its own goal: they do nothing and say so, the index and the sealed inventory
     * of the previous build stay exactly as they were, and the rest of the build carries on — which is what
     * makes {@code -Dmaven.test.skip=true package} work. The index and the sealed inventory of each compilation are
     * checked after each of the two builds, the test ones included.
     */
    @Test
    void skippingACompilationSkipsItsStateGraphGoals() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        List<String> testPublished = fixture.localExportsOf(TEST_EXPORTS);
        assertTrue(testPublished.contains("fxt.Tickets"), testPublished::toString);

        MavenResult main = fixture.run("-Dmaven.main.skip=true", "compile");

        assertEquals(0, main.exitCode(), main.output());
        assertTrue(main.output().contains("StateGraph skips fx.build:fixture:main: maven.main.skip=true switches"
                + " the default-compile compilation off"), main.output());
        assertNothingWasRetired(fixture, published);
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(testPublished, fixture.localExportsOf(TEST_EXPORTS));

        MavenResult test = fixture.run("-Dmaven.test.skip=true", "package");

        assertEquals(0, test.exitCode(), test.output());
        assertTrue(test.output().contains("StateGraph skips fx.build:fixture:test: maven.test.skip=true switches"
                + " the default-testCompile compilation off"), test.output());
        assertTrue(fixture.inventoryIsSealed("test"), "the test inventory was retired:\n" + test.output());
        assertEquals(testPublished, fixture.localExportsOf(TEST_EXPORTS));
        assertNothingWasRetired(fixture, published);
        assertTrue(fixture.exists("target/fixture-1.0.0.jar"));
    }

    /**
     * A build may compile its sources through executions of its own and bind {@code default-compile} and
     * {@code default-testCompile} to the phase {@code none}, which Maven never runs, as kotlin-maven-plugin asks a
     * mixed Kotlin and Java build to do. The checks read the executions Maven runs, here {@code java-compile} and
     * {@code java-test-compile}, which carry the manifest arguments, and not the two it never runs, whose missing
     * manifest and {@code <proc>none</proc>} would be refused if they ran. Both compilations build and publish.
     */
    @Test
    void theChecksReadTheCompilerExecutionsMavenRunsAndNotTheDefaultOnesBoundToNoPhase() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        compileThroughExecutionsOfTheirOwn(fixture);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("--- compiler:3.15.0:compile (java-compile)"), result.output());
        assertTrue(result.output().contains("--- compiler:3.15.0:testCompile (java-test-compile)"), result.output());
        assertFalse(result.output().contains("(default-compile)"), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.localExports().contains("fx.Orders"), result.output());
        assertTrue(fixture.inventoryIsSealed("test"));
        assertTrue(fixture.localExportsOf(TEST_EXPORTS).contains("fxt.Tickets"), result.output());
    }

    /**
     * The settings the generation refuses are refused on the execution that compiles, whatever its id: with the main
     * sources compiled by {@code java-compile}, its
     * {@code <useIncrementalCompilation>false</useIncrementalCompilation>} is refused by the generation before
     * anything is retired, naming that execution.
     */
    @Test
    void aRefusedSettingOfTheExecutionThatCompilesIsRefusedWhateverItsId() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        compileThroughExecutionsOfTheirOwn(fixture);
        assertEquals(0, fixture.run("compile").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String compiling = "<goals><goal>compile</goal></goals>\n            <configuration>";
        fixture.editPom(compiling, compiling + "\n              <useIncrementalCompilation>false"
                + "</useIncrementalCompilation>");

        MavenResult result = fixture.run("compile");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("<useIncrementalCompilation>false</useIncrementalCompilation> makes the"
                + " java-compile execution of maven-compiler-plugin compile only the sources"), result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * Maven runs the executions the lifecycle binds, such as {@code default-compile}, before the others of their phase,
     * and the executions a POM adds to one phase in the order their plugins are declared. A compilation run through an
     * execution of its own therefore needs maven-compiler-plugin declared before graphmaquina-maven-plugin, or
     * {@code complete} runs before javac, and {@code complete-test} before the test javac, and fails after the
     * preparation retired the previous outputs. The generation refuses that order before anything is retired, for
     * the main compilation and for the test one, and so it does for a compiling execution bound to a phase that runs
     * before the preparation. The executions the lifecycle binds run first whatever the order, so a build of
     * {@code default-compile} and {@code default-testCompile} that declares graphmaquina-maven-plugin first builds.
     */
    @Test
    void aCompilerExecutionThatRunsOutsideThePreparationAndTheCompletionIsRefused() throws IOException {
        assertMainCompilerRunningAfterTheCompletionIsRefused();
        assertTestCompilerRunningAfterTheCompletionIsRefused();
        assertCompilerRunningBeforeThePreparationIsRefused();
        assertLifecycleExecutionsRunFirstWhateverTheDeclarationOrder();
    }

    /**
     * A compilation no execution of the compiler runs, here because {@code default-compile} is bound to the phase
     * {@code none} and nothing else compiles the main sources, is skipped like one {@code maven.main.skip} switches
     * off: the main goals do nothing and say so, and the index and the sealed inventory of the previous build stay as
     * they were.
     */
    @Test
    void aCompilationNoCompilerExecutionRunsIsSkipped() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("compile").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<id>default-compile</id>", "<id>default-compile</id>\n            <phase>none</phase>");

        MavenResult result = fixture.run("compile");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("StateGraph skips fx.build:fixture:main: maven-compiler-plugin runs no"
                + " execution of its compile goal in the default lifecycle of this project, as default-compile is bound"
                + " to none, which is no phase of a lifecycle"), result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * StateGraph surrounds the compilations of the default lifecycle, and Maven runs an execution of the compiler bound
     * to a phase of another lifecycle, such as {@code pre-site} of the site lifecycle, only when the build invokes that
     * lifecycle, never between the preparation and the completion. A compilation whose only execution of the compile
     * goal is bound there is skipped in a build of the default lifecycle, as one bound to {@code none} is, naming both
     * bindings, and the index and the sealed inventory of the previous build stay as they were. Beside
     * {@code default-compile}, such an execution, which would compile the vocabulary too, is not a second running
     * execution of the goal: the build compiles and publishes as without it.
     */
    @Test
    void aCompilerExecutionBoundToAPhaseOfAnotherLifecycleIsNotOneTheDefaultLifecycleRuns() throws IOException {
        MavenFixture alone = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("alone")),
                fixtureRepository);
        assertEquals(0, alone.run("compile").exitCode());
        List<String> published = alone.localExportsOf(MAIN_EXPORTS);
        moveCompilation(alone, "default-compile", "site-compile", "compile", "pre-site");

        MavenResult skipped = alone.run("compile");

        assertEquals(0, skipped.exitCode(), skipped.output());
        assertTrue(skipped.output().contains("StateGraph skips fx.build:fixture:main: maven-compiler-plugin runs no"
                + " execution of its compile goal in the default lifecycle of this project, as default-compile is bound"
                + " to none, which is no phase of a lifecycle, and site-compile is bound to pre-site, a phase of the"
                + " site lifecycle"), skipped.output());
        assertNothingWasRetired(alone, published);

        MavenFixture beside = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("beside")),
                fixtureRepository);
        beside.editPom("<id>default-testCompile</id>", """
                <id>site-compile</id>
                            <phase>pre-site</phase>
                            <goals><goal>compile</goal></goals>
                          </execution>
                          <execution>
                            <id>default-testCompile</id>""".strip());

        MavenResult built = beside.run("compile");

        assertEquals(0, built.exitCode(), built.output());
        assertTrue(beside.inventoryIsSealed("main"));
        assertTrue(beside.exists(MAIN_EXPORTS));
        assertTrue(beside.exists("target/classes/fx/OrdersGraph.class"));
    }

    /**
     * A build may run more than one execution of the compile goal, as a multi-release jar does through one of its own
     * that compiles another source root into {@code META-INF/versions}. The execution that compiles the vocabulary the
     * generator writes, here {@code default-compile}, is the main compilation the checks read, and the other one, whose
     * {@code <compileSourceRoots>} leave that vocabulary out and whose {@code <multiReleaseOutput>} would be refused
     * on the main compilation, runs untouched, build after build. A third execution that compiles the vocabulary as
     * well would make javac compile it twice, and the generation refuses it naming both, before anything is retired.
     */
    @Test
    void theExecutionThatCompilesTheVocabularyIsTheMainCompilationAmongSeveral() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<id>default-testCompile</id>", """
                <id>versioned</id>
                            <goals><goal>compile</goal></goals>
                            <configuration>
                              <multiReleaseOutput>true</multiReleaseOutput>
                              <compileSourceRoots>
                                <compileSourceRoot>${project.basedir}/src/main/java21</compileSourceRoot>
                              </compileSourceRoots>
                            </configuration>
                          </execution>
                          <execution>
                            <id>default-testCompile</id>""".strip());
        fixture.writeFile("src/main/java21/fx/Versioned.java", """
                package fx;

                /** A class of release 21 of a multi-release jar. */
                public final class Versioned {

                    private Versioned() {
                    }
                }
                """.getBytes(StandardCharsets.UTF_8));

        MavenResult first = fixture.run("package");
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        MavenResult second = fixture.run("package");

        for (MavenResult result : List.of(first, second)) {
            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("--- compiler:3.15.0:compile (versioned)"), result.output());
        }
        assertTrue(fixture.jarContains("META-INF/versions/21/fx/Versioned.class"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertEquals(published, fixture.localExportsOf(MAIN_EXPORTS));
        assertTrue(published.contains("fx.Orders"), published::toString);

        fixture.editPom("<id>versioned</id>", "<id>again</id><goals><goal>compile</goal></goals></execution>"
                + "<execution><id>versioned</id>");

        MavenResult twice = fixture.run("package");

        assertNotEquals(0, twice.exitCode(), twice.output());
        assertTrue(twice.output().contains("maven-compiler-plugin runs the executions default-compile and again of"
                + " its compile goal for this project, and each of them compiles "), twice.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * Asserts that a main compilation run through an execution of its own, declared after graphmaquina-maven-plugin,
     * is refused: the completion would run before javac.
     */
    private void assertMainCompilerRunningAfterTheCompletionIsRefused() throws IOException {
        MavenFixture main = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("main")),
                fixtureRepository);
        compileThroughExecutionsOfTheirOwn(main);
        assertEquals(0, main.run("compile").exitCode());
        List<String> published = main.localExportsOf(MAIN_EXPORTS);
        declareStateGraphPluginFirst(main);

        MavenResult mainFirst = main.run("compile");

        assertNotEquals(0, mainFirst.exitCode(), mainFirst.output());
        assertTrue(mainFirst.output().contains("the java-compile execution of maven-compiler-plugin, bound to compile,"
                + " runs after the sg-complete execution of graphmaquina-maven-plugin, bound to compile, which"
                + " completes this compilation"), mainFirst.output());
        assertTrue(mainFirst.output().contains("Declare maven-compiler-plugin before graphmaquina-maven-plugin in the"
                + " <plugins> of the build."), mainFirst.output());
        assertNothingWasRetired(main, published);
    }

    /**
     * Asserts that a test compilation run through an execution of its own, declared after graphmaquina-maven-plugin,
     * is refused: the completion would run before the test javac.
     */
    private void assertTestCompilerRunningAfterTheCompletionIsRefused() throws IOException {
        MavenFixture test = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("test")),
                fixtureRepository);
        moveCompilation(test, "default-testCompile", "java-test-compile", "testCompile", "test-compile");
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            test.writeTestSource(source.getKey(), source.getValue());
        }
        assertEquals(0, test.run("test-compile").exitCode());
        List<String> testPublished = test.localExportsOf(TEST_EXPORTS);
        declareStateGraphPluginFirst(test);

        MavenResult testFirst = test.run("test-compile");

        assertNotEquals(0, testFirst.exitCode(), testFirst.output());
        assertTrue(testFirst.output().contains("the java-test-compile execution of maven-compiler-plugin, bound to"
                + " test-compile, runs after the sg-complete-test execution of graphmaquina-maven-plugin"),
                testFirst.output());
        assertTrue(test.inventoryIsSealed("test"));
        assertEquals(testPublished, test.localExportsOf(TEST_EXPORTS));
    }

    /** Asserts that a main compilation run through an execution bound to a phase before the preparation is refused. */
    private void assertCompilerRunningBeforeThePreparationIsRefused() throws IOException {
        MavenFixture early = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("early")),
                fixtureRepository);
        moveCompilation(early, "default-compile", "java-compile", "compile", "process-sources");

        MavenResult beforePreparation = early.run("compile");

        assertNotEquals(0, beforePreparation.exitCode(), beforePreparation.output());
        assertTrue(beforePreparation.output().contains("the java-compile execution of maven-compiler-plugin, bound to"
                + " process-sources, runs before the sg-prepare execution of graphmaquina-maven-plugin, bound to"
                + " process-sources, which prepares this compilation"), beforePreparation.output());
        assertNothingWasRetiredOrPublished(early);
    }

    /** Asserts that the executions the lifecycle binds run first, so declaring the StateGraph plugin first builds. */
    private void assertLifecycleExecutionsRunFirstWhateverTheDeclarationOrder() throws IOException {
        MavenFixture defaults = MavenFixture.basic(Files.createDirectories(tempDirectory.resolve("defaults")),
                fixtureRepository);
        declareStateGraphPluginFirst(defaults);

        MavenResult lifecycleFirst = defaults.run("test-compile");

        assertEquals(0, lifecycleFirst.exitCode(), lifecycleFirst.output());
        assertTrue(defaults.inventoryIsSealed("main"));
        assertTrue(defaults.inventoryIsSealed("test"));
    }

    /**
     * Has the executions {@code java-compile} and {@code java-test-compile} compile the main and the test sources with
     * the configuration the fixture gives {@code default-compile} and {@code default-testCompile}, and binds those two
     * to the phase {@code none}, configured with {@code <proc>none</proc>} and no manifest argument, as
     * kotlin-maven-plugin asks a mixed Kotlin and Java build to do.
     */
    private static void compileThroughExecutionsOfTheirOwn(MavenFixture fixture) throws IOException {
        moveCompilation(fixture, "default-compile", "java-compile", "compile", "compile");
        moveCompilation(fixture, "default-testCompile", "java-test-compile", "testCompile", "test-compile");
    }

    /**
     * Binds the execution {@code defaultId} to the phase {@code none}, and gives the configuration it had to a new
     * execution {@code id} of {@code goal}, bound to {@code phase}.
     */
    private static void moveCompilation(MavenFixture fixture, String defaultId, String id, String goal, String phase)
            throws IOException {
        fixture.editPom("<id>" + defaultId + "</id>", "<id>" + defaultId + "</id>\n"
                + "            <phase>none</phase>\n"
                + "            <configuration><proc>none</proc></configuration>\n"
                + "          </execution>\n"
                + "          <execution>\n"
                + "            <id>" + id + "</id>\n"
                + "            <phase>" + phase + "</phase>\n"
                + "            <goals><goal>" + goal + "</goal></goals>");
    }

    /** Moves the declaration of graphmaquina-maven-plugin ahead of that of maven-compiler-plugin in the fixture POM. */
    private static void declareStateGraphPluginFirst(MavenFixture fixture) throws IOException {
        String pom = Files.readString(fixture.projectDirectory().resolve("pom.xml"), StandardCharsets.UTF_8);
        String compiler = "      <plugin>\n        <groupId>org.apache.maven.plugins</groupId>";
        int start = pom.indexOf("      <plugin>\n        <groupId>io.github.graphmaquina</groupId>");
        String end = "      </plugin>\n";
        String stateGraph = pom.substring(start, pom.indexOf(end, start) + end.length());
        fixture.editPom(stateGraph, "");
        fixture.editPom(compiler, stateGraph + compiler);
    }

    /** The goal executions of one build, as {@code plugin:version:goal (executionId)}, in the order Maven ran them. */
    private static List<String> goalExecutions(String output) {
        List<String> executions = new ArrayList<>();
        for (String line : output.lines().toList()) {
            int start = line.indexOf("--- ");
            int end = line.indexOf(" @ ");
            if (line.startsWith("[INFO] ") && start > 0 && end > start) {
                executions.add(line.substring(start + 4, end));
            }
        }
        return List.copyOf(executions);
    }

    /** Asserts that the execution ending with {@code first} ran before the one ending with {@code second}. */
    private static void assertOrder(List<String> executions, String first, String second) {
        int before = indexOf(executions, first);
        int after = indexOf(executions, second);
        assertTrue(before >= 0, first + " did not run: " + executions);
        assertTrue(after >= 0, second + " did not run: " + executions);
        assertTrue(before < after, first + " did not run before " + second + ": " + executions);
    }

    /** The position of the first execution whose name ends with {@code suffix}, or {@code -1}. */
    private static int indexOf(List<String> executions, String suffix) {
        for (int index = 0; index < executions.size(); index++) {
            if (executions.get(index).endsWith(suffix)) {
                return index;
            }
        }
        return -1;
    }
}
