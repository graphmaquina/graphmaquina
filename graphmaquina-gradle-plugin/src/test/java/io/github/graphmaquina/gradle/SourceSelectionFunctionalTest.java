package io.github.graphmaquina.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.graphmaquina.gradle.PluginFixture.FIXTURE_REPOSITORY;
import static io.github.graphmaquina.gradle.PluginFixture.MAIN_PENDING_INVENTORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests driving the StateGraph Gradle plugin through TestKit over which sources a build hands to the
 * generator: what the source set and the compilation task include, exclude and filter, what Gradle's default
 * excludes leave out, and the refusals that say how to give a source to the source set instead.
 */
class SourceSelectionFunctionalTest {

    @TempDir
    Path tempDirectory;

    /**
     * Another task of the build generates sources into the same source set — the role ANTLR-, protobuf- or
     * OpenAPI-style generators play — and registers its output directory with the task that writes it. The
     * generation parses that directory too, so it runs after that task: Gradle refuses a task that reads another
     * one's output without depending on it, and a generation that ran first would parse nothing there.
     */
    @Test
    void generatesAfterAnotherTaskThatGeneratesIntoTheSourceSet() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeFile("other-generator/fx/other/Helper.java", """
                package fx.other;

                import fx.Order;
                import fx.Status;
                import fx.Transition;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;

                /** An action host another generator of the build writes. */
                public final class Helper {

                    @GraphAction
                    public ActionOutcome ship(TransitionContext<Order, Status, Transition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        fixture.appendToBuildScript("""
                val generateOther = tasks.register<Sync>("generateOther") {
                    from("other-generator")
                    into(layout.buildDirectory.dir("generated/sources/other/main"))
                }
                sourceSets.named("main") {
                    java.srcDir(generateOther)
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateOther").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertTrue(fixture.localExports().contains("fx.other.HelperRefs.Ship"), fixture.localExports()::toString);
        assertTrue(fixture.jarContains("fx/other/HelperRefs$Ship.class"));
    }

    /**
     * The generator parses what javac compiles: the source set as its include and exclude patterns filter it. A
     * draft the build excludes may not even parse, and an excluded facade must not get a vocabulary javac then
     * finds no declaration for.
     */
    @Test
    void parsesOnlyTheSourcesTheSourceSetIncludes() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.drafts.Draft", """
                package fx.drafts;

                /** A draft the build leaves out; it does not parse. */
                public final class Draft {

                    this is not a declaration
                }
                """);
        sources.put("fx.legacy.OldOrders", legacyOldOrders());
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java {
                        exclude("fx/drafts/**")
                        exclude("fx/legacy/**")
                    }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> requested = requestedSources(fixture.request("main"));
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("fx/drafts/")),
                requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("fx/legacy/")),
                requested::toString);
        assertTrue(requested.contains("fx/Orders.java"), requested::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/legacy/OldOrdersGraph.class"));
        assertFalse(fixture.localExports().contains("fx.legacy.OldOrders"));
    }

    /**
     * The java sources of a source set carry a filter of their own beside their patterns, the one the {@code java}
     * plugin gives its {@code **}{@code /*.java} include, and javac compiles what it selects. The generator reads the
     * source set that way too, file by file, whether the filter leaves a draft that does not parse out with a pattern
     * or with a spec, while the source set and the compilation task carry no pattern at all.
     */
    @Test
    void parsesOnlyTheSourcesTheFilterOfTheJavaSourcesSelects() throws IOException {
        for (String exclusion : List.of("\"**/Draft.java\"", "{ element -> element.file.name == \"Draft.java\" }")) {
            PluginFixture fixture = PluginFixture.basic(Files.createDirectories(
                    tempDirectory.resolve(exclusion.startsWith("{") ? "spec" : "pattern")), FIXTURE_REPOSITORY);
            Map<String, String> sources = PluginFixture.domainSources();
            sources.put("fx.drafts.Draft", draft("fx.drafts"));
            fixture.writeSources(sources);
            fixture.appendToBuildScript("sourceSets.named(\"main\") {\n    java.filter.exclude(" + exclusion
                    + ")\n}\n");

            BuildResult result = fixture.run("jar");

            assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome(), exclusion);
            List<String> requested = requestedSources(fixture.request("main"));
            assertTrue(requested.contains("fx/Orders.java"), requested::toString);
            assertFalse(requested.contains("fx/drafts/Draft.java"), requested::toString);
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"), exclusion);
        }
    }

    /**
     * A source directory that neither the source set nor its compilation task filters is still handed to the
     * generator as a directory, so a hand-written type that shares its simple name with a generated one, in a package
     * that merely ends like the generated one's, is no collision: the generator compares a generated type with the
     * original sources by the path each stands at below its source root and by the types it declares, not by how that
     * path ends.
     */
    @Test
    void aWholeSourceDirectoryStaysADirectoryOfTheRequest() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("legacy.fx.OrderRefs", """
                package legacy.fx;

                /** A hand-written type named like the generated fx.OrderRefs, in another package. */
                public final class OrderRefs {

                    private OrderRefs() {
                    }
                }
                """);
        fixture.writeSources(sources);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        List<String> requested = new ArrayList<>();
        for (JsonElement source : fixture.request("main").getAsJsonArray("sources")) {
            requested.add(source.getAsString());
        }
        assertEquals(1, requested.size(), requested.toString());
        assertTrue(requested.get(0).endsWith("/src/main/java"), requested.toString());
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertTrue(fixture.jarContains("legacy/fx/OrderRefs.class"));
    }

    /**
     * The generated vocabulary is a source directory set of its own, nested in the java sources of the source
     * set, so the include and exclude patterns a build gives the source set filter its original sources only: a
     * source set that includes nothing but its own package still compiles everything the generation wrote, the
     * compilation carrier included, and neither the generator nor javac reads what it leaves out.
     */
    @Test
    void compilesEverythingGeneratedWhateverTheSourceSetIncludes() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("other.Draft", """
                package other;

                /** A draft outside the included package; it does not parse. */
                public final class Draft {

                    this is not a declaration
                }
                """);
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java { include("fx/**") }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContainsGraphCompilationCarrier());
        assertTrue(fixture.localExports().contains("fx.Orders"));
        List<String> requested = requestedSources(fixture.request("main"));
        assertTrue(requested.contains("fx/Orders.java"), requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("other/")),
                requested::toString);
    }

    /**
     * javac compiles the source set as the include and exclude patterns of the compilation task filter it too, so the
     * generator reads it that way: the task's include leaves out a draft outside it that does not parse, and its
     * excludes leave out a draft inside it and a facade, which then gets no vocabulary javac would find no
     * declaration for. The include also names the package of the compilation carrier, since it filters the
     * generated sources as well.
     */
    @Test
    void parsesOnlyTheSourcesTheCompilationTaskIncludes() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("other.Draft", draft("other"));
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        sources.put("fx.Legacy", PluginFixture.orders("Legacy", "Approve", "approve"));
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    include("fx/**", "io/github/graphmaquina/generated/**")
                    exclude("fx/drafts/**", "**/Legacy.java")
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> requested = requestedSources(fixture.request("main"));
        assertTrue(requested.contains("fx/Orders.java"), requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("other/")),
                requested::toString);
        assertFalse(requested.stream().anyMatch((String source) -> source.startsWith("fx/drafts/")),
                requested::toString);
        assertFalse(requested.contains("fx/Legacy.java"), requested::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("fx/LegacyGraph.class"));
        assertFalse(fixture.localExports().contains("fx.Legacy"), fixture.localExports()::toString);
    }

    /**
     * A filter the compilation task is given as a spec or a closure is one the generation cannot read, so the
     * generation refuses it before the generator runs, which would otherwise report the draft that filter leaves out
     * with {@code SG601}, and says to move it to the source set. The source set's own filters are followed, specs
     * included, so the build that follows that advice builds.
     */
    @Test
    void refusesAFilterOfTheCompilationTaskBeforeTheGeneratorRuns() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    exclude { element -> element.file.name == "Draft.java" }
                }
                """);

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":generateStateGraphApi").getOutcome());
        assertTrue(refused.getOutput().contains(
                "fx/drafts/Draft.java, which task ':compileJava' does not compile"), refused::getOutput);
        assertTrue(refused.getOutput().contains("When task ':compileJava' is given such a filter, give it to the java"
                + " sources of the source set instead"),
                refused::getOutput);
        assertFalse(refused.getOutput().contains("[" + DiagnosticCode.SG601 + "]"), refused::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("tasks.named<JavaCompile>(\"compileJava\") {\n    exclude {",
                "sourceSets.named(\"main\") {\n    java.exclude {");

        BuildResult moved = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, moved.task(":compileJava").getOutcome());
        assertTrue(fixture.inventoryIsSealed("main"));
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * A filter or a source the compilation task is given after a generation leaves that generation up to date, since
     * it changes nothing the generation reads; the preparation then refuses it, before it retires anything and before
     * javac runs: a closure that leaves a facade out of the compilation, whose vocabulary javac would compile without
     * it; a directory added to the compilation alone, whose sources the generation never read, which the refusal names
     * as the directory to give the source set; and a source put in place of the source set's, which the refusal tells
     * to give the source set instead, just as the generation itself would have told.
     */
    @Test
    void refusesAFilterOrASourceOfTheCompilationTaskBeforeThePreparation() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.Legacy", PluginFixture.orders("Legacy", "Approve", "approve"));
        fixture.writeSources(sources);
        fixture.writeFile("src/extra/java/extra/Helper.java", extraHelper());
        fixture.run("jar");
        assertTrue(fixture.jarContains("fx/LegacyGraph.class"));
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    exclude { element -> element.file.name == "Legacy.java" }
                }
                """);

        BuildResult filtered = fixture.runFailing("jar");

        assertEquals(TaskOutcome.UP_TO_DATE, filtered.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.FAILED, filtered.task(":compileJava").getOutcome());
        assertTrue(filtered.getOutput().contains("fx/Legacy.java, which task ':compileJava' does not compile"),
                filtered::getOutput);
        assertFalse(filtered.getOutput().contains("cannot find symbol"), "javac never ran");
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("exclude { element -> element.file.name == \"Legacy.java\" }",
                "source(\"src/extra/java\")");

        BuildResult added = fixture.runFailing("jar");

        assertEquals(TaskOutcome.UP_TO_DATE, added.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.FAILED, added.task(":compileJava").getOutcome());
        assertTrue(added.getOutput().contains("task ':compileJava' compiles "), added::getOutput);
        assertTrue(added.getOutput().contains(
                "extra/Helper.java, which the StateGraph generation generateStateGraphApi does not read"),
                added::getOutput);
        assertTrue(added.getOutput().contains("/src/extra/java, the directory task ':compileJava' takes that file"
                + " from, to the java sources of the source set instead, with java.srcDir(...)"), added::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");

        fixture.replaceInBuildScript("source(\"src/extra/java\")", "setSource(fileTree(\"src/extra/java\"))");

        BuildResult replaced = fixture.runFailing("jar");

        assertEquals(TaskOutcome.UP_TO_DATE, replaced.task(":generateStateGraphApi").getOutcome());
        assertEquals(TaskOutcome.FAILED, replaced.task(":compileJava").getOutcome());
        assertTrue(replaced.getOutput().contains(", which task ':compileJava' does not compile."),
                replaced::getOutput);
        assertTrue(replaced.getOutput().contains("/src/extra/java, in place of its own, add the generated directory"
                + " back after them, and remove that source from task ':compileJava'"), replaced::getOutput);
        assertFalse(fixture.exists(MAIN_PENDING_INVENTORY), "the preparation never ran");
    }

    /**
     * A source the compilation task is given in place of the source set's is refused before the generator runs, and
     * the refusal's fix builds as the refusal gives it: the source set takes the directories the task compiled, in
     * place of its own, the generated directory is added back after them, and the task's source goes. The legacy
     * class the replacement left out stays out. The fix also builds when the task was given no source at all, so
     * that the source set keeps no directory of its own.
     */
    @Test
    void refusesASourcePutInPlaceOfTheSourceSetsWithAFixThatBuilds() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.writeSources(Map.of("legacy.Old", legacyOld()));
        for (Map.Entry<String, String> source : PluginFixture.domainSources().entrySet()) {
            fixture.writeFile("src/other/java/" + source.getKey().replace('.', '/') + ".java", source.getValue());
        }
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    setSource(fileTree("src/other/java"))
                }
                """);

        BuildResult refused = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, refused.task(":generateStateGraphApi").getOutcome());
        assertTrue(refused.getOutput().contains("legacy/Old.java, which task ':compileJava' does not compile"),
                refused::getOutput);
        Matcher remedy = Pattern.compile("Kotlin DSL: (.+)").matcher(refused.getOutput());
        assertTrue(remedy.find(), refused::getOutput);
        assertTrue(remedy.group(1).contains("/src/other/java"), remedy.group(1));
        fixture.replaceInBuildScript("setSource(fileTree(\"src/other/java\"))", "");
        fixture.appendToBuildScript("sourceSets.named(\"main\") {\n    " + remedy.group(1).strip() + "\n}\n");

        BuildResult fixed = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, fixed.task(":compileJava").getOutcome());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertFalse(fixture.jarContains("legacy/Old.class"));
        assertTrue(fixture.inventoryIsSealed("main"));

        PluginFixture nothing = PluginFixture.basic(
                Files.createDirectories(tempDirectory.resolve("nothing")), FIXTURE_REPOSITORY);
        nothing.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    setSource(files())
                }
                """);

        BuildResult refusedNothing = nothing.runFailing("jar");

        Matcher noDirectory = Pattern.compile("Kotlin DSL: (.+)").matcher(refusedNothing.getOutput());
        assertTrue(noDirectory.find(), refusedNothing::getOutput);
        nothing.replaceInBuildScript("setSource(files())", "");
        nothing.appendToBuildScript("sourceSets.named(\"main\") {\n    " + noDirectory.group(1).strip() + "\n}\n");

        BuildResult fixedNothing = nothing.run("jar");

        assertEquals(TaskOutcome.SUCCESS, fixedNothing.task(":compileJava").getOutcome());
        assertFalse(nothing.jarContains("fx/OrdersGraph.class"));
        assertTrue(nothing.inventoryIsSealed("main"));
    }

    /**
     * The generated directory is a source root the plugin adds to the source set when the source set is
     * created, so a build script that later replaces the source set's directories drops it. The compilation
     * then names that replacement, instead of javac failing on every generated type it cannot find, and the
     * remedy it prints works as printed, even for a source set whose include pattern leaves out the package of
     * the compilation carrier.
     */
    @Test
    void refusesASourceSetWhoseDirectoriesDroppedTheGeneratedRoot() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java.setSrcDirs(listOf("src/main/java"))
                }
                """);

        BuildResult result = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains("does not compile"), result::getOutput);
        assertTrue(result.getOutput().contains("java.setSrcDirs"), result::getOutput);
        assertFalse(result.getOutput().contains("package OrdersGraph does not exist"), result::getOutput);

        Matcher remedy = Pattern.compile("Kotlin DSL: (.+)").matcher(result.getOutput());
        assertTrue(remedy.find(), result::getOutput);
        fixture.appendToBuildScript("sourceSets.named(\"main\") {\n"
                + "    java { include(\"fx/**\") }\n"
                + "    " + remedy.group(1).strip() + "\n"
                + "}\n");

        BuildResult remedied = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, remedied.task(":compileJava").getOutcome());
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
        assertTrue(fixture.inventoryIsSealed("main"));
    }

    /**
     * A build that keeps Gradle's default excludes leaves out of every source directory the files the Ant-style
     * default excludes name, such as the AppleDouble companion {@code ._Orders.java} a copy from macOS leaves beside
     * a source, or anything inside the directory of a version control system. The generator leaves them out too,
     * whether the source set hands it a filtered directory file by file or a whole directory to walk.
     */
    @Test
    void skipsTheFilesABuildExcludesByDefault() throws IOException {
        byte[] appleDouble = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        for (String filter : List.of("exclude(\"fx/drafts/**\")", "")) {
            PluginFixture fixture = PluginFixture.basic(Files.createDirectories(
                    tempDirectory.resolve(filter.isEmpty() ? "whole" : "filtered")), FIXTURE_REPOSITORY);
            fixture.writeFile("src/main/java/fx/._Orders.java", appleDouble);
            fixture.writeFile("src/main/java/fx/CVS/Junk.java", appleDouble);
            if (!filter.isEmpty()) {
                fixture.appendToBuildScript("sourceSets.named(\"main\") {\n    java { " + filter + " }\n}\n");
            }

            BuildResult result = fixture.run("jar");

            assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome(), filter);
            assertTrue(fixture.jarContains("fx/OrdersGraph.class"), filter);
        }
    }

    /**
     * Gradle leaves out of a source directory, with everything inside it, every directory a default exclude names,
     * not only the directory of a version control system: one named like an editor's backup or lock file, such as
     * {@code old~} or {@code .#old}, like an AppleDouble companion or like a file of a version control system, such
     * as {@code .gitignore}. javac never compiles a file inside one, and the generator, handed the source directory
     * whole, never reads one either.
     */
    @Test
    void skipsTheDirectoriesABuildExcludesByDefault() throws IOException {
        byte[] junk = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        for (String directory : List.of("~", "old~", "#old#", ".#old", "%old%", "._old", ".DS_Store", ".cvsignore",
                "vssver.scc", ".gitattributes", ".gitignore", ".gitmodules", ".hgignore", ".hgsub", ".hgsubstate",
                ".hgtags", ".bzrignore")) {
            fixture.writeFile("src/main/java/fx/" + directory + "/Junk.java", junk);
        }

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        JsonArray requested = fixture.request("main").getAsJsonArray("sources");
        assertEquals(1, requested.size(), requested::toString);
        assertTrue(Path.of(requested.get(0).getAsString()).endsWith(Path.of("src", "main", "java")),
                requested::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * A settings script may change the default excludes of the build, as Gradle documents, through Ant's
     * {@code DirectoryScanner}. javac never compiles a file a default exclude the build adds leaves out, and the
     * generator, which leaves out only Ant's own default excludes when it walks a whole directory, is handed the
     * files Gradle selects instead, so it never reads a draft that does not parse either.
     */
    @Test
    void leavesOutWhatADefaultExcludeOfTheBuildLeavesOut() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("fx.drafts.Draft", draft("fx.drafts"));
        fixture.writeSources(sources);
        Path settings = fixture.projectDirectory().resolve("settings.gradle.kts");
        Files.writeString(settings, Files.readString(settings)
                + "\norg.apache.tools.ant.DirectoryScanner.addDefaultExclude(\"**/Draft.java\")\n");

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        List<String> requested = requestedSources(fixture.request("main"));
        assertTrue(requested.contains("fx/Orders.java"), requested::toString);
        assertFalse(requested.contains("fx/drafts/Draft.java"), requested::toString);
        assertTrue(fixture.jarContains("fx/OrdersGraph.class"));
    }

    /**
     * A source set filtered by a pattern hands the generator its files one by one. A hand-written type that only
     * shares the file name of a generated source, in a package that merely ends like the generated one's, is no
     * collision there either: a listed file stands where its package declaration puts it.
     */
    @Test
    void aHandWrittenTypeNamedLikeAGeneratedOneIsNoCollisionInAFilteredSourceSet() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        Map<String, String> sources = PluginFixture.domainSources();
        sources.put("legacy.fx.OrderRefs", """
                package legacy.fx;

                /** A hand-written type named like the generated fx.OrderRefs, in another package. */
                public final class OrderRefs {

                    private OrderRefs() {
                    }
                }
                """);
        fixture.writeSources(sources);
        fixture.appendToBuildScript("""
                sourceSets.named("main") {
                    java { exclude("fx/drafts/**") }
                }
                """);

        BuildResult result = fixture.run("jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStateGraphApi").getOutcome());
        List<String> requested = requestedSources(fixture.request("main"));
        assertTrue(requested.contains("legacy/fx/OrderRefs.java"), requested::toString);
        assertTrue(fixture.jarContains("fx/OrderRefs$Status.class"));
        assertTrue(fixture.jarContains("legacy/fx/OrderRefs.class"));
    }

    /**
     * A source or a pattern set on the compile task itself filters the generated sources too, and no line added
     * to the source set can undo that, so the refusal also says to remove that setting from the task.
     */
    @Test
    void refusesACompilationTaskThatFiltersOutTheGeneratedSourcesAndSaysToRemoveThatFilter() throws IOException {
        PluginFixture fixture = PluginFixture.basic(tempDirectory, FIXTURE_REPOSITORY);
        fixture.appendToBuildScript("""
                tasks.named<JavaCompile>("compileJava") {
                    exclude("io/github/graphmaquina/generated/**")
                }
                """);

        BuildResult result = fixture.runFailing("jar");

        assertEquals(TaskOutcome.FAILED, result.task(":compileJava").getOutcome());
        assertTrue(result.getOutput().contains("does not compile"), result::getOutput);
        assertTrue(result.getOutput().contains("remove that setting from task ':compileJava'"), result::getOutput);
    }

    /** The source of {@code fx.legacy.OldOrders}, a machine of the legacy package that the source set leaves out. */
    private static String legacyOldOrders() {
        return """
                package fx.legacy;

                import fx.ActionsRefs;
                import fx.Order;
                import fx.OrderRefs;
                import fx.Status;
                import fx.Transition;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;

                import static fx.Status.DONE;
                import static fx.Status.NEW;
                import static fx.Transition.APPROVE;

                /** A machine the build leaves out. */
                @GenerateGraphApi
                @OldOrdersGraph.Definition(initial = NEW, terminals = DONE,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OldOrdersGraph.StatePolicy(state = NEW, otherwise = "a new order is approved")
                public interface OldOrders extends Machine<Order, Status, Transition> {

                    @OldOrdersGraph.Transition(id = APPROVE, from = NEW, to = DONE, action = ActionsRefs.Approve.class)
                    Outcome<Order, Status, Transition> approve(Order order);
                }
                """;
    }

    /** The source of {@code extra.Helper}, a class that only the compilation task is given. */
    private static String extraHelper() {
        return """
                package extra;

                /** A class only the compilation task is given. */
                public final class Helper {

                    private Helper() {
                    }
                }
                """;
    }

    /** The source of {@code legacy.Old}, a class that only the source set holds. */
    private static String legacyOld() {
        return """
                package legacy;

                /** A class only the source set holds. */
                public final class Old {

                    private Old() {
                    }
                }
                """;
    }

    /** A class of {@code packageName} that does not parse, so the generator fails with {@code SG601} if it reads it. */
    private static String draft(String packageName) {
        return """
                package %s;

                /** A draft the build leaves out; it does not parse. */
                public final class Draft {

                    this is not a declaration
                }
                """.formatted(packageName);
    }

    /**
     * The sources a request lists, each relative to the {@code src/main/java} root it lies under; a source that
     * lies under no such root fails the calling test.
     *
     * @param request the parsed request
     * @return the relative paths, sorted
     */
    private static List<String> requestedSources(JsonObject request) {
        String root = "/src/main/java/";
        List<String> requested = new ArrayList<>();
        for (JsonElement source : request.getAsJsonArray("sources")) {
            String path = source.getAsString();
            int index = path.indexOf(root);
            assertTrue(index >= 0, path);
            requested.add(path.substring(index + root.length()));
        }
        requested.sort(String::compareTo);
        return requested;
    }
}
