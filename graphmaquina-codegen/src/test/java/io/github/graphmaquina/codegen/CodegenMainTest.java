package io.github.graphmaquina.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line a build plugin drives, exercised the way a plugin drives it: a separate JVM, one operation
 * and one request file per invocation, an exit status the build can act on and directories that changed.
 *
 * <p>Nothing is caught and continued: an operation that cannot run leaves a non-zero status and a message on
 * the error stream naming the operation, the request file and the cause, so a build never carries on with the
 * generation of a previous run.</p>
 */
class CodegenMainTest {

    private static final String MAIN = "io.github.graphmaquina.codegen.CodegenMain";
    private static final String PUBLISHED = "META-INF/graphmaquina/exports.json";
    private static final String PENDING = "META-INF/graphmaquina/pending-exports.json";
    private static final String CARRIER_PACKAGE = "io/github/graphmaquina/generated/bootstrap";

    @TempDir
    Path directory;

    /** One invocation of the tool: what it wrote, error stream and standard output merged, and the status it left. */
    private record Invocation(int exitCode, String errors) { }

    @Test
    void generatesPreparesAndCompletesOneCompilation() throws IOException {
        Path request = request();

        assertEquals(0, run("generate", request).exitCode());
        assertTrue(Files.isRegularFile(directory.resolve("work/annotation-manifest.json")));
        assertTrue(Files.isRegularFile(directory.resolve("bootstrap/fx/DocsGraph.java")));

        Files.createDirectories(directory.resolve("classes").resolve(PUBLISHED).getParent());
        Files.writeString(directory.resolve("classes").resolve(PUBLISHED), "stale", StandardCharsets.UTF_8);
        assertEquals(0, run("prepare-compile", request).exitCode());
        assertFalse(Files.exists(directory.resolve("classes").resolve(PUBLISHED)));
        assertTrue(Files.isRegularFile(directory.resolve("work/owned-outputs.pending.json")));

        compile();
        byte[] index = writeProvisionalIndex();
        writeProcessorInventory();

        assertEquals(0, run("complete-compile", request).exitCode());
        assertArrayEquals(index, Files.readAllBytes(directory.resolve("classes").resolve(PUBLISHED)));
        assertFalse(Files.exists(directory.resolve("classes").resolve(PENDING)));
        assertFalse(Files.exists(directory.resolve("classes").resolve(CARRIER_PACKAGE)));
        assertTrue(Files.isRegularFile(directory.resolve("work/owned-outputs.json")));
        assertFalse(Files.exists(directory.resolve("work/owned-outputs.pending.json")));
    }

    @Test
    void refusesAnUnknownOperationAndArgumentsItDoesNotAccept() throws IOException {
        Path request = request();

        Invocation unknown = run("compile", request);
        assertEquals(2, unknown.exitCode(), unknown.errors());
        assertTrue(unknown.errors().contains("compile"), unknown.errors());

        Invocation extra = launch(List.of("generate", "--request", request.toString(), "--force"));
        assertEquals(2, extra.exitCode(), extra.errors());

        Invocation missing = launch(List.of("generate"));
        assertEquals(2, missing.exitCode(), missing.errors());

        Invocation misspelled = launch(List.of("generate", "-request", request.toString()));
        assertEquals(2, misspelled.exitCode(), misspelled.errors());
        assertTrue(misspelled.errors().contains("--request"), misspelled.errors());
    }

    @Test
    void reportsARequestItCannotRead() throws IOException {
        Path corrupt = directory.resolve("corrupt-request.json");
        Files.writeString(corrupt, "{\"projectDirectory\": \"" + directory + "\"}", StandardCharsets.UTF_8);

        Invocation invocation = launch(List.of("prepare-compile", "--request", corrupt.toString()));

        assertEquals(1, invocation.exitCode(), invocation.errors());
        assertTrue(invocation.errors().contains("prepare-compile"), invocation.errors());
        assertTrue(invocation.errors().contains(corrupt.toString()), invocation.errors());
        assertTrue(invocation.errors().contains("SG602"), invocation.errors());
    }

    @Test
    void reportsACompilationItCannotPrepare() throws IOException {
        Path request = request();

        Invocation invocation = run("prepare-compile", request);

        assertEquals(1, invocation.exitCode(), invocation.errors());
        assertTrue(invocation.errors().contains("prepare-compile"), invocation.errors());
        assertTrue(invocation.errors().contains(request.toString()), invocation.errors());
        assertTrue(invocation.errors().contains("SG602"), invocation.errors());
    }

    /**
     * A wrapped failure says what the tool was doing in its own message and what went wrong in its cause, so
     * the line the build prints carries the whole chain and not only the outermost sentence.
     */
    @Test
    void describesEveryCauseOfAWrappedFailure() {
        String described = CodegenMain.describe(new UncheckedIOException("cannot read the original sources",
                new IOException("classes/fx is a directory",
                        new CodegenException("SG603", "the inventory claims a file it does not own"))));

        assertEquals("java.io.UncheckedIOException: cannot read the original sources"
                + ": java.io.IOException: classes/fx is a directory"
                + ": [SG603] the inventory claims a file it does not own", described);
    }

