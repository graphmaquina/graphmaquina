package io.github.graphmaquina.codegen.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the manifest codec: what schema 2 writes and reads back, and how a document of the retired semantic
 * schema 1 is refused.
 */
class ManifestCodecTest {

    @TempDir
    Path directory;

    private static final String SCHEMA_ONE_INDEX = """
        {
          "schemaVersion": 1,
          "toolVersion": "1.0.0",
          "compilationId": "fx:demo:main",
          "model": {
            "graphs": [
              {
                "facade": "fx.Orders", "packageName": "fx", "entityType": "fx.Order",
                "stateType": "fx.Status", "states": ["NEW", "DONE"], "peers": [],
                "origin": {"path": "fx/Orders.java", "line": 10, "column": 5}
              }
            ],
            "symbols": []
          },
          "annotationTypes": ["fx.OrdersGraph.Row"]
        }
        """;

    private static SourceLocation origin() {
        return new SourceLocation("fx/Orders.java", 10, 5);
    }

    @Test
    void aPublishedIndexRoundTripsWithoutExecutionPaths() throws IOException {
        ExportIndex index = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:empty:main",
            new GenerationModel(List.of(), List.of()), List.of());
        Path first = directory.resolve("first.json");
        Path second = directory.resolve("second.json");
        ManifestCodec.writeExports(first, index);
        ManifestCodec.writeExports(second, ManifestCodec.readExports(first));
        assertEquals(Files.readString(first), Files.readString(second));
        assertFalse(Files.readString(first).contains(directory.toString()));
    }

    @Test
    void writtenManifestsUseLfLineEndings() throws IOException {
        ExportIndex index = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:empty:main",
            new GenerationModel(List.of(), List.of()), List.of());
        Path file = directory.resolve("index.json");
        ManifestCodec.writeExports(file, index);
        assertFalse(Files.readString(file).contains("\r"));
    }

    @Test
    void rejectsUnknownSchemaVersion() throws IOException {
        Path file = directory.resolve("future.json");
        Files.writeString(file, """
            {
              "schemaVersion": 99,
              "toolVersion": "0.3.0-SNAPSHOT",
              "compilationId": "fx:empty:main",
              "model": { "graphs": [], "symbols": [] },
              "annotationTypes": []
            }
            """);
        assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));
    }

    /**
     * A number member is read exactly: a fraction or a value beyond the member's range is refused, never
     * truncated into a schema this tool reads with the wrong rules or called a schema it is not.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2.9", "4294967298", "1.5"})
    void rejectsASchemaVersionThatIsNotAnExactInteger(String schemaVersion) throws IOException {
        Path file = directory.resolve("inexact.json");
        Files.writeString(file, """
            {
              "schemaVersion": %s,
              "toolVersion": "0.3.0-SNAPSHOT",
              "compilationId": "fx:empty:main",
              "model": { "graphs": [], "symbols": [] },
              "annotationTypes": []
            }
            """.formatted(schemaVersion));

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));

        assertEquals("SG602", failure.code());
        assertEquals("member 'schemaVersion' must be a 32-bit integer in " + file + ", but is " + schemaVersion,
            failure.getMessage());
    }

    @Test
    void rejectsAnOriginPositionThatIsNotAnExactInteger() throws IOException {
        GraphDescriptor graph = new GraphDescriptor("fx.Orders", "fx", "fx.Order", "fx.Status",
            List.of("NEW", "DONE"), "fx.OrderTransition", List.of("SUBMIT"), List.of(), origin());
        Path file = directory.resolve("origin.json");
        ManifestCodec.writeExports(file, new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(graph), List.of()), List.of()));
        Files.writeString(file, Files.readString(file).replace("\"line\": 10", "\"line\": 10.5"));

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));

        assertEquals("SG602", failure.code());
        assertEquals("member 'line' must be a 64-bit integer in " + file + ", but is 10.5", failure.getMessage());
    }

    /** The branch that names content after the document is the one a reader sees, not the parser's advice. */
    @Test
    void reportsContentAfterTheDocumentAsTrailingContent() throws IOException {
        Path file = directory.resolve("request.json");
        ManifestCodec.writeRequest(file, new GenerationRequest(directory.resolve("project"),
            List.of(directory.resolve("project/src")), List.of(), directory.resolve("generated"),
            directory.resolve("processor"), directory.resolve("classes"), directory.resolve("work"),
            "fx:demo:main"));
        Files.writeString(file, Files.readString(file) + " {\"extra\": true}");

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readRequest(file));

        assertEquals("SG602", failure.code());
        assertEquals("unexpected trailing content in " + file, failure.getMessage());
    }

    /** What an interrupted write leaves behind is reported against the file, so a user knows what to delete. */
    @Test
    void namesTheFileOfADocumentThatEndsEarly() throws IOException {
        Path file = directory.resolve("owned-outputs.pending.json");
        Files.writeString(file, "{\n  \"schemaVersion\": 2,\n  \"sources\": [\n    \"fx/DocsGraph.java\",\n");

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readOwned(file));

        assertEquals("SG602", failure.code());
        assertEquals("malformed JSON in " + file + ": End of input at line 5 column 1 path $.sources[1]",
            failure.getMessage());
    }

    /** A write cut off inside a multi-byte character leaves bytes that are not UTF-8, reported against the file. */
    @Test
    void namesTheFileOfADocumentCutOffInsideAMultiByteCharacter() throws IOException {
        Path file = directory.resolve("owned-outputs.pending.json");
        byte[] document = "{\n  \"schemaVersion\": 2,\n  \"sources\": [\n    \"fx/Café"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(file, Arrays.copyOf(document, document.length - 1));

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readOwned(file));

        assertEquals("SG602", failure.code());
        assertEquals("malformed JSON in " + file + ": bytes that are not UTF-8 text", failure.getMessage());
    }

    /**
     * A strict reader describes a comment as something a lenient mode would accept; nobody reading these files
     * can choose that mode, so the description keeps the position and drops the advice and its link.
     */
    @Test
    void namesTheFileOfADocumentThatIsNotStrictJsonWithoutAdviceItsReaderCannotFollow() throws IOException {
        Path file = directory.resolve("commented.json");
        Files.writeString(file, "{\"schemaVersion\": 2, // the current schema\n \"generatedTypes\": []}\n");

        CodegenException failure =
            assertThrows(CodegenException.class, () -> ManifestCodec.readProcessorInventory(file));

        assertEquals("SG602", failure.code());
        assertEquals("malformed JSON in " + file
            + ": content that strict JSON does not allow at line 1 column 23 path $.schemaVersion",
            failure.getMessage());
    }

    @Test
    void rejectsMissingMandatoryMember() throws IOException {
        Path file = directory.resolve("incomplete.json");
        Files.writeString(file, """
            {
              "schemaVersion": 2,
              "compilationId": "fx:empty:main",
              "model": { "graphs": [], "symbols": [] },
              "annotationTypes": []
            }
            """);
        assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));
    }

    @Test
    void rejectsDuplicateKeys() throws IOException {
        Path file = directory.resolve("duplicate.json");
        Files.writeString(file, """
            {
              "schemaVersion": 2,
              "schemaVersion": 2,
              "toolVersion": "0.3.0-SNAPSHOT",
              "compilationId": "fx:empty:main",
              "model": { "graphs": [], "symbols": [] },
              "annotationTypes": []
            }
            """);
        assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));
    }

    @Test
    void symbolDescriptorPropertyIsOmittedWhenAbsentAndRoundTripsWhenPresent() throws IOException {
        SymbolDescriptor withoutProperty = new SymbolDescriptor("fx.PaymentActions.Authorize",
            "PaymentActions", "authorize", Set.of(SymbolKind.ACTION),
            List.of(new MemberDescriptor("fx.PaymentActions", "authorize", List.of(), "void",
                SymbolKind.ACTION, false, origin())),
            Optional.empty(), origin());
        ExportIndex indexWithout = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of(withoutProperty)), List.of());
        Path fileWithout = directory.resolve("without-property.json");
        ManifestCodec.writeExports(fileWithout, indexWithout);
        assertFalse(Files.readString(fileWithout).contains("\"property\""));
        assertEquals(indexWithout, ManifestCodec.readExports(fileWithout));

        SymbolDescriptor withProperty = new SymbolDescriptor("fx.PaymentActions.Amount",
            "PaymentActions", "amount", Set.of(SymbolKind.PROPERTY), List.of(),
            Optional.of(new PropertyDescriptor("fx.PaymentActions", "amount", "getAmount", true, origin())),
            origin());
        ExportIndex indexWith = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of(withProperty)), List.of());
        Path fileWith = directory.resolve("with-property.json");
        ManifestCodec.writeExports(fileWith, indexWith);
        assertTrue(Files.readString(fileWith).contains("\"property\""));
        assertEquals(indexWith, ManifestCodec.readExports(fileWith));
    }

    @Test
    void writingSortsIdentityListsRegardlessOfInputOrder() throws IOException {
        GraphDescriptor ordersGraph = new GraphDescriptor("fx.Orders", "fx", "fx.Order", "fx.Status",
            List.of("NEW", "DONE"), "fx.OrderTransition", List.of("SUBMIT", "SHIP"), List.of(), origin());
        GraphDescriptor billingGraph = new GraphDescriptor("fx.Billing", "fx", "fx.Invoice", "fx.Status",
            List.of("NEW", "DONE"), "fx.BillingTransition", List.of("ISSUE", "SETTLE"), List.of(), origin());

        ExportIndex ordersFirst = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(ordersGraph, billingGraph), List.of()), List.of("b", "a"));
        ExportIndex billingFirst = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(billingGraph, ordersGraph), List.of()), List.of("a", "b"));

        Path first = directory.resolve("orders-first.json");
        Path second = directory.resolve("billing-first.json");
        ManifestCodec.writeExports(first, ordersFirst);
        ManifestCodec.writeExports(second, billingFirst);
        assertEquals(Files.readString(first), Files.readString(second));
    }

    @Test
    void compileManifestRoundTripsWithDependenciesAndOwnedSources() throws IOException {
        ExportIndex local = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of()), List.of("io.github.graphmaquina.symbol.Authorize"));
        ExportIndex dependency = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:peer:main",
            new GenerationModel(List.of(), List.of()), List.of());
        CompileManifest manifest = new CompileManifest(2, "0.3.0-SNAPSHOT", local,
            List.of(dependency), "META-INF/graphmaquina/pending-exports.json",
            List.of("fx/Authorize.java"));

        Path file = directory.resolve("compile.json");
        ManifestCodec.writeCompile(file, manifest);
        assertEquals(manifest, ManifestCodec.readCompile(file));
    }

    @Test
    void rejectsOwnedSourcePathEscapingRootOnWrite() {
        ExportIndex local = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of()), List.of());
        CompileManifest manifest = new CompileManifest(2, "0.3.0-SNAPSHOT", local, List.of(),
            "META-INF/graphmaquina/pending-exports.json", List.of("../Escaped.java"));
        Path file = directory.resolve("escaping.json");
        assertThrows(CodegenException.class, () -> ManifestCodec.writeCompile(file, manifest));
    }

    @Test
    void generationRequestRoundTripsAsFlatJson() throws IOException {
        GenerationRequest request = new GenerationRequest(
            directory.resolve("project"),
            List.of(directory.resolve("project/src/main/java")),
            List.of(directory.resolve("deps/one.jar"), directory.resolve("deps/two.jar")),
            directory.resolve("build/generated-sources/graphmaquina"),
            directory.resolve("build/generated-sources/graphmaquina-processor"),
            directory.resolve("build/classes/java/main"),
            directory.resolve("build/graphmaquina"),
            "io.github.graphmaquina:example:main");

        Path file = directory.resolve("request.json");
        ManifestCodec.writeRequest(file, request);
        String json = Files.readString(file);
        assertEquals(1, json.chars().filter(character -> character == '{').count());
        assertEquals(request, ManifestCodec.readRequest(file));
    }

    /**
     * The release and the encoding of the compilation are optional members: a request names them when the build
     * knows them, and one that names neither, as every request did before they existed, is read at release 21 in
     * UTF-8. Both are written back as they were read.
     */
    @Test
    void generationRequestReadsTheReleaseAndTheEncodingItNamesAndDefaultsTheOthers() throws IOException {
        Path older = Files.writeString(directory.resolve("older.json"), requestJson(""), StandardCharsets.UTF_8);
        Path newer = Files.writeString(directory.resolve("newer.json"),
                requestJson(",\n  \"release\": 25,\n  \"encoding\": \"ISO-8859-1\""), StandardCharsets.UTF_8);

        GenerationRequest defaults = ManifestCodec.readRequest(older);
        GenerationRequest named = ManifestCodec.readRequest(newer);

        assertEquals(21, defaults.release());
        assertEquals(StandardCharsets.UTF_8, defaults.encoding());
        assertEquals(25, named.release());
        assertEquals(StandardCharsets.ISO_8859_1, named.encoding());
        Path written = directory.resolve("written.json");
        ManifestCodec.writeRequest(written, named);
        assertEquals(named, ManifestCodec.readRequest(written));
    }

    /** A release or an encoding the generator cannot use is refused as a malformed request, naming the member. */
    @Test
    void generationRequestRejectsAReleaseOrAnEncodingItCannotUse() throws IOException {
        Map<String, String> members = new LinkedHashMap<>();
        members.put("\"release\": \"25\"", "release");
        members.put("\"release\": 21.5", "release");
        members.put("\"encoding\": 8", "encoding");
        members.put("\"encoding\": \"no-such-encoding\"", "encoding");
        for (Map.Entry<String, String> member : members.entrySet()) {
            Path file = Files.writeString(directory.resolve("request.json"),
                    requestJson(",\n  " + member.getKey()), StandardCharsets.UTF_8);

            CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readRequest(file));

            assertEquals("SG602", failure.code(), member.getKey());
            assertTrue(failure.getMessage().contains("'" + member.getValue() + "'"), failure.getMessage());
            assertTrue(failure.getMessage().contains(file.toString()), failure.getMessage());
        }
    }

    /** A request with the eight members every request carries, followed by {@code extra}, as JSON text. */
    private String requestJson(String extra) {
        return """
            {
              "projectDirectory": "%1$s",
              "sources": ["%1$s/src"],
              "classpath": [],
              "sourcesOutput": "%1$s/generated",
              "processorSourcesOutput": "%1$s/processor",
              "classesOutput": "%1$s/classes",
              "workDirectory": "%1$s/work",
              "compilationId": "fx:demo:main"%2$s
            }
            """.formatted(directory.toString().replace("\\", "\\\\"), extra);
    }

    @Test
    void generationRequestRejectsBlankCompilationId() {
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(
            directory.resolve("project"),
            List.of(directory.resolve("project/src/main/java")),
            List.of(),
            directory.resolve("build/generated-sources/graphmaquina"),
            directory.resolve("build/generated-sources/graphmaquina-processor"),
            directory.resolve("build/classes/java/main"),
            directory.resolve("build/graphmaquina"),
            "  "));
    }

    @Test
    void generationRequestRejectsOverlappingSourceAndOutputDirectories() {
        Path sourceDirectory = directory.resolve("project/src/main/java");
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(
            directory.resolve("project"),
            List.of(sourceDirectory),
            List.of(),
            sourceDirectory,
            directory.resolve("build/generated-sources/graphmaquina-processor"),
            directory.resolve("build/classes/java/main"),
            directory.resolve("build/graphmaquina"),
            "io.github.graphmaquina:example:main"));
    }

    /**
     * A relative path names the directory it resolves to from the working directory, so the overlap between a
     * source directory and an output directory is the same whether either of them is spelled relatively.
     */
    @Test
    void generationRequestRejectsAnOverlapSpelledOnceRelativelyAndOnceAbsolutely() {
        Path relative = Path.of("build", "overlap", "src", "main", "java");
        Path absolute = relative.toAbsolutePath();

        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(
            directory.resolve("project"), List.of(relative), List.of(), absolute,
            directory.resolve("build/generated-sources/graphmaquina-processor"),
            directory.resolve("build/classes/java/main"), directory.resolve("build/graphmaquina"),
            "io.github.graphmaquina:example:main"));
        assertThrows(IllegalArgumentException.class, () -> new GenerationRequest(
            directory.resolve("project"), List.of(absolute), List.of(), relative.resolve("generated"),
            directory.resolve("build/generated-sources/graphmaquina-processor"),
            directory.resolve("build/classes/java/main"), directory.resolve("build/graphmaquina"),
            "io.github.graphmaquina:example:main"));
    }

    @Test
    void aGraphCarriesItsTransitionEnumAndIdentifiersRightAfterItsStates() throws IOException {
        GraphDescriptor graph = new GraphDescriptor("fx.Orders", "fx", "fx.Order", "fx.Status",
            List.of("NEW", "DONE"), "fx.OrderTransition", List.of("SUBMIT", "SHIP"), List.of(), origin());
        ExportIndex index = new ExportIndex(2, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(graph), List.of()), List.of());
        Path file = directory.resolve("graph.json");
        ManifestCodec.writeExports(file, index);

        String json = Files.readString(file);
        assertEquals(List.of("facade", "packageName", "entityType", "stateType", "states", "transitionType",
                "transitions", "peers", "origin"), members(json));
        assertEquals(index, ManifestCodec.readExports(file));
    }

    @Test
    void rejectsAGraphWithoutItsTransitionEnumOrItsIdentifiers() throws IOException {
        for (String missing : List.of("transitionType", "transitions")) {
            Path file = directory.resolve("without-" + missing + ".json");
            Files.writeString(file, """
                {
                  "schemaVersion": 2,
                  "toolVersion": "0.3.0-SNAPSHOT",
                  "compilationId": "fx:demo:main",
                  "model": {
                    "graphs": [
                      {
                        "facade": "fx.Orders", "packageName": "fx", "entityType": "fx.Order",
                        "stateType": "fx.Status", "states": ["NEW", "DONE"],
                        "transitionType": "fx.OrderTransition", "transitions": ["SUBMIT"], "peers": [],
                        "origin": {"path": "fx/Orders.java", "line": 10, "column": 5}
                      }
                    ],
                    "symbols": []
                  },
                  "annotationTypes": []
                }
                """.replace("\"" + missing + "\"", "\"absent\""));

            CodegenException failure =
                assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));
            assertEquals("SG602", failure.code());
            assertTrue(failure.getMessage().contains("missing member '" + missing + "'"),
                failure.getMessage());
        }
    }

    @Test
    void rejectsASemanticIndexOfSchemaOneAskingForTheProducerToBeRegenerated() throws IOException {
        Path file = directory.resolve("schema-one-exports.json");
        Files.writeString(file, SCHEMA_ONE_INDEX);

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("schema 1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("regenerate"), failure.getMessage());
        assertTrue(failure.getMessage().contains("recompile"), failure.getMessage());
        assertTrue(failure.getMessage().contains("producer"), failure.getMessage());
        assertTrue(failure.getMessage().contains("consumer"), failure.getMessage());
    }

    @Test
    void rejectsACompileManifestOfSchemaOneBeforeReadingAnyGraphOfIt() throws IOException {
        Path file = directory.resolve("schema-one-compile.json");
        Files.writeString(file, """
            {
              "schemaVersion": 1,
              "toolVersion": "1.0.0",
              "local": %s,
              "dependencies": [],
              "processorInventory": "META-INF/graphmaquina/pending-exports.json",
              "ownedSources": ["fx/OrdersGraph.java"]
            }
            """.formatted(SCHEMA_ONE_INDEX.strip()));

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readCompile(file));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("schema 1"), failure.getMessage());
    }

    /** The user cleans old outputs explicitly; the tool never consumes an inventory of the retired schema. */
    @Test
    void rejectsAnOwnershipInventoryOfTheRetiredSchema() throws IOException {
        Path file = directory.resolve("owned-outputs.json");
        Files.writeString(file, """
            {
              "schemaVersion": 1,
              "sources": ["fx/OrdersGraph.java"],
              "binaries": ["fx/OrdersGraph.class"],
              "resources": ["META-INF/graphmaquina/exports.json"],
              "excludedBinaries": []
            }
            """);

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readOwned(file));
        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("clean"), failure.getMessage());
    }

    /**
     * The member names of the graph objects of a written index, from its {@code graphs} key to its {@code symbols}
     * key, in the order the document spells them and without the {@code path}, {@code line} and {@code column} of an
     * origin.
     */
    private static List<String> members(String json) {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([a-zA-Z]+)\":").matcher(
            json.substring(json.indexOf("\"graphs\""), json.indexOf("\"symbols\"")));
        while (matcher.find()) {
            if (!"path".equals(matcher.group(1)) && !"line".equals(matcher.group(1))
                    && !"column".equals(matcher.group(1)) && !"graphs".equals(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }
}
