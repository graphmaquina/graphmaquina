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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /** The member names of the first graph object of a written index, in the order the document spells them. */
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
