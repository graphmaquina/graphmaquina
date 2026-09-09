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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestCodecTest {

    @TempDir
    Path directory;

    private static SourceLocation origin() {
        return new SourceLocation("fx/Orders.java", 10, 5);
    }

    @Test
    void aPublishedIndexRoundTripsWithoutExecutionPaths() throws IOException {
        ExportIndex index = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:empty:main",
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
        ExportIndex index = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:empty:main",
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
              "schemaVersion": 1,
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
              "schemaVersion": 1,
              "schemaVersion": 1,
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
        ExportIndex indexWithout = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of(withoutProperty)), List.of());
        Path fileWithout = directory.resolve("without-property.json");
        ManifestCodec.writeExports(fileWithout, indexWithout);
        assertFalse(Files.readString(fileWithout).contains("\"property\""));
        assertEquals(indexWithout, ManifestCodec.readExports(fileWithout));

        SymbolDescriptor withProperty = new SymbolDescriptor("fx.PaymentActions.Amount",
            "PaymentActions", "amount", Set.of(SymbolKind.PROPERTY), List.of(),
            Optional.of(new PropertyDescriptor("fx.PaymentActions", "amount", "getAmount", true, origin())),
            origin());
        ExportIndex indexWith = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of(withProperty)), List.of());
        Path fileWith = directory.resolve("with-property.json");
        ManifestCodec.writeExports(fileWith, indexWith);
        assertTrue(Files.readString(fileWith).contains("\"property\""));
        assertEquals(indexWith, ManifestCodec.readExports(fileWith));
    }

    @Test
    void writingSortsIdentityListsRegardlessOfInputOrder() throws IOException {
        GraphDescriptor ordersGraph = new GraphDescriptor("fx.Orders", "fx", "fx.Order", "fx.Status",
            List.of("NEW", "DONE"), List.of(), origin());
        GraphDescriptor billingGraph = new GraphDescriptor("fx.Billing", "fx", "fx.Invoice", "fx.Status",
            List.of("NEW", "DONE"), List.of(), origin());

        ExportIndex ordersFirst = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(ordersGraph, billingGraph), List.of()), List.of("b", "a"));
        ExportIndex billingFirst = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(billingGraph, ordersGraph), List.of()), List.of("a", "b"));

        Path first = directory.resolve("orders-first.json");
        Path second = directory.resolve("billing-first.json");
        ManifestCodec.writeExports(first, ordersFirst);
        ManifestCodec.writeExports(second, billingFirst);
        assertEquals(Files.readString(first), Files.readString(second));
    }

    @Test
    void compileManifestRoundTripsWithDependenciesAndOwnedSources() throws IOException {
        ExportIndex local = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of()), List.of("io.github.graphmaquina.symbol.Authorize"));
        ExportIndex dependency = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:peer:main",
            new GenerationModel(List.of(), List.of()), List.of());
        CompileManifest manifest = new CompileManifest(1, "0.3.0-SNAPSHOT", local,
            List.of(dependency), "META-INF/graphmaquina/pending-exports.json",
            List.of("fx/Authorize.java"));

        Path file = directory.resolve("compile.json");
        ManifestCodec.writeCompile(file, manifest);
        assertEquals(manifest, ManifestCodec.readCompile(file));
    }

    @Test
    void rejectsOwnedSourcePathEscapingRootOnWrite() {
        ExportIndex local = new ExportIndex(1, "0.3.0-SNAPSHOT", "fx:demo:main",
            new GenerationModel(List.of(), List.of()), List.of());
        CompileManifest manifest = new CompileManifest(1, "0.3.0-SNAPSHOT", local, List.of(),
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
}
