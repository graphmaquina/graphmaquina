package io.github.graphmaquina.codegen.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.GenerationResult;
import io.github.graphmaquina.codegen.StateGraphCodegen;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.manifest.OwnedOutputs;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that one generation is published as a coherent set: the manifest describes exactly the files on disk,
 * a run that changes nothing rewrites the same bytes, deleting a reference retires the file it owned, a file the
 * generation does not own is left alone, and a type that would overwrite an original source is refused.
 */
class GenerationOutputTest {

    @TempDir
    Path directory;

    private static final Map<String, String> ORIGINALS = originals();

    private static Map<String, String> originals() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
            package fx;
            public enum DocStatus { DRAFT, PUBLISHED }
            """);
        sources.put("fx.DocTransition", """
            package fx;
            public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }
            """);
        sources.put("fx.Doc", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            public class Doc {
                @GraphProperty
                private DocStatus status;
            }
            """);
        sources.put("fx.Docs", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {}
            """);
        sources.put("fx.DocActions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            public class DocActions {
                @GraphAction
                public Doc publish(Doc doc) { return doc; }
            }
            """);
        return Map.copyOf(sources);
    }

    @Test
    void publishesEverySourceAndAManifestThatDescribesThem() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        GenerationResult result = StateGraphCodegen.generate(request());

        assertEquals(directory.resolve("work").resolve("annotation-manifest.json").toAbsolutePath(),
                result.annotationManifest());
        assertEquals(List.of("fx/DocActionsRefs.java", "fx/DocRefs.java", "fx/DocsGraph.java"),
                result.generatedSources().stream().map(file -> output().relativize(file).toString())
                        .filter(name -> name.startsWith("fx")).sorted().toList());

        CompileManifest manifest = ManifestCodec.readCompile(result.annotationManifest());
        assertEquals(2, manifest.schemaVersion());
        assertEquals(ToolVersion.current(), manifest.toolVersion());
        assertEquals("io.github.graphmaquina:test:main", manifest.local().compilationId());
        assertEquals(List.of(), manifest.dependencies());
        assertEquals(directory.resolve("work").resolve("processor-inventory.json").toAbsolutePath().toString(),
                manifest.processorInventory());
        assertTrue(manifest.ownedSources().contains("fx/DocsGraph.java"), manifest.ownedSources().toString());
        assertTrue(manifest.ownedSources().stream().anyMatch(
                owned -> owned.startsWith("io/github/graphmaquina/generated/bootstrap/GraphCompilation")),
                manifest.ownedSources().toString());
        assertTrue(manifest.local().annotationTypes().contains("fx.DocsGraph.Transitions"),
                manifest.local().annotationTypes().toString());
        assertEquals(published(), manifest.ownedSources().stream().sorted().toList());
    }

    @Test
    void rewritesTheSameBytesWhenNothingChanged() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        Map<String, String> first = contents();

        StateGraphCodegen.generate(request());

        assertEquals(first, contents());
    }

    @Test
    void retiresTheSourcesOfADeletedReferenceAndKeepsWhatItDoesNotOwn() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        assertTrue(Files.isRegularFile(output().resolve("fx/DocActionsRefs.java")));
        Path manual = output().resolve("fx/Handwritten.java");
        Files.writeString(manual, "package fx;\npublic final class Handwritten {}\n", StandardCharsets.UTF_8);
        String manualBefore = Files.readString(manual, StandardCharsets.UTF_8);
        Path original = sources().resolve("fx/Docs.java");
        String originalBefore = Files.readString(original, StandardCharsets.UTF_8);

        Files.delete(sources().resolve("fx/DocActions.java"));
        StateGraphCodegen.generate(request());

        assertFalse(Files.exists(output().resolve("fx/DocActionsRefs.java")));
        assertTrue(Files.isRegularFile(output().resolve("fx/DocsGraph.java")));
        assertEquals(manualBefore, Files.readString(manual, StandardCharsets.UTF_8));
        assertEquals(originalBefore, Files.readString(original, StandardCharsets.UTF_8));
        assertFalse(ManifestCodec.readCompile(request().workDirectory().resolve("annotation-manifest.json"))
                .ownedSources().contains("fx/DocActionsRefs.java"));
    }

    @Test
    void replacesPriorSpellingSourcesAndBytecodeUsingOnlyTheirInventories() throws IOException {
        SourceFiles.write(sources(), Map.of("fx.Outer", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            public class Outer {
                public static class Inner { @GraphProperty public String _id; }
            }
            """));
        GenerationResult initial = StateGraphCodegen.generate(request());
        CompileManifest current = ManifestCodec.readCompile(initial.annotationManifest());
        String carrier = current.ownedSources().stream().filter(path -> path.contains("GraphCompilation"))
            .findFirst().orElseThrow();
        String oldCarrier = carrier.replace("GraphCompilation", "GraphCompilation_");
        String oldCarrierType = oldCarrier.substring(oldCarrier.lastIndexOf('/') + 1).replace(".java", "");
        for (Path generated : initial.generatedSources()) {
            Files.delete(generated);
        }
        List<Path> oldFiles = SourceFiles.write(output(), Map.of(
            "fx.Outer_InnerRefs", """
                package fx;
                public final class Outer_InnerRefs { public static final class __id {} }
                """,
            "io.github.graphmaquina.generated.bootstrap." + oldCarrierType,
            "package io.github.graphmaquina.generated.bootstrap; public final class " + oldCarrierType + " {}",
            "fx.Outer_InnerRefsManual", "package fx; public final class Outer_InnerRefsManual {}"));
        SourceFiles.Compilation compiled = SourceFiles.compileStrict(oldFiles, request().classesOutput());
        assertTrue(compiled.success(), () -> String.valueOf(compiled.messages()));
        ManifestCodec.writeCompile(initial.annotationManifest(), new CompileManifest(current.schemaVersion(),
            "1.0.0", current.local(), current.dependencies(), current.processorInventory(),
            List.of("fx/Outer_InnerRefs.java", oldCarrier)));
        ManifestCodec.writeOwned(request().workDirectory().resolve("owned-outputs.json"), new OwnedOutputs(2,
            List.of("fx/Outer_InnerRefs.java", oldCarrier),
            List.of("fx/Outer_InnerRefs.class", "fx/Outer_InnerRefs$__id.class"), List.of(),
            List.of(oldCarrier.replace(".java", ".class"))));
        Path manualSource = output().resolve("fx/Outer_InnerRefsManual.java");
        Path manualClass = request().classesOutput().resolve("fx/Outer_InnerRefsManual.class");
        String manualBefore = Files.readString(manualSource);
        byte[] classBefore = Files.readAllBytes(manualClass);
        String originalBefore = Files.readString(sources().resolve("fx/Outer.java"));

        StateGraphCodegen.generate(request());
        CompilationOutputs.prepare(request());

        assertTrue(Files.isRegularFile(output().resolve("fx/OuterInnerRefs.java")));
        assertTrue(Files.isRegularFile(output().resolve(carrier)));
        assertFalse(Files.exists(output().resolve("fx/Outer_InnerRefs.java")));
        assertFalse(Files.exists(output().resolve(oldCarrier)));
        assertFalse(Files.exists(request().classesOutput().resolve("fx/Outer_InnerRefs.class")));
        assertFalse(Files.exists(request().classesOutput().resolve("fx/Outer_InnerRefs$__id.class")));
        assertFalse(Files.exists(request().classesOutput().resolve(oldCarrier.replace(".java", ".class"))));
        assertEquals(manualBefore, Files.readString(manualSource));
        org.junit.jupiter.api.Assertions.assertArrayEquals(classBefore, Files.readAllBytes(manualClass));
        assertEquals(originalBefore, Files.readString(sources().resolve("fx/Outer.java")));
    }

    @Test
    void refusesAGeneratedTypeAnOriginalSourceAlreadyDeclares() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        SourceFiles.write(sources(), Map.of("fx.DocsGraph", "package fx;\npublic final class DocsGraph {}\n"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("fx.DocsGraph"), failure.getMessage());
        assertTrue(failure.getMessage().contains(sources().resolve("fx/DocsGraph.java").toString()),
                failure.getMessage());
        assertFalse(Files.exists(directory.resolve("work").resolve("annotation-manifest.json")));
    }

    @Test
    void deletesTheManifestWhenTheRunFails() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        assertTrue(Files.isRegularFile(directory.resolve("work").resolve("annotation-manifest.json")));

        SourceFiles.write(sources(), Map.of("fx.DocsGraph", "package fx;\npublic final class DocsGraph {}\n"));
        assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));

        assertFalse(Files.exists(directory.resolve("work").resolve("annotation-manifest.json")));
    }

    @Test
    void retiresDeletedReferencesAfterRepeatedFailedGenerations() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        Path manual = output().resolve("fx/Handwritten.java");
        String handwritten = "package fx; public final class Handwritten {}\n";
        Files.writeString(manual, handwritten);

        SourceFiles.write(sources(), Map.of("fx.DocsGraph", "package fx; public final class DocsGraph {}"));
        for (int attempt = 0; attempt < 2; attempt++) {
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> StateGraphCodegen.generate(request()));
            assertEquals("SG603", failure.code());
            assertFalse(Files.exists(request().workDirectory().resolve("annotation-manifest.json")));
        }
        Files.delete(sources().resolve("fx/DocsGraph.java"));
        Files.delete(sources().resolve("fx/DocActions.java"));
        GenerationResult result = StateGraphCodegen.generate(request());

        assertFalse(Files.exists(output().resolve("fx/DocActionsRefs.java")));
        assertEquals(handwritten, Files.readString(manual));
        assertEquals(published().stream().filter(name -> !name.equals("fx/Handwritten.java")).toList(),
                ManifestCodec.readCompile(result.annotationManifest()).ownedSources().stream().sorted().toList());
    }

    @Test
    void refusesToOverwriteAnUnownedFileInTheGeneratedSourceDirectory() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        Path manual = output().resolve("fx/DocActionsRefs.java");
        Files.createDirectories(manual.getParent());
        String handwritten = "package fx; public final class DocActionsRefs {}\n";
        Files.writeString(manual, handwritten);

        for (int attempt = 0; attempt < 2; attempt++) {
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> StateGraphCodegen.generate(request()));
            assertEquals("SG603", failure.code());
            assertEquals(handwritten, Files.readString(manual));
        }
    }

    @Test
    void refusesAGeneratedTypeAlreadyPresentInADependency() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        Path work = directory.resolve("dependency");
        Path jar = SourceFiles.compileToJar(work,
                Map.of("fx.DocsGraph", "package fx; public final class DocsGraph {}"),
                directory.resolve("dependency.jar"));
        for (Path dependency : List.of(jar, work.resolve("jar-classes"))) {
            GenerationRequest original = request();
            List<Path> classpath = new ArrayList<>(original.classpath());
            classpath.add(dependency);
            GenerationRequest withDependency = new GenerationRequest(original.projectDirectory(), original.sources(),
                    classpath, original.sourcesOutput(), original.processorSourcesOutput(), original.classesOutput(),
                    original.workDirectory(), original.compilationId());
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> StateGraphCodegen.generate(withDependency));
            assertEquals("SG603", failure.code());
            assertTrue(failure.getMessage().contains("fx.DocsGraph"));
            assertTrue(failure.getMessage().contains(dependency.toString()));
        }
    }

    /**
     * A previous manifest this run cannot read, here the one GraphMaquina 1.x left under the same name, stays
     * where it is: the refusal repeats until the user cleans the build, instead of the next run calling the
     * files that manifest owned unowned.
     */
    @Test
    void keepsAPreviousManifestItCannotReadSoTheRefusalRepeats() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        Path manifest = directory.resolve("work").resolve("annotation-manifest.json");
        String retired = Files.readString(manifest).replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 1");
        Files.writeString(manifest, retired);

        for (int attempt = 0; attempt < 2; attempt++) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));
            assertEquals("SG602", failure.code());
            assertTrue(failure.getMessage().startsWith("the schema 1 of " + manifest), failure.getMessage());
            assertEquals(retired, Files.readString(manifest));
        }
    }

    /**
     * The manifest is replaced whole, never rewritten where it lies, so an interrupted write cannot leave half a
     * manifest that every later run would refuse: a second link to the previous file still reads what it said.
     */
    @Test
    void replacesTheManifestInsteadOfRewritingItInPlace() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        GenerationResult first = StateGraphCodegen.generate(request());
        byte[] previous = Files.readAllBytes(first.annotationManifest());
        Path snapshot = Files.createLink(directory.resolve("snapshot.json"), first.annotationManifest());
        Files.delete(sources().resolve("fx/DocActions.java"));

        GenerationResult second = StateGraphCodegen.generate(request());

        assertArrayEquals(previous, Files.readAllBytes(snapshot));
        assertFalse(Arrays.equals(previous, Files.readAllBytes(second.annotationManifest())));
    }

    /**
     * On a case-insensitive volume a machine renamed only in case finds its previous vocabulary under the old
     * spelling. That file is this generator's own, so it is replaced rather than refused as somebody else's.
     */
    @Test
    void replacesItsOwnVocabularyWhenAMachineIsRenamedOnlyInCase() throws IOException {
        assumeTrue(caseInsensitive(directory), "only a case-insensitive volume finds a file under another case");
        SourceFiles.write(sources(), withMachine("HtmlDocs"));
        StateGraphCodegen.generate(request());
        assertEquals(List.of("HtmlDocsGraph.java"), vocabularies());
        Files.delete(sources().resolve("fx/HtmlDocs.java"));
        SourceFiles.write(sources(), withMachine("HTMLDocs"));

        GenerationResult result = StateGraphCodegen.generate(request());

        assertEquals(List.of("HTMLDocsGraph.java"), vocabularies());
        List<String> owned = ManifestCodec.readCompile(result.annotationManifest()).ownedSources();
        assertTrue(owned.contains("fx/HTMLDocsGraph.java"), owned.toString());
        assertFalse(owned.contains("fx/HtmlDocsGraph.java"), owned.toString());
    }

    /**
     * Reading a file's own name on disk resolves no link: a symbolic link somebody placed in the generated
     * directory stays theirs, even when it points at a file the generation owns.
     */
    @Test
    void refusesToReplaceALinkPlacedInTheGeneratedSourceDirectory() throws IOException {
        Map<String, String> withoutActions = new LinkedHashMap<>(ORIGINALS);
        withoutActions.remove("fx.DocActions");
        SourceFiles.write(sources(), withoutActions);
        StateGraphCodegen.generate(request());
        Path link = SymbolicLinks.create(output().resolve("fx/DocActionsRefs.java"),
                output().resolve("fx/DocsGraph.java"));
        SourceFiles.write(sources(), ORIGINALS);

        CodegenException failure = assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));

        assertEquals("SG603", failure.code());
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.isRegularFile(output().resolve("fx/DocsGraph.java")));
    }

    /** javac accepts a top-level type in a file of any name, so a generated type collides with it all the same. */
    @Test
    void refusesAGeneratedTypeAnOriginalSourceDeclaresInAFileOfAnotherName() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        SourceFiles.write(sources(), Map.of("fx.Helpers", """
            package fx;
            public class Helpers {}
            final class DocRefs {}
            """));

        CodegenException failure = assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("fx.DocRefs"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Helpers.java"), failure.getMessage());
        assertFalse(Files.exists(directory.resolve("work").resolve("annotation-manifest.json")));
    }

    /** A Unicode escape spells the same name to javac, so it hides no collision either. */
    @Test
    void refusesAGeneratedTypeAnOriginalSourceSpellsWithAUnicodeEscape() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        SourceFiles.write(sources(), Map.of("fx.Helpers", """
            package fx;
            public class Helpers {}
            final class \\u0044ocRefs {}
            """));

        CodegenException failure = assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("fx.DocRefs"), failure.getMessage());
    }

    /** Only a top-level declaration collides: a member type that shares the simple name is another type. */
    @Test
    void acceptsAMemberTypeThatSharesTheSimpleNameOfAGeneratedType() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        SourceFiles.write(sources(), Map.of("fx.Helpers", """
            package fx;
            public class Helpers {
                static final class DocRefs {}
            }
            """));

        StateGraphCodegen.generate(request());

        assertTrue(Files.isRegularFile(output().resolve("fx/DocRefs.java")));
    }

    /**
     * A caller may put this compilation's own class output on its classpath as the previous run left it. The
     * index published there describes this compilation, never a dependency of it, whatever version wrote it.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ignoresTheIndexItsOwnClassOutputPublishedInAPreviousRun(boolean writtenByThisVersion) throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        GenerationRequest request = withClasspathEntry(request().classesOutput());
        Path published = Files.createDirectories(request.classesOutput().resolve("META-INF/graphmaquina"))
                .resolve("exports.json");
        ManifestCodec.writeExports(published, new ExportIndex(2,
                writtenByThisVersion ? ToolVersion.current() : "0.0.1-OTHER", request.compilationId(),
                new GenerationModel(List.of(), List.of()), List.of("fx.DocsGraph.Definition")));

        GenerationResult result = StateGraphCodegen.generate(request);

        assertEquals(List.of(), ManifestCodec.readCompile(result.annotationManifest()).dependencies());
    }

    /** javac reads past a classpath file that is not an archive, and so does the check for shadowed classes. */
    @Test
    void ignoresAClasspathEntryThatIsNeitherADirectoryNorAnArchive() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        Path settings = Files.writeString(Files.createDirectories(directory.resolve("libs"))
                .resolve("settings.properties"), "mode=strict\n", StandardCharsets.UTF_8);

        GenerationResult result = StateGraphCodegen.generate(withClasspathEntry(settings));

        assertTrue(Files.isRegularFile(result.annotationManifest()));
    }

    /** The request of {@link #request()} with {@code entry} appended to its classpath. */
    private GenerationRequest withClasspathEntry(Path entry) {
        GenerationRequest original = request();
        List<Path> classpath = new ArrayList<>(original.classpath());
        classpath.add(entry);
        return new GenerationRequest(original.projectDirectory(), original.sources(), classpath,
                original.sourcesOutput(), original.processorSourcesOutput(), original.classesOutput(),
                original.workDirectory(), original.compilationId());
    }

    private GenerationRequest request() {
        return new GenerationRequest(directory, List.of(sources()), SourceFiles.classpath(),
                directory.resolve("generated"), directory.resolve("processor-generated"),
                directory.resolve("classes"), directory.resolve("work"), "io.github.graphmaquina:test:main");
    }

    private Path sources() {
        return directory.resolve("sources");
    }

    /** The originals of {@link #ORIGINALS} with their machine declared as {@code fx.<facade>} instead. */
    private static Map<String, String> withMachine(String facade) {
        Map<String, String> sources = new LinkedHashMap<>(ORIGINALS);
        sources.remove("fx.Docs");
        sources.put("fx." + facade, ORIGINALS.get("fx.Docs").replace("interface Docs ", "interface " + facade + " "));
        return sources;
    }

    /** The vocabularies in the generated {@code fx} directory, under the names the volume itself reports. */
    private List<String> vocabularies() throws IOException {
        try (Stream<Path> files = Files.list(output().resolve("fx"))) {
            return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith("Graph.java"))
                    .sorted().toList();
        }
    }

    /** Whether {@code directory} finds a file under a name that differs from the one it was written with in case. */
    private static boolean caseInsensitive(Path directory) throws IOException {
        Path probe = Files.writeString(directory.resolve("case-probe"), "", StandardCharsets.UTF_8);
        try {
            return Files.exists(directory.resolve("CASE-PROBE"));
        } finally {
            Files.delete(probe);
        }
    }

    private Path output() {
        return directory.resolve("generated").toAbsolutePath();
    }

    /** Every published file, relative to the generated source root, with forward slashes. */
    private List<String> published() throws IOException {
        try (Stream<Path> walk = Files.walk(output())) {
            return walk.filter(Files::isRegularFile)
                    .map(file -> output().relativize(file).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    /** Every published file with its text, so two runs can be compared as a whole. */
    private Map<String, String> contents() throws IOException {
        Map<String, String> contents = new TreeMap<>();
        for (String name : published()) {
            try {
                contents.put(name, Files.readString(output().resolve(name), StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        contents.put("annotation-manifest.json", Files.readString(
                directory.resolve("work").resolve("annotation-manifest.json"), StandardCharsets.UTF_8));
        return contents;
    }
}
