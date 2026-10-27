package io.github.graphmaquina.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.codegen.source.Bootstrap;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the generator reads the original sources as the compilation that follows reads them: at the
 * release the request names, which decides the syntax a source may use and the JDK types it can name, and in
 * the encoding the request names, which it also writes the generated sources in. A request that names neither
 * is read at release 21 in UTF-8, as before either member existed.
 *
 * <p>Every request here is written as the JSON a build plugin writes, so the tests read it the way the command
 * line reads it.</p>
 */
class ReleaseAndEncodingTest {

    @TempDir
    Path directory;

    /** A machine, its entity and its two enums; a test that needs a host of marked members adds one. */
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
                public DocStatus getStatus() { return status; }
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
        return Map.copyOf(sources);
    }

    /**
     * A source written for a release above 21 may use its syntax anywhere, a method body included, and javac
     * parses it at the release the compilation names. The generator does too; a request that names no release
     * is still read at 21, where that syntax does not parse.
     */
    @Test
    void parsesTheSyntaxOfTheReleaseTheRequestNames() throws IOException {
        int release = Runtime.version().feature();
        assumeTrue(release >= 22, "unnamed variables are Java 22 syntax; this test runs on Java " + release);
        Map<String, String> sources = new LinkedHashMap<>(ORIGINALS);
        sources.put("fx.Parsing", """
            package fx;
            final class Parsing {
                static boolean parses(String text) {
                    try {
                        Integer.parseInt(text);
                        return true;
                    } catch (NumberFormatException _) {
                        return false;
                    }
                }
            }
            """);
        write(sources, StandardCharsets.UTF_8);

        CodegenException atTheDefault = assertThrows(CodegenException.class,
                () -> StateGraphCodegen.generate(request(new JsonObject())));
        StateGraphCodegen.generate(request(members("release", release)));

        assertEquals(DiagnosticCode.SG601.name(), atTheDefault.code(), atTheDefault.getMessage());
        assertTrue(Files.isRegularFile(directory.resolve("generated/fx/DocsGraph.java")));
    }

    /**
     * The release a request names is the one its sources are parsed at, below the default as well: a pattern in a
     * switch is Java 21 syntax, so a request that names release 17 is refused there, as javac at release 17 refuses
     * it, while a request that names no release parses it. This runs on every JDK the build uses.
     */
    @Test
    void parsesAtTheReleaseTheRequestNamesBelowTheDefaultToo() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(ORIGINALS);
        sources.put("fx.Describe", """
            package fx;
            final class Describe {
                static String describe(Object value) {
                    return switch (value) {
                        case String text -> "text " + text;
                        default -> "other";
                    };
                }
            }
            """);
        write(sources, StandardCharsets.UTF_8);

        CodegenException atRelease17 = assertThrows(CodegenException.class,
                () -> StateGraphCodegen.generate(request(members("release", 17))));
        StateGraphCodegen.generate(request(new JsonObject()));

        assertEquals(DiagnosticCode.SG601.name(), atRelease17.code(), atRelease17.getMessage());
        assertTrue(atRelease17.getMessage().contains("originals/fx/Describe.java"), atRelease17.getMessage());
        assertTrue(Files.isRegularFile(directory.resolve("generated/fx/DocsGraph.java")));
    }

    /**
     * javac resolves the JDK types a source names against the release the compilation names, so a type added to
     * the JDK after Java 21 is a JDK type there, and the generator records it under its canonical name, the name
     * the processor compares it with.
     */
    @Test
    void resolvesTheJdkTypesOfTheReleaseTheRequestNames() throws IOException {
        int release = Runtime.version().feature();
        assumeTrue(release >= 24, "java.util.stream.Gatherer is Java 24 API; this test runs on Java " + release);
        Map<String, String> sources = new LinkedHashMap<>(ORIGINALS);
        sources.put("fx.Rules", """
            package fx;
            import io.github.graphmaquina.annotation.GraphGuard;
            import java.util.stream.Gatherer;
            public final class Rules {
                @GraphGuard
                public static boolean usable(Gatherer<String, ?, String> gatherer) { return true; }
            }
            """);
        write(sources, StandardCharsets.UTF_8);

        GenerationModel model = Bootstrap.inspect(request(members("release", release)));

        SymbolDescriptor usable = model.symbols().stream()
                .filter((SymbolDescriptor symbol) -> symbol.qualifiedName().equals("fx.RulesRefs.Usable"))
                .findFirst().orElseThrow();
        assertEquals(List.of(List.of("java.util.stream.Gatherer<java.lang.String, ?, java.lang.String>")),
                usable.candidates().stream().map(MemberDescriptor::parameterTypes).toList());
    }

    /**
     * A project whose sources are not UTF-8 declares their encoding, and javac reads them in it. The generator
     * reads them in it too, and writes what it generates in it, so an accented identifier reaches the generated
     * vocabulary intact and javac reads the generated sources back as it reads the originals.
     */
    @Test
    void readsAndWritesSourcesInTheEncodingTheRequestNames() throws IOException {
        Charset latin = StandardCharsets.ISO_8859_1;
        List<Path> originals = write(accented("approveRésumé", "The actions that approve a résumé."), latin);

        GenerationResult result = StateGraphCodegen.generate(request(members("encoding", "ISO-8859-1")));

        Path references = directory.resolve("generated/fx/DocActionsRefs.java");
        String text = Files.readString(references, latin);
        assertTrue(text.contains("ApproveRésumé"), text);
        SourceFiles.Compilation compilation = compile(originals, result, latin);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /**
     * The check for a generated type an original source already declares reads the very sources the generator
     * read, in the encoding of the request: a source kept in ISO-8859-1 that declares a generated type in a file of
     * another name is refused for that, instead of failing as text that is not UTF-8.
     */
    @Test
    void refusesAGeneratedTypeDeclaredByASourceReadInTheEncodingOfTheRequest() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(accented("approveRésumé", "The actions of a résumé."));
        sources.put("fx.Helpers", """
            package fx;
            /** Helpers for a résumé. */
            public class Helpers {}
            final class DocActionsRefs {}
            """);
        write(sources, StandardCharsets.ISO_8859_1);

        CodegenException failure = assertThrows(CodegenException.class,
                () -> StateGraphCodegen.generate(request(members("encoding", "ISO-8859-1"))));

        assertEquals(DiagnosticCode.SG603.name(), failure.code(), failure.getMessage());
        assertTrue(failure.getMessage().contains("fx.DocActionsRefs"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Helpers.java"), failure.getMessage());
    }

    /**
     * A character the encoding of the compilation cannot represent still reaches the generated sources: it is
     * written as the Unicode escape javac reads in any encoding, here a letter an ASCII source spells as an
     * escape itself.
     */
    @Test
    void escapesInTheGeneratedSourcesWhatTheEncodingCannotRepresent() throws IOException {
        List<Path> originals = write(accented("approveR\\u00e9sum\\u00e9",
                "The actions that approve a r\\u00e9sum\\u00e9."), StandardCharsets.US_ASCII);

        GenerationResult result = StateGraphCodegen.generate(request(members("encoding", "US-ASCII")));

        byte[] references = Files.readAllBytes(directory.resolve("generated/fx/DocActionsRefs.java"));
        String text = new String(references, StandardCharsets.US_ASCII);
        assertTrue(text.contains("ApproveR\\u00e9sum\\u00e9"), text);
        for (byte value : references) {
            assertTrue(value >= 0, "a byte outside US-ASCII in " + text);
        }
        SourceFiles.Compilation compilation = compile(originals, result, StandardCharsets.US_ASCII);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /**
     * The fixture with an action host whose action and comment are spelled {@code action} and {@code comment},
     * and a source that names the symbol the generator derives from that action.
     */
    private static Map<String, String> accented(String action, String comment) {
        Map<String, String> sources = new LinkedHashMap<>(ORIGINALS);
        sources.put("fx.DocActions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            /** %s */
            public class DocActions {
                @GraphAction
                public Doc %s(Doc doc) { return doc; }
            }
            """.formatted(comment, action));
        sources.put("fx.Uses", """
            package fx;
            final class Uses {
                static final Class<?> SYMBOL = DocActionsRefs.A%s.class;
            }
            """.formatted(action.substring(1)));
        return sources;
    }

    /** Writes {@code sources} under {@code originals}, each in {@code charset}, and returns their paths. */
    private List<Path> write(Map<String, String> sources, Charset charset) {
        List<Path> written = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = directory.resolve("originals").resolve(source.getKey().replace('.', '/') + ".java");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue(), charset);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            written.add(file);
        }
        return written;
    }

    /** Compiles the originals with what the generation wrote, as javac reads them in {@code encoding}. */
    private SourceFiles.Compilation compile(List<Path> originals, GenerationResult result, Charset encoding) {
        List<Path> files = new ArrayList<>(originals);
        files.addAll(result.generatedSources());
        return SourceFiles.compileStrict(files, directory.resolve("classes"), encoding);
    }

    /** One member, as the JSON object {@link #request(JsonObject)} adds to the request. */
    private static JsonObject members(String name, Object value) {
        JsonObject members = new JsonObject();
        if (value instanceof Number number) {
            members.addProperty(name, number);
        } else {
            members.addProperty(name, String.valueOf(value));
        }
        return members;
    }

    /**
     * Writes the request of the fixture as a build plugin writes it, with {@code extra} added to its members, and
     * reads it back the way the command line reads it.
     */
    private GenerationRequest request(JsonObject extra) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("projectDirectory", directory.toString());
        request.add("sources", strings(List.of(directory.resolve("originals").toString())));
        request.add("classpath", strings(SourceFiles.classpath().stream().map(Path::toString).toList()));
        request.addProperty("sourcesOutput", directory.resolve("generated").toString());
        request.addProperty("processorSourcesOutput", directory.resolve("processor").toString());
        request.addProperty("classesOutput", directory.resolve("classes").toString());
        request.addProperty("workDirectory", directory.resolve("work").toString());
        request.addProperty("compilationId", "fx:docs:main");
        for (String name : extra.keySet()) {
            request.add(name, extra.get(name));
        }
        Path file = directory.resolve("request.json");
        Files.writeString(file, new Gson().toJson(request), StandardCharsets.UTF_8);
        return ManifestCodec.readRequest(file);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
