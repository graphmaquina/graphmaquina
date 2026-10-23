package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
import static io.github.graphmaquina.maven.MavenFixture.MAIN_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.TEST_EXPORTS;
import static io.github.graphmaquina.maven.MavenFixture.requireProperty;
import static io.github.graphmaquina.maven.MavenFixture.testDomain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the packaged Maven plugin the way a consumer does: a real {@code mvn} process resolves it from the file
 * repository this build published to and runs its goals. Nothing here instantiates a Mojo.
 *
 * <p>These tests cover which sources the generator reads: exactly those javac compiles, whatever the includes,
 * excludes, source roots, links and default excludes of the compiler select, and the carrier of the compilation
 * among them.</p>
 */
class MavenSourceSelectionTest {

    /** Why a test that locks a directory is aborted where {@link MavenFixture#forbidListing} cannot lock it. */
    private static final String UNLOCKABLE =
            "this user lists a directory it has no permission on, or the file system has no POSIX permissions";

    /** The file repository the Gradle build published every StateGraph artifact of this version to. */
    private final Path fixtureRepository = Path.of(requireProperty("graphmaquina.fixtureRepository"));

    @TempDir
    Path tempDirectory;

    /**
     * The generator parses exactly the sources javac compiles: a source the {@code <excludes>} of the compiler
     * leave out — here a draft that does not even parse — is not read by the main generation, and one the
     * {@code <testExcludes>} leave out is not read by the test generation.
     */
    @Test
    void theGeneratorReadsOnlyTheSourcesTheCompilerCompiles() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <excludes><exclude>fx/drafts/**</exclude></excludes>\n"
                + "          <testExcludes><exclude>fxt/drafts/**</exclude></testExcludes>");
        Map<String, String> sources = MavenFixture.domainSources();
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        fixture.writeSources(sources);
        fixture.writeTestSource("fxt.drafts.Draft", draft("fxt.drafts"));

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/drafts/Draft.class"));
        assertTrue(fixture.inventoryIsSealed("test"));
        assertFalse(fixture.exists("target/test-classes/fxt/drafts/Draft.class"));
    }

    /**
     * The test compilation of maven-compiler-plugin adds its {@code <testIncrementalExcludes>} to its
     * {@code <testExcludes>} when it selects the sources javac compiles, and so does the test generation: a draft they
     * leave out, which does not even parse, is not read, and a pattern of theirs that leaves out the carrier of the
     * test compilation is refused before anything is retired.
     */
    @Test
    void theTestIncrementalExcludesLeaveOutTheTestSourcesJavacLeavesOut() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : testDomain().entrySet()) {
            fixture.writeTestSource(source.getKey(), source.getValue());
        }
        fixture.writeTestSource("fxt.drafts.Draft", draft("fxt.drafts"));
        String drafts = "<testIncrementalExclude>fxt/drafts/**</testIncrementalExclude>";
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <testIncrementalExcludes>" + drafts + "</testIncrementalExcludes>");

        MavenResult left = fixture.run("package");

        assertEquals(0, left.exitCode(), left.output());
        assertTrue(fixture.inventoryIsSealed("test"));
        assertFalse(fixture.exists("target/test-classes/fxt/drafts/Draft.class"));
        List<String> published = fixture.localExportsOf(TEST_EXPORTS);
        assertTrue(published.contains("fxt.Tickets"), published::toString);

        fixture.editPom(drafts, drafts + "<testIncrementalExclude>io/github/**</testIncrementalExclude>");

        MavenResult carrier = fixture.run("package");

        assertNotEquals(0, carrier.exitCode(), carrier.output());
        assertTrue(carrier.output().contains("the <testIncrementalExcludes> pattern io/github/** of the"
                + " default-testCompile execution of maven-compiler-plugin leaves out"
                + " io/github/graphmaquina/generated/bootstrap/GraphCompilation"), carrier.output());
        assertTrue(fixture.inventoryIsSealed("test"));
        assertEquals(published, fixture.localExportsOf(TEST_EXPORTS));
    }

    /**
     * The main compilation of maven-compiler-plugin compiles the sources its {@code <incrementalExcludes>} match, but
     * leaves them out of its check for stale sources, which decides whether it runs javac at all. The carrier, whose
     * class file the completion deletes, is what makes it compile the module again after the preparation: a pattern
     * that hides the carrier, here in a project whose generation emits nothing else, would let it skip javac. Such a
     * pattern is refused before anything is retired, while one that leaves the carrier in builds.
     */
    @Test
    void anIncrementalExcludeThatHidesTheCarrierIsRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.removeAllStateGraphDeclarations();
        assertEquals(0, fixture.run("compile").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String generated = "<incrementalExclude>**/generated/**</incrementalExclude>";
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <incrementalExcludes>" + generated + "</incrementalExcludes>");

        MavenResult hidden = fixture.run("compile");

        assertNotEquals(0, hidden.exitCode(), hidden.output());
        assertTrue(hidden.output().contains("the <incrementalExcludes> pattern **/generated/** of the default-compile"
                + " execution of maven-compiler-plugin leaves io/github/graphmaquina/generated/bootstrap/"
                + "GraphCompilation"), hidden.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom(generated, "<incrementalExclude>fx/drafts/**</incrementalExclude>");

        MavenResult left = fixture.run("compile");

        assertBuildPublishesTheSameIndex(fixture, left, published);
    }

    /**
     * maven-compiler-plugin applies its includes and excludes to the directory the generator writes into as well,
     * so patterns that leave out the carrier of a compilation would let javac compile the vocabulary without it.
     * They are refused by the generation, before anything is retired, naming the pattern and what mends it: main
     * includes that select only the packages of the project, and a test exclude that drops the carrier package.
     */
    @Test
    void patternsThatLeaveOutTheCarrierAreRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        // The carrier the generator emitted, named relative to its root as the compiler matches it.
        String carrier = fixture.projectDirectory().resolve("target/generated-sources/graphmaquina")
                .relativize(fixture.carrierSource()).toString();
        fixture.editPom("<release>21</release>",
                "<release>21</release>\n          <includes><include>fx/**</include></includes>");

        MavenResult includes = fixture.run("package");

        assertNotEquals(0, includes.exitCode(), includes.output());
        assertTrue(includes.output().contains("the <includes> of the default-compile execution of"
                + " maven-compiler-plugin, [fx/**], select no " + carrier), includes.output());
        assertTrue(includes.output().contains("Add <include>io/github/graphmaquina/generated/**</include> to the"
                + " <includes> of that execution."), includes.output());
        assertNothingWasRetired(fixture, published);

        fixture.editPom("<includes><include>fx/**</include></includes>",
                "<testExcludes><exclude>io/github/**</exclude></testExcludes>");

        MavenResult excludes = fixture.run("package");

        assertNotEquals(0, excludes.exitCode(), excludes.output());
        assertTrue(excludes.output().contains("the <testExcludes> pattern io/github/** of the default-testCompile"
                + " execution of maven-compiler-plugin leaves out io/github/graphmaquina/generated/bootstrap/"
                + "GraphCompilation"), excludes.output());
        assertTrue(fixture.inventoryIsSealed("test"));
    }

    /**
     * The scanner of maven-compiler-plugin enters a directory only when an include matches it and no exclude does, or
     * when an include could match a path below it, which an include that starts with a separator never can where the
     * file separator is {@code /}. So {@code /io/github/graphmaquina/generated/**} matches the carrier and still leaves
     * it out, since the scanner never enters {@code io}: javac would compile no carrier. Such includes are refused by
     * the generation, before anything is retired, naming the include and the directory the scanner never enters. The
     * test runs only where the file separator is {@code /}: where it is {@code \}, the scanner enters {@code io} for
     * such an include, and javac compiles the carrier.
     */
    @Test
    void anIncludeThatStartsWithASeparatorAndLeavesTheCarrierOutIsRefusedBeforeAnythingIsRetired()
            throws IOException {
        assumeTrue(File.separatorChar == '/', "an include that starts with a separator lets the scanner in here");
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String carrier = fixture.projectDirectory().resolve("target/generated-sources/graphmaquina")
                .relativize(fixture.carrierSource()).toString();
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <includes>\n"
                + "            <include>/fx/**</include>\n"
                + "            <include>/io/github/graphmaquina/generated/**</include>\n"
                + "          </includes>");

        MavenResult result = fixture.run("package");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the <includes> of the default-compile execution of"
                + " maven-compiler-plugin, [/fx/**, /io/github/graphmaquina/generated/**], select no " + carrier),
                result.output());
        assertTrue(result.output().contains("The include /io/github/graphmaquina/generated/** matches that path, but"
                + " the scanner of the compiler never enters io: it enters a directory only when an include matches"
                + " that directory and no exclude does"), result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * The generator reads the sources the scanner of maven-compiler-plugin reaches, and that scanner enters an excluded
     * directory only when an include could match a path below it, which {@code /fx/**} never can where the file
     * separator is {@code /}: with that include and the exclude {@code fx/drafts}, which matches the directory and none
     * of the files under it, javac compiles no draft there, and the generator reads none, although {@code /fx/**}
     * matches the draft's path. The draft does not even parse. The test runs only where the file separator is
     * {@code /}: where it is {@code \}, the scanner enters {@code fx/drafts} for that include.
     */
    @Test
    void theGeneratorSkipsTheDirectoriesTheCompilersScannerNeverEnters() throws IOException {
        assumeTrue(File.separatorChar == '/', "an include that starts with a separator lets the scanner in here");
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <includes>\n"
                + "            <include>/fx/**</include>\n"
                + "            <include>io/github/graphmaquina/generated/**</include>\n"
                + "          </includes>\n"
                + "          <excludes><exclude>fx/drafts</exclude></excludes>");
        Map<String, String> sources = MavenFixture.domainSources();
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        fixture.writeSources(sources);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/drafts/Draft.class"));
    }

    /**
     * Includes that also select the sources the generator emits outside the packages of the project build and
     * package: the generator reads only the included sources — a draft outside them does not even parse — and
     * javac compiles the generated vocabulary together with its carrier.
     */
    @Test
    void includesThatSelectTheGeneratedSourcesBuildAndPackage() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <includes>\n"
                + "            <include>fx/**</include>\n"
                + "            <include>io/github/graphmaquina/generated/**</include>\n"
                + "          </includes>");
        Map<String, String> sources = MavenFixture.domainSources();
        sources.put("misc.Draft", draft("misc"));
        fixture.writeSources(sources);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("misc/Draft.class"));
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
    }

    /**
     * Maven also accepts a list parameter written as text, which it splits on every comma: {@code <compilerArgs>}
     * holding the manifest argument and {@code -Werror} that way gives javac both arguments, so the manifest check
     * reads it there and the compilation builds, and a {@code <testExcludes>} written that way, which leaves out the
     * carrier of the test compilation, is refused by the generation of that compilation. {@code <includes>} holding
     * nothing but empty values gets no default include from maven-compiler-plugin, so it selects no source, the
     * carrier included, and is refused as well, before the preparation retires anything.
     */
    @Test
    void listParametersWrittenAsTextAreReadAsMavenSplitsThem() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("""
                              <compilerArgs>
                                <arg>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/\
                annotation-manifest.json</arg>
                                <arg>-Xlint:all,-serial</arg>
                                <arg>-Werror</arg>
                              </compilerArgs>
                """, """
                              <compilerArgs>-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/\
                main/annotation-manifest.json,-Werror</compilerArgs>
                """);

        MavenResult text = fixture.run("compile");

        assertEquals(0, text.exitCode(), text.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        fixture.editPom("<release>21</release>",
                "<release>21</release>\n          <testExcludes>io/github/**</testExcludes>");

        MavenResult excludes = fixture.run("test-compile");

        assertNotEquals(0, excludes.exitCode(), excludes.output());
        assertTrue(excludes.output().contains("the <testExcludes> pattern io/github/** of the default-testCompile"
                + " execution of maven-compiler-plugin leaves out io/github/graphmaquina/generated/bootstrap/"
                + "GraphCompilation"), excludes.output());

        fixture.editPom("<testExcludes>io/github/**</testExcludes>", "<includes>,</includes>");

        MavenResult empty = fixture.run("compile");

        assertNotEquals(0, empty.exitCode(), empty.output());
        assertTrue(empty.output().contains("the <includes> of the default-compile execution of maven-compiler-plugin"
                + " hold only empty values"), empty.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * {@code <compileSourceRoots>} of a compiler execution replace the source roots of the project for javac, the
     * directory the generator writes into included, so a list that leaves that directory out would have javac compile
     * the hand-written sources without the vocabulary they use. It is refused before anything is retired.
     */
    @Test
    void compilerSourceRootsWithoutTheGeneratedDirectoryAreRefusedBeforeAnythingIsRetired() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        assertEquals(0, fixture.run("package").exitCode());
        List<String> published = fixture.localExportsOf(MAIN_EXPORTS);
        String main = "<id>default-compile</id>\n            <configuration>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>"
                + "<compileSourceRoot>${project.basedir}/src/main/java</compileSourceRoot></compileSourceRoots>");

        MavenResult result = fixture.run("compile");

        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("the <compileSourceRoots> of the default-compile execution of"
                + " maven-compiler-plugin replace the source roots of the project and leave out "
                + fixture.projectDirectory().toRealPath().resolve("target/generated-sources/graphmaquina")),
                result.output());
        assertNothingWasRetired(fixture, published);
    }

    /**
     * An empty {@code <compileSourceRoots/>} is no list of its own: Maven gives the compiler its default for it, the
     * source roots of the project, the directory the generator writes into included, so it builds and publishes.
     */
    @Test
    void anEmptyCompileSourceRootsElementCompilesTheSourceRootsOfTheProject() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        String main = "<id>default-compile</id>\n            <configuration>";
        fixture.editPom(main, main + "\n              <compileSourceRoots/>");

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
    }

    /**
     * Maven started with {@code -f} through a symbolic link to the project directory names the project directory
     * through that link, while the JVM running it names its working directory with every link resolved. A relative
     * manifest path, and relative {@code <compileSourceRoots>}, which javac resolves against that working directory,
     * name the very files and directories the generator writes, so both build. So do absolute
     * {@code <compileSourceRoots>} spelled through the link while Maven names the project directory without it, and a
     * relative root that leads through a link inside the project and then up again, which the file system resolves
     * link first. Each build publishes, byte for byte, the index a plain build publishes: a source keeps its origin
     * relative to the project directory, however its root is spelled. The test is aborted where symbolic links cannot
     * be created.
     */
    @Test
    void namesThroughALinkToTheProjectDirectoryBuildTheSameIndex() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path link = tempDirectory.resolve("linked project");
        try {
            Files.createSymbolicLink(link, fixture.projectDirectory());
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        assertEquals(0, fixture.run("package").exitCode());
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);
        byte[] published = Files.readAllBytes(exports);
        String pom = link.resolve("pom.xml").toString();
        fixture.editPom("-Agraphmaquina.annotationManifest=${project.build.directory}/graphmaquina/main/",
                "-Agraphmaquina.annotationManifest=target/graphmaquina/main/");

        MavenResult manifest = fixture.run("-f", pom, "package");

        assertBuildPublishesTheSameBytes(fixture, manifest, exports, published);

        String main = "<id>default-compile</id>\n            <configuration>";
        String relative = "<compileSourceRoots>"
                + "<compileSourceRoot>src/main/java</compileSourceRoot>"
                + "<compileSourceRoot>target/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>";
        fixture.editPom(main, main + "\n              " + relative);

        MavenResult roots = fixture.run("-f", pom, "package");

        assertBuildPublishesTheSameBytes(fixture, roots, exports, published);

        String absoluteRoots = "<compileSourceRoots>"
                + "<compileSourceRoot>" + link.resolve("src/main/java") + "</compileSourceRoot>"
                + "<compileSourceRoot>" + link.resolve("target/generated-sources/graphmaquina")
                + "</compileSourceRoot></compileSourceRoots>";
        fixture.editPom(relative, absoluteRoots);

        MavenResult absolute = fixture.run("package");

        assertBuildPublishesTheSameBytes(fixture, absolute, exports, published);

        // The file system resolves deep/../../src/main/java through the link first, to src/main/java, while a
        // normalized spelling of it would leave the project directory.
        Path deep = Files.createDirectories(fixture.projectDirectory().resolve("target/deep"));
        Files.createSymbolicLink(fixture.projectDirectory().resolve("deep"), deep);
        fixture.editPom(absoluteRoots, "<compileSourceRoots>"
                + "<compileSourceRoot>deep/../../src/main/java</compileSourceRoot>"
                + "<compileSourceRoot>target/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");

        MavenResult throughDeep = fixture.run("package");

        assertBuildPublishesTheSameBytes(fixture, throughDeep, exports, published);
    }

    /**
     * The generator parses the sources of the roots javac compiles: the {@code <compileSourceRoots>} of the compiler
     * execution when it declares them. Here they name a root of their own, which holds the shared domain, and the
     * directory the generator writes into, while the source root of the project holds only a draft that does not
     * parse, which javac never sees and the generator does not read either. That root then builds the same index when
     * it is spelled through a symbolic link followed by {@code ..}, which the file system resolves link first, while
     * a normalized spelling of it would name a directory that does not exist. That part is aborted where symbolic
     * links cannot be created.
     */
    @Test
    void theGeneratorReadsTheSourceRootsOfTheCompiler() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : MavenFixture.domainSources().entrySet()) {
            fixture.writeFile("src/other/java/" + source.getKey().replace('.', '/') + ".java",
                    source.getValue().getBytes(StandardCharsets.UTF_8));
        }
        fixture.writeSources(Map.of("misc.Draft", draft("misc")));
        String main = "<id>default-compile</id>\n            <configuration>";
        String other = "<compileSourceRoot>${project.basedir}/src/other/java</compileSourceRoot>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>" + other
                + "<compileSourceRoot>${project.build.directory}/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
        assertFalse(fixture.jarContains("misc/Draft.class"));
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);
        byte[] published = Files.readAllBytes(exports);

        Path deeper = Files.createDirectories(fixture.projectDirectory().resolve("src/other/deeper"));
        try {
            Files.createSymbolicLink(fixture.projectDirectory().resolve("inner"), deeper);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        fixture.editPom(other, "<compileSourceRoot>${project.basedir}/inner/../java</compileSourceRoot>");

        MavenResult throughLink = fixture.run("package");

        assertEquals(0, throughLink.exitCode(), throughLink.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));
    }

    /**
     * The roots of {@code <compileSourceRoots>} are read as Maven evaluates them for the compiler, completing what the
     * effective model leaves for it, such as {@code ${session.executionRootDirectory}}, the directory the build was
     * started in. Here a root of their own named through that expression holds the shared domain, while the source
     * root of the project holds only a draft that does not parse: the generator parses the root javac compiles, and
     * not the draft. The directory the generator writes into, named through the same expression, is then one javac
     * compiles too, so that build is not refused either, and both publish the same index.
     */
    @Test
    void compileSourceRootsAreReadAsMavenEvaluatesThem() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        for (Map.Entry<String, String> source : MavenFixture.domainSources().entrySet()) {
            fixture.writeFile("src/other/java/" + source.getKey().replace('.', '/') + ".java",
                    source.getValue().getBytes(StandardCharsets.UTF_8));
        }
        fixture.writeSources(Map.of("misc.Draft", draft("misc")));
        String main = "<id>default-compile</id>\n            <configuration>";
        String generated = "<compileSourceRoot>${project.build.directory}/generated-sources/graphmaquina"
                + "</compileSourceRoot>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>"
                + "<compileSourceRoot>${session.executionRootDirectory}/src/other/java</compileSourceRoot>"
                + generated + "</compileSourceRoots>");

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertEquals(List.of("fx.Orders", "fx.ActionsRefs.Approve", "fx.OrderRefs.Id", "fx.OrderRefs.Status"),
                fixture.localExports());
        assertFalse(fixture.jarContains("misc/Draft.class"));
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);
        byte[] published = Files.readAllBytes(exports);

        fixture.editPom(generated, "<compileSourceRoot>${session.executionRootDirectory}/target/generated-sources"
                + "/graphmaquina</compileSourceRoot>");

        MavenResult session = fixture.run("package");

        assertEquals(0, session.exitCode(), session.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));
    }

    /**
     * The sources of a root outside the project directory keep the origin a plain build publishes for them, their path
     * from the project directory once symbolic links are resolved, when the build reaches that root with {@code -f}
     * through a link to the project directory that lies in another directory, whose path has at least as many names
     * as that path has {@code ..}, and through a link to the root itself. The index is the same, byte for byte, each
     * time. The test is aborted where symbolic links cannot be created.
     */
    @Test
    void aSourceRootOutsideTheProjectKeepsItsOriginThroughALink() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Path shared = tempDirectory.resolve("shared/java");
        for (Map.Entry<String, String> source : MavenFixture.domainSources().entrySet()) {
            Path file = shared.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        fixture.writeSources(Map.of("misc.Draft", draft("misc")));
        Path projectLink = Files.createDirectories(tempDirectory.resolve("elsewhere/deeper")).resolve("linked project");
        Path sharedLink = tempDirectory.resolve("shared link");
        try {
            Files.createSymbolicLink(projectLink, fixture.projectDirectory());
            Files.createSymbolicLink(sharedLink, shared);
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        String main = "<id>default-compile</id>\n            <configuration>";
        String outside = "<compileSourceRoot>${project.basedir}/../shared/java</compileSourceRoot>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>" + outside
                + "<compileSourceRoot>${project.build.directory}/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");

        MavenResult plain = fixture.run("package");

        assertEquals(0, plain.exitCode(), plain.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);
        byte[] published = Files.readAllBytes(exports);
        assertTrue(Files.readString(exports).contains("\"../shared/java/fx/Orders.java\""),
                Files.readString(exports));

        MavenResult throughProject = fixture.run("-f", projectLink.resolve("pom.xml").toString(), "package");

        assertBuildPublishesTheSameBytes(fixture, throughProject, exports, published);

        fixture.editPom(outside, "<compileSourceRoot>" + sharedLink + "</compileSourceRoot>");

        MavenResult throughRoot = fixture.run("package");

        assertBuildPublishesTheSameBytes(fixture, throughRoot, exports, published);
    }

    /**
     * An origin is the path of its source from the project directory the build is given, read lexically, as the
     * generator relativizes it. When {@code -f} names a deep project directory through a link whose path has fewer
     * names than the {@code ..} that lead from the project directory up to a source root outside it, a name that
     * spelled those {@code ..} after the link would lose some of them in the generator. The root then keeps its own
     * name, so every origin still leads from the link to its source. The test is aborted where symbolic links cannot
     * be created.
     */
    @Test
    void aShallowLinkToADeepProjectLeavesEveryOriginLeadingToItsSource() throws IOException {
        Path deep = tempDirectory;
        for (int level = 0; level <= tempDirectory.getNameCount(); level++) {
            deep = deep.resolve("d" + level);
        }
        MavenFixture fixture = MavenFixture.basic(deep, fixtureRepository);
        Path shared = tempDirectory.resolve("shared/java");
        for (Map.Entry<String, String> source : MavenFixture.domainSources().entrySet()) {
            Path file = shared.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        fixture.writeSources(Map.of("misc.Draft", draft("misc")));
        Path projectLink = tempDirectory.resolve("shallow");
        try {
            Files.createSymbolicLink(projectLink, fixture.projectDirectory());
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }
        String up = "../".repeat(fixture.projectDirectory().getNameCount() - tempDirectory.getNameCount());
        String main = "<id>default-compile</id>\n            <configuration>";
        fixture.editPom(main, main + "\n              <compileSourceRoots>"
                + "<compileSourceRoot>${project.basedir}/" + up + "shared/java</compileSourceRoot>"
                + "<compileSourceRoot>${project.build.directory}/generated-sources/graphmaquina</compileSourceRoot>"
                + "</compileSourceRoots>");
        Path exports = fixture.projectDirectory().resolve(MAIN_EXPORTS);

        MavenResult plain = fixture.run("package");

        assertEquals(0, plain.exitCode(), plain.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertOriginsLeadToSources(fixture.projectDirectory(), exports);

        MavenResult throughLink = fixture.run("-f", projectLink.resolve("pom.xml").toString(), "package");

        assertEquals(0, throughLink.exitCode(), throughLink.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertOriginsLeadToSources(projectLink, exports);
    }

    /**
     * maven-compiler-plugin leaves out of every source root the files its scanner's default excludes name, such
     * as the AppleDouble companion {@code ._Orders.java} a copy from macOS leaves beside a source, or anything
     * inside the directory of a version control system, such as {@code CVS}. The generator leaves out these two,
     * which Ant's default excludes name as well, whether this plugin hands it the files that includes and excludes
     * select or a whole root to walk. Not every file the compiler leaves out is left out of a whole root: a root the
     * execution does not narrow is handed to the generator whole, unless javac compiles a source under it that the
     * generator's walk leaves out or that walk would fail on a directory it cannot list, and the generator walks it
     * with Ant's default excludes, not with plexus-utils' longer list.
     */
    @Test
    void theGeneratorSkipsTheFilesTheCompilerExcludesByDefault() throws IOException {
        byte[] appleDouble = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        for (String excludes : List.of("<excludes><exclude>fx/drafts/**</exclude></excludes>", "")) {
            MavenFixture fixture = MavenFixture.basic(Files.createDirectories(
                    tempDirectory.resolve(excludes.isEmpty() ? "whole" : "filtered")), fixtureRepository);
            if (!excludes.isEmpty()) {
                fixture.editPom("<release>21</release>", "<release>21</release>\n          " + excludes);
            }
            fixture.writeFile("src/main/java/fx/._Orders.java", appleDouble);
            fixture.writeFile("src/main/java/fx/CVS/Junk.java", appleDouble);

            MavenResult result = fixture.run("package");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"), excludes);
        }
    }

    /**
     * The scanner of maven-compiler-plugin enters a directory named like a file its default excludes name, such as
     * {@code old~} or {@code .gitignore}, and javac compiles the sources inside it, while the generator, walking a
     * whole root as Gradle walks it, leaves such a directory out. A root the execution does not narrow that holds a
     * source javac compiles inside one is therefore handed to the generator file by file, so the generator reads the
     * machine and the action host declared there.
     */
    @Test
    void theGeneratorReadsTheSourcesTheCompilerCompilesInsideADirectoryNamedLikeAnExcludedFile() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        Map<String, String> sources = MavenFixture.domainSources();
        String orders = sources.remove("fx.Orders");
        String actions = sources.remove("fx.Actions");
        fixture.writeSources(sources);
        fixture.writeFile("src/main/java/fx/old~/Orders.java", orders.getBytes(StandardCharsets.UTF_8));
        fixture.writeFile("src/main/java/fx/.gitignore/Actions.java", actions.getBytes(StandardCharsets.UTF_8));

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.localExports().containsAll(List.of("fx.Orders", "fx.ActionsRefs.Approve")),
                fixture.localExports()::toString);
    }

    /**
     * The directory of a version control system, such as {@code .git}, holds nothing javac compiles, and the
     * generator never enters it: neither does this plugin when it looks for a source the generator would miss, so a
     * directory inside it that cannot be listed, as {@code .git/objects} can be, fails nothing.
     */
    @Test
    void aDirectoryInsideTheDirectoryOfAVersionControlSystemThatCannotBeListedFailsNothing() throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeFile("src/main/java/fx/.git/objects/Junk.java", new byte[0]);
        Path objects = fixture.projectDirectory().resolve("src/main/java/fx/.git/objects");
        assumeTrue(MavenFixture.forbidListing(objects), UNLOCKABLE);
        try {
            MavenResult result = fixture.run("package");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        } finally {
            MavenFixture.allowListing(objects);
        }
    }

    /**
     * The scanner of maven-compiler-plugin reads a directory it cannot list as an empty one, an ordinary directory as
     * well as the directory of a version control system itself, so javac compiles the sources beside it and nothing
     * inside it. The generator, which would fail on such a directory walking the root whole, is handed the files the
     * compiler selects instead, and reads just those.
     */
    @Test
    void aDirectoryTheCompilerCannotListLeavesTheSourcesBesideItToTheGenerator() throws IOException {
        byte[] junk = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.writeFile("src/main/java/fx/locked/Junk.java", junk);
        fixture.writeFile("src/main/java/fx/.svn/Junk.java", junk);
        List<Path> locked = List.of(fixture.projectDirectory().resolve("src/main/java/fx/locked"),
                fixture.projectDirectory().resolve("src/main/java/fx/.svn"));
        try {
            for (Path directory : locked) {
                assumeTrue(MavenFixture.forbidListing(directory), UNLOCKABLE);
            }

            MavenResult result = fixture.run("package");

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        } finally {
            for (Path directory : locked) {
                MavenFixture.allowListing(directory);
            }
        }
    }

    /**
     * Includes or excludes make this plugin hand the generator the files they select, one by one. A hand-written
     * type that only shares the file name of a generated source, in a package that merely ends like the
     * generated one's, is no collision there either: a listed file stands where its package declaration puts it.
     */
    @Test
    void aHandWrittenTypeNamedLikeAGeneratedOneIsNoCollisionWhenTheCompilerFiltersItsSources()
            throws IOException {
        MavenFixture fixture = MavenFixture.basic(tempDirectory, fixtureRepository);
        fixture.editPom("<release>21</release>", "<release>21</release>\n"
                + "          <excludes><exclude>fx/drafts/**</exclude></excludes>");
        Map<String, String> sources = MavenFixture.domainSources();
        sources.put("legacy.fx.OrderRefs", """
                package legacy.fx;

                /** A hand-written type named like the generated fx.OrderRefs, in another package. */
                public final class OrderRefs {

                    private OrderRefs() {
                    }
                }
                """);
        fixture.writeSources(sources);

        MavenResult result = fixture.run("package");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertTrue(fixture.jarContains("legacy/fx/OrderRefs.class"));
    }

    /**
     * Asserts that the index {@code exports} publishes origins, and that each of them, resolved lexically against
     * {@code projectDirectory}, names a regular file.
     */
    private static void assertOriginsLeadToSources(Path projectDirectory, Path exports) throws IOException {
        String index = Files.readString(exports);
        Matcher origins = Pattern.compile("\"path\": \"([^\"]*)\"").matcher(index);
        int found = 0;
        while (origins.find()) {
            found++;
            Path named = projectDirectory.resolve(origins.group(1)).normalize();
            assertTrue(Files.isRegularFile(named), origins.group(1) + " leads from " + projectDirectory + " to "
                    + named + ", which is no source");
        }
        assertTrue(found > 0, index);
    }

    /**
     * Asserts that {@code result} is a successful build that sealed the main inventory and left in {@code exports}
     * the very bytes of the index {@code published}.
     */
    private static void assertBuildPublishesTheSameBytes(MavenFixture fixture, MavenResult result, Path exports,
            byte[] published) throws IOException {
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertArrayEquals(published, Files.readAllBytes(exports), Files.readString(exports));
    }

    /** A source javac refuses to parse, which only a build that excludes it can carry. */
    private static String draft(String packageName) {
        return """
                package %s;

                /** Work in progress, excluded from the build until it compiles. */
                public final class Draft {

                    void unfinished( {
                }
                """.formatted(packageName);
    }
}