    /** A diagnostic anchored to an original source carries that anchor onto the line the build prints. */
    @Test
    void describesTheSourceLocationADiagnosticIsAnchoredTo() {
        String described = CodegenMain.describe(new CodegenException("SG601",
                "the type Status is ambiguous between a.Status and b.Status",
                new SourceLocation("src/fx/Docs.java", 8, 1)));

        assertEquals("[SG601] the type Status is ambiguous between a.Status and b.Status (at src/fx/Docs.java:8:1)",
                described);
    }

    /**
     * An {@link Error} the JVM raises while an operation runs, here because the runtime carries no
     * {@code jdk.compiler}, is reported like any other failure: one line naming the operation, the request and
     * the cause, and status 1, never a bare stack trace.
     */
    @Test
    void reportsAnErrorTheJvmRaisesLikeAnyOtherFailure() throws IOException {
        Path request = request();

        Invocation invocation = launch(List.of("--limit-modules", "java.se"), List.of("generate", "--request",
                request.toString()));

        assertEquals(1, invocation.exitCode(), invocation.errors());
        assertTrue(invocation.errors().startsWith("graphmaquina: generate failed for " + request
                + ": java.lang.NoClassDefFoundError: com/sun/source/"), invocation.errors());
        assertEquals(1, invocation.errors().strip().lines().count(), invocation.errors());
    }

    @Test
    void describesACyclicCauseChainWithoutRepeatingIt() throws IOException, InterruptedException {
        Path output = directory.resolve("cyclic-cause.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", System.getProperty("java.class.path"), CyclicCause.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "describing a cyclic cause must terminate");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertEquals("java.lang.RuntimeException: outer: java.lang.RuntimeException: inner",
                    Files.readString(output).strip());
        } finally {
            process.destroyForcibly();
        }
    }

    /** Runs an intentionally cyclic exception in a bounded JVM so a regression cannot exhaust the test JVM. */
    public static final class CyclicCause {
        public static void main(String[] args) {
            RuntimeException outer = new RuntimeException("outer");
            RuntimeException inner = new RuntimeException("inner", outer);
            outer.initCause(inner);
            System.out.println(CodegenMain.describe(outer));
        }
    }

    /** Writes the fixture and the request file the tool reads, and returns the path of that file. */
    private Path request() throws IOException {
        SourceFiles.write(directory.resolve("originals"), ORIGINALS);
        GenerationRequest request = new GenerationRequest(directory,
                List.of(directory.resolve("originals")), SourceFiles.classpath(),
                directory.resolve("bootstrap"), directory.resolve("processor"), directory.resolve("classes"),
                directory.resolve("work"), "fx:test:main");
        Path file = directory.resolve("request.json");
        ManifestCodec.writeRequest(file, request);
        return file;
    }

    /**
     * Runs {@code operation} over {@code request} in a new JVM, of the same runtime and with the same class path as
     * the one the test itself runs on.
     */
    private Invocation run(String operation, Path request) {
        return launch(List.of(operation, "--request", request.toString()));
    }

    private Invocation launch(List<String> arguments) {
        return launch(List.of(), arguments);
    }

    /** Runs the tool with {@code jvmOptions} given to the JVM that runs it, ahead of its own arguments. */
    private Invocation launch(List<String> jvmOptions, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), MAIN));
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Invocation(process.waitFor(), output);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    /** Compiles the originals with the generated bootstrap, which is what javac does between the two steps. */
    private void compile() {
        List<Path> files = new ArrayList<>(javaFiles(directory.resolve("originals")));
        files.addAll(javaFiles(directory.resolve("bootstrap")));
        SourceFiles.Compilation compilation =
                SourceFiles.compileStrict(files, directory.resolve("classes"));
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /**
     * Writes, in place of the processor, the provisional index it leaves behind for the completion step to promote,
     * and returns its bytes.
     */
    private byte[] writeProvisionalIndex() throws IOException {
        Path pending = directory.resolve("classes").resolve(PENDING);
        Files.createDirectories(pending.getParent());
        ManifestCodec.writeExports(pending, new ExportIndex(2, ToolVersion.current(), "fx:test:main",
                new GenerationModel(List.of(), List.of()), List.of()));
        return Files.readAllBytes(pending);
    }

    /** The processor's own inventory, empty here because this compilation ran no annotation processor. */
    private void writeProcessorInventory() throws IOException {
        CompileManifest manifest =
                ManifestCodec.readCompile(directory.resolve("work/annotation-manifest.json"));
        Files.writeString(Path.of(manifest.processorInventory()),
                "{\n  \"schemaVersion\": 2,\n  \"generatedTypes\": []\n}\n", StandardCharsets.UTF_8);
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The sources {@link #request()} writes and names: an entity with one property, its two enums, the machine over
     * them and an action host.
     */
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
                    public DocStatus status() { return status; }
                    public void setStatus(DocStatus next) { this.status = next; }
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
}
