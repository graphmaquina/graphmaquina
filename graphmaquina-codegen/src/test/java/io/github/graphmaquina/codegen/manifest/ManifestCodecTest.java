package io.github.graphmaquina.codegen.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the manifest codec: what schema 2 writes and reads back, and how a document of another schema is refused.
 */
class ManifestCodecTest {

    @TempDir
    Path directory;

    private static SourceLocation origin() {
        return new SourceLocation("fx/Orders.java", 10, 5);
    }

    @Test
    void aPublishedIndexRoundTripsWithoutExecutionPaths() throws IOException {
        ExportIndex index = new ExportIndex(2, "2.0.0-test", "fx:empty:main",
            new GenerationModel(List.of(), List.of()), List.of());
        Path first = directory.resolve("first.json");
        Path second = directory.resolve("second.json");
        ManifestCodec.writeExports(first, index);
        ManifestCodec.writeExports(second, ManifestCodec.readExports(first));
        assertEquals(Files.readString(first), Files.readString(second));
        assertFalse(Files.readString(first).contains(directory.toString()));
    }

    /**
     * Every writer breaks lines with LF alone: the request, an index, a manifest, an inventory and the processor
     * inventory. Only the processor inventory ends with a line break.
     */
    @Test
    void everyWriterBreaksLinesWithLfAndOnlyTheProcessorInventoryEndsWithOne() throws IOException {
        Path request = directory.resolve("request.json");
        ManifestCodec.writeRequest(request, new GenerationRequest(directory.resolve("project"),
            List.of(directory.resolve("project/src")), List.of(), directory.resolve("generated"),
            directory.resolve("processor"), directory.resolve("classes"), directory.resolve("work"), "fx:demo:main"));
        Path exports = directory.resolve("exports.json");
        ManifestCodec.writeExports(exports, index("fx:demo:main"));
        Path compile = directory.resolve("compile.json");
        ManifestCodec.writeCompile(compile, new CompileManifest(2, "2.0.0-test", index("fx:demo:main"),
            List.of(index("fx:peer:main")), "processor-inventory.json", List.of("fx/DocsGraph.java")));
        Path owned = directory.resolve("owned-outputs.json");
        ManifestCodec.writeOwned(owned, new OwnedOutputs(2, List.of("fx/Docs_.java"), List.of("fx/Docs_.class"),
            List.of("META-INF/graphmaquina/exports.json"), List.of()));
        Path processor = directory.resolve("processor-inventory.json");
        ManifestCodec.writeProcessorInventory(processor, List.of("fx.Docs_"));

        for (Path file : List.of(request, exports, compile, owned, processor)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(text.contains("\n"), file.toString());
            assertFalse(text.contains("\r"), file.toString());
            assertEquals(file.equals(processor), text.endsWith("\n"), file.toString());
        }
    }

    @Test
    void rejectsUnknownSchemaVersion() throws IOException {
        Path file = directory.resolve("future.json");
        Files.writeString(file, """
            {
              "schemaVersion": 99,
              "toolVersion": "2.0.0-test",
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
              "toolVersion": "2.0.0-test",
              "compilationId": "fx:empty:main",
              "model": { "graphs": [], "symbols": [] },
              "annotationTypes": []
            }
            """.formatted(schemaVersion));

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("member 'schemaVersion' must be a 32-bit integer in " + file + ", but is " + schemaVersion,
            failure.getMessage());
    }

    @Test
    void rejectsAnOriginPositionThatIsNotAnExactInteger() throws IOException {
        GraphDescriptor graph = new GraphDescriptor("fx.Orders", "fx", "fx.Order", "fx.Status",
            List.of("NEW", "DONE"), "fx.OrderTransition", List.of("SUBMIT"), List.of(), origin());
        Path file = directory.resolve("origin.json");
        ManifestCodec.writeExports(file, new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(graph), List.of()), List.of()));
        Files.writeString(file, Files.readString(file).replace("\"line\": 10", "\"line\": 10.5"));

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("member 'line' must be a 64-bit integer in " + file + ", but is 10.5", failure.getMessage());
    }

    /**
     * A number whose exponent lies beyond the range a {@link java.math.BigDecimal} holds is well-formed JSON all the
     * same. Wherever it stands, it is refused like any other invalid value: with {@code SG602}, naming the file and
     * the place of the number in the document.
     */
    @ParameterizedTest
    @CsvSource({
        "schemaVersion, 1e99999999999, $.schemaVersion",
        "line, 1E2147483649, $.model.graphs[0].origin.line"})
    void rejectsANumberWhoseExponentIsOutOfRange(String member, String number, String place) throws IOException {
        GraphDescriptor graph = new GraphDescriptor("fx.Orders", "fx", "fx.Order", "fx.Status",
            List.of("NEW", "DONE"), "fx.OrderTransition", List.of("SUBMIT"), List.of(), origin());
        Path file = directory.resolve("exponent.json");
        ManifestCodec.writeExports(file, new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(graph), List.of()), List.of()));
        Files.writeString(file, Files.readString(file)
            .replaceFirst("\"" + member + "\": \\d+", "\"" + member + "\": " + number));

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readExports(file));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("number with an exponent out of range in " + file + ": " + number + " at path " + place,
            failure.getMessage());
    }

    /**
     * Such a number that is an element of an array is placed at the index of that element, the one just read, not
     * at the index of the element that would follow it.
     */
    @Test
    void placesANumberWhoseExponentIsOutOfRangeAtItsOwnArrayIndex() throws IOException {
        Path file = directory.resolve("processor-inventory.json");
        Files.writeString(file, "{\"schemaVersion\": 2, \"generatedTypes\": [1e99999999999]}\n");

        CodegenException failure =
            assertThrows(CodegenException.class, () -> ManifestCodec.readProcessorInventory(file));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("number with an exponent out of range in " + file + ": 1e99999999999 at path $.generatedTypes[0]",
            failure.getMessage());
    }

    /**
     * Strict JSON can escape a surrogate that is not half of a pair, which no UTF-8 file can hold, so the tools could
     * not write the value back. Every file the codec reads refuses one, in a string or in a name, with {@code SG602},
     * naming the file, the surrogate and its place; a pair, escaped or not, is read as the character it encodes.
     */
    @Test
    void rejectsAnUnpairedSurrogateInAStringOrAName() throws IOException {
        Path request = directory.resolve("request.json");
        ManifestCodec.writeRequest(request, new GenerationRequest(directory.resolve("project"),
            List.of(directory.resolve("project/src")), List.of(), directory.resolve("generated"),
            directory.resolve("processor"), directory.resolve("classes"), directory.resolve("work"),
            "fx:demo:main"));
        Files.writeString(request, Files.readString(request).replace("fx:demo:main", "fx:demo\\ud800:main"));
        Path inventory = directory.resolve("processor-inventory.json");
        Files.writeString(inventory, "{\"schemaVersion\": 2, \"generatedTypes\": [], \"x\\udc00\": 1}\n");
        Path paired = directory.resolve("paired-inventory.json");
        Files.writeString(paired, "{\"schemaVersion\": 2, \"generatedTypes\": [\"fx.A\\ud83d\\ude00\"]}\n");

        CodegenException value = assertThrows(CodegenException.class, () -> ManifestCodec.readRequest(request));
        CodegenException name =
            assertThrows(CodegenException.class, () -> ManifestCodec.readProcessorInventory(inventory));

        assertEquals(DiagnosticCode.SG602.name(), value.code());
        assertEquals("malformed JSON in " + request + ": an unpaired surrogate \\ud800 at path $.compilationId",
            value.getMessage());
        assertEquals(DiagnosticCode.SG602.name(), name.code());
        assertEquals("malformed JSON in " + inventory + ": an unpaired surrogate \\udc00 at path $.x\\udc00",
            name.getMessage());
        assertEquals(List.of("fx.A\ud83d\ude00"), ManifestCodec.readProcessorInventory(paired));
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

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("unexpected trailing content in " + file, failure.getMessage());
    }

    /** What an interrupted write leaves behind is reported against the file, so a user knows what to delete. */
    @Test
    void namesTheFileOfADocumentThatEndsEarly() throws IOException {
        Path file = directory.resolve("owned-outputs.pending.json");
        Files.writeString(file, "{\n  \"schemaVersion\": 2,\n  \"sources\": [\n    \"fx/DocsGraph.java\",\n");

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readOwned(file));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
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

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
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

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
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
              "toolVersion": "2.0.0-test",
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
        ExportIndex indexWithout = new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(), List.of(withoutProperty)), List.of());
        Path fileWithout = directory.resolve("without-property.json");
        ManifestCodec.writeExports(fileWithout, indexWithout);
        assertFalse(Files.readString(fileWithout).contains("\"property\""));
        assertEquals(indexWithout, ManifestCodec.readExports(fileWithout));

        SymbolDescriptor withProperty = new SymbolDescriptor("fx.PaymentActions.Amount",
            "PaymentActions", "amount", Set.of(SymbolKind.PROPERTY), List.of(),
            Optional.of(new PropertyDescriptor("fx.PaymentActions", "amount", "getAmount", true, origin())),
            origin());
        ExportIndex indexWith = new ExportIndex(2, "2.0.0-test", "fx:demo:main",
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

        ExportIndex ordersFirst = new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(ordersGraph, billingGraph), List.of()), List.of("b", "a"));
        ExportIndex billingFirst = new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(billingGraph, ordersGraph), List.of()), List.of("a", "b"));

        Path first = directory.resolve("orders-first.json");
        Path second = directory.resolve("billing-first.json");
        ManifestCodec.writeExports(first, ordersFirst);
        ManifestCodec.writeExports(second, billingFirst);
        assertEquals(Files.readString(first), Files.readString(second));
    }

    /**
     * An index is written with its graphs, its symbols, each graph's peers, each symbol's kinds and candidates, the
     * candidates by name and then by their parameter types, and its annotation types sorted, while a graph's states
     * and transitions and a candidate's parameter types keep the order they were given in.
     */
    @Test
    void writesTheIdentityListsOfAnIndexSortedAndItsOtherListsInTheirOrder() throws IOException {
        MemberDescriptor wide = new MemberDescriptor("fx.Handlers", "archive", List.of("int", "fx.Order"), "void",
            SymbolKind.ACTION, false, origin());
        MemberDescriptor narrow = new MemberDescriptor("fx.Handlers", "archive", List.of("fx.Order"), "void",
            SymbolKind.ACTION, false, origin());
        MemberDescriptor approve = new MemberDescriptor("fx.Handlers", "approve", List.of("fx.Order"), "boolean",
            SymbolKind.GUARD, false, origin());
        SymbolDescriptor zeta = new SymbolDescriptor("fx.HandlersRefs.Zeta", "Handlers", "zeta",
            Set.of(SymbolKind.GUARD, SymbolKind.CONDITION), List.of(wide, approve, narrow), Optional.empty(), origin());
        SymbolDescriptor alpha = new SymbolDescriptor("fx.HandlersRefs.Alpha", "Handlers", "alpha",
            Set.of(SymbolKind.ACTION), List.of(narrow), Optional.empty(), origin());
        GraphDescriptor orders = new GraphDescriptor("fx.Orders", "fx", "fx.Order", "fx.Status",
            List.of("NEW", "DONE"), "fx.OrderTransition", List.of("SUBMIT", "SHIP"), List.of("fx.Pages", "fx.Billing"),
            origin());
        GraphDescriptor billing = new GraphDescriptor("fx.Billing", "fx", "fx.Invoice", "fx.Status",
            List.of("NEW", "DONE"), "fx.BillingTransition", List.of("ISSUE", "SETTLE"), List.of(), origin());
        Path file = directory.resolve("exports.json");

        ManifestCodec.writeExports(file, new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(orders, billing), List.of(zeta, alpha)),
            List.of("fx.OrdersGraph.Transition", "fx.BillingGraph.Definition")));

        JsonObject index = parse(file);
        JsonArray graphs = index.getAsJsonObject("model").getAsJsonArray("graphs");
        assertEquals(List.of("fx.Billing", "fx.Orders"), memberValues(graphs, "facade"));
        JsonObject ordersJson = graphs.get(1).getAsJsonObject();
        assertEquals(List.of("fx.Billing", "fx.Pages"), strings(ordersJson.getAsJsonArray("peers")));
        assertEquals(List.of("NEW", "DONE"), strings(ordersJson.getAsJsonArray("states")));
        assertEquals(List.of("SUBMIT", "SHIP"), strings(ordersJson.getAsJsonArray("transitions")));
        JsonArray symbols = index.getAsJsonObject("model").getAsJsonArray("symbols");
        assertEquals(List.of("fx.HandlersRefs.Alpha", "fx.HandlersRefs.Zeta"), memberValues(symbols, "qualifiedName"));
        JsonObject zetaJson = symbols.get(1).getAsJsonObject();
        assertEquals(List.of("CONDITION", "GUARD"), strings(zetaJson.getAsJsonArray("kinds")));
        JsonArray candidates = zetaJson.getAsJsonArray("candidates");
        assertEquals(List.of("approve", "archive", "archive"), memberValues(candidates, "name"));
        assertEquals(List.of("fx.Order"),
            strings(candidates.get(1).getAsJsonObject().getAsJsonArray("parameterTypes")));
        assertEquals(List.of("int", "fx.Order"),
            strings(candidates.get(2).getAsJsonObject().getAsJsonArray("parameterTypes")));
        assertEquals(List.of("fx.BillingGraph.Definition", "fx.OrdersGraph.Transition"),
            strings(index.getAsJsonArray("annotationTypes")));
    }

    /** A manifest is written with its dependencies sorted by their identifier and its owned sources sorted. */
    @Test
    void writesTheDependenciesAndTheOwnedSourcesOfAManifestSorted() throws IOException {
        Path file = directory.resolve("compile.json");

        ManifestCodec.writeCompile(file, new CompileManifest(2, "2.0.0-test", index("fx:demo:main"),
            List.of(index("fx:zeta:main"), index("fx:alpha:main")), "processor-inventory.json",
            List.of("fx/ZetaGraph.java", "fx/AlphaGraph.java")));

        JsonObject manifest = parse(file);
        assertEquals(List.of("fx:alpha:main", "fx:zeta:main"),
            memberValues(manifest.getAsJsonArray("dependencies"), "compilationId"));
        assertEquals(List.of("fx/AlphaGraph.java", "fx/ZetaGraph.java"),
            strings(manifest.getAsJsonArray("ownedSources")));
    }

    /** An inventory is written with each of its four lists sorted, and the processor inventory with its types. */
    @Test
    void writesTheListsOfBothInventoriesSorted() throws IOException {
        Path owned = directory.resolve("owned-outputs.json");
        Path processor = directory.resolve("processor-inventory.json");

        ManifestCodec.writeOwned(owned, new OwnedOutputs(2, List.of("fx/Zeta_.java", "fx/Alpha_.java"),
            List.of("fx/Zeta_.class", "fx/Alpha_.class"), List.of("META-INF/z.json", "META-INF/a.json"),
            List.of("fx/Zeta.class", "fx/Alpha.class")));
        ManifestCodec.writeProcessorInventory(processor, List.of("fx.Zeta_", "fx.Alpha_"));

        JsonObject inventory = parse(owned);
        assertEquals(List.of("fx/Alpha_.java", "fx/Zeta_.java"), strings(inventory.getAsJsonArray("sources")));
        assertEquals(List.of("fx/Alpha_.class", "fx/Zeta_.class"), strings(inventory.getAsJsonArray("binaries")));
        assertEquals(List.of("META-INF/a.json", "META-INF/z.json"), strings(inventory.getAsJsonArray("resources")));
        assertEquals(List.of("fx/Alpha.class", "fx/Zeta.class"),
            strings(inventory.getAsJsonArray("excludedBinaries")));
        assertEquals(List.of("fx.Alpha_", "fx.Zeta_"), strings(parse(processor).getAsJsonArray("generatedTypes")));
    }

    @Test
    void compileManifestRoundTripsWithDependenciesAndOwnedSources() throws IOException {
        ExportIndex local = new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(), List.of()), List.of("io.github.graphmaquina.symbol.Authorize"));
        ExportIndex dependency = new ExportIndex(2, "2.0.0-test", "fx:peer:main",
            new GenerationModel(List.of(), List.of()), List.of());
        CompileManifest manifest = new CompileManifest(2, "2.0.0-test", local,
            List.of(dependency), "META-INF/graphmaquina/pending-exports.json",
            List.of("fx/Authorize.java"));

        Path file = directory.resolve("compile.json");
        ManifestCodec.writeCompile(file, manifest);
        assertEquals(manifest, ManifestCodec.readCompile(file));
    }

    @Test
    void rejectsOwnedSourcePathEscapingRootOnWrite() {
        ExportIndex local = new ExportIndex(2, "2.0.0-test", "fx:demo:main",
            new GenerationModel(List.of(), List.of()), List.of());
        CompileManifest manifest = new CompileManifest(2, "2.0.0-test", local, List.of(),
            "META-INF/graphmaquina/pending-exports.json", List.of("../Escaped.java"));
        Path file = directory.resolve("escaping.json");
        assertThrows(CodegenException.class, () -> ManifestCodec.writeCompile(file, manifest));
    }

    /**
     * An owned source must name a file below the generated source root. One that has a root, as {@code /x.java} has
     * here and {@code \x.java} or {@code C:x.java} would have on Windows, one with a {@code ..} segment, one that names
     * the root itself, as {@code .} and the empty path do, and one no path can spell, such as one holding the NUL
     * character, are refused with {@code SG602}, both when a manifest is written and when it is read.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/x.java", "../x.java", ".", "", "NUL"})
    void refusesAnOwnedSourceThatNamesNoFileBelowItsRoot(String entry) throws IOException {
        String ownedSource = entry.equals("NUL") ? "fx/X\u0000.java" : entry;
        Path written = directory.resolve("written.json");

        CodegenException onWrite = assertThrows(CodegenException.class, () -> ManifestCodec.writeCompile(written,
            new CompileManifest(2, "2.0.0-test", index("fx:demo:main"), List.of(), "processor-inventory.json",
                List.of(ownedSource))));

        assertEquals(DiagnosticCode.SG602.name(), onWrite.code());
        assertFalse(Files.exists(written));
        Path valid = directory.resolve("valid.json");
        ManifestCodec.writeCompile(valid, new CompileManifest(2, "2.0.0-test", index("fx:demo:main"), List.of(),
            "processor-inventory.json", List.of("fx/Valid.java")));
        Path read = Files.writeString(directory.resolve("read.json"), Files.readString(valid, StandardCharsets.UTF_8)
            .replace("fx/Valid.java", entry.equals("NUL") ? "fx/X\\u0000.java" : entry), StandardCharsets.UTF_8);
        CodegenException onRead = assertThrows(CodegenException.class, () -> ManifestCodec.readCompile(read));
        assertEquals(DiagnosticCode.SG602.name(), onRead.code());
    }

    /**
     * The processor inventory is a file the processor writes and the build reads back, so one that no path can spell,
     * such as one holding the NUL character, is refused with {@code SG602} naming the manifest, both when a manifest
     * is written and when it is read, before a step that makes a path of it fails on it.
     */
    @Test
    void refusesAProcessorInventoryThatNoPathCanSpell() throws IOException {
        String inventory = "work/inventory\u0000.json";
        Path written = directory.resolve("written.json");

        CodegenException onWrite = assertThrows(CodegenException.class, () -> ManifestCodec.writeCompile(written,
            new CompileManifest(2, "2.0.0-test", index("fx:demo:main"), List.of(), inventory,
                List.of("fx/Valid.java"))));

        assertEquals(DiagnosticCode.SG602.name(), onWrite.code());
        assertEquals("processor inventory '" + inventory + "' is not a path in " + written, onWrite.getMessage());
        assertFalse(Files.exists(written));
        Path valid = directory.resolve("valid.json");
        ManifestCodec.writeCompile(valid, new CompileManifest(2, "2.0.0-test", index("fx:demo:main"), List.of(),
            "work/inventory.json", List.of("fx/Valid.java")));
        Path read = Files.writeString(directory.resolve("read.json"), Files.readString(valid, StandardCharsets.UTF_8)
            .replace("work/inventory.json", "work/inventory\\u0000.json"), StandardCharsets.UTF_8);
        CodegenException onRead = assertThrows(CodegenException.class, () -> ManifestCodec.readCompile(read));
        assertEquals(DiagnosticCode.SG602.name(), onRead.code());
        assertEquals("processor inventory '" + inventory + "' is not a path in " + read, onRead.getMessage());
    }

    /** A request round-trips as one flat object, with its sources and its classpath in the order it gives them. */
    @Test
    void generationRequestRoundTripsAsFlatJson() throws IOException {
        GenerationRequest request = new GenerationRequest(
            directory.resolve("project"),
            List.of(directory.resolve("project/src/main/java"), directory.resolve("project/src/extra/java")),
            List.of(directory.resolve("deps/two.jar"), directory.resolve("deps/one.jar")),
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
     * The release and the encoding of the compilation are optional members, each read on its own: a request names
     * them when the build knows them, and a member it leaves out, whether or not it names the other, is read as
     * release 21 or as UTF-8, the values of a request that names neither.
     * Both are written back as they were read.
     */
    @Test
    void generationRequestReadsTheReleaseAndTheEncodingItNamesAndDefaultsTheOthers() throws IOException {
        Path older = Files.writeString(directory.resolve("older.json"), requestJson(""), StandardCharsets.UTF_8);
        Path newer = Files.writeString(directory.resolve("newer.json"),
                requestJson(",\n  \"release\": 25,\n  \"encoding\": \"ISO-8859-1\""), StandardCharsets.UTF_8);
        Path releaseOnly = Files.writeString(directory.resolve("release-only.json"),
                requestJson(",\n  \"release\": 25"), StandardCharsets.UTF_8);
        Path encodingOnly = Files.writeString(directory.resolve("encoding-only.json"),
                requestJson(",\n  \"encoding\": \"ISO-8859-1\""), StandardCharsets.UTF_8);

        GenerationRequest defaults = ManifestCodec.readRequest(older);
        GenerationRequest named = ManifestCodec.readRequest(newer);
        GenerationRequest release = ManifestCodec.readRequest(releaseOnly);
        GenerationRequest encoding = ManifestCodec.readRequest(encodingOnly);

        assertEquals(21, defaults.release());
        assertEquals(StandardCharsets.UTF_8, defaults.encoding());
        assertEquals(25, named.release());
        assertEquals(StandardCharsets.ISO_8859_1, named.encoding());
        assertEquals(25, release.release());
        assertEquals(StandardCharsets.UTF_8, release.encoding());
        assertEquals(21, encoding.release());
        assertEquals(StandardCharsets.ISO_8859_1, encoding.encoding());
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

            assertEquals(DiagnosticCode.SG602.name(), failure.code(), member.getKey());
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
        ExportIndex index = new ExportIndex(2, "2.0.0-test", "fx:demo:main",
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
                  "toolVersion": "2.0.0-test",
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
            assertEquals(DiagnosticCode.SG602.name(), failure.code());
            assertTrue(failure.getMessage().contains("missing member '" + missing + "'"),
                failure.getMessage());
        }
    }

    @Test
    void rejectsACompileManifestOfAnotherSchemaBeforeReadingAnyGraphOfIt() throws IOException {
        Path file = directory.resolve("other-schema-compile.json");
        Files.writeString(file, """
            {
              "schemaVersion": 99,
              "toolVersion": "2.0.0-test",
              "local": {"graphs": "not a model"},
              "dependencies": [],
              "processorInventory": "META-INF/graphmaquina/pending-exports.json",
              "ownedSources": ["fx/OrdersGraph.java"]
            }
            """);

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readCompile(file));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("unsupported schemaVersion 99 in " + file + "; expected 2", failure.getMessage());
    }

    /** The tool never consumes an inventory of a schema it does not read. */
    @Test
    void rejectsAnOwnershipInventoryOfAnotherSchema() throws IOException {
        Path file = directory.resolve("owned-outputs.json");
        Files.writeString(file, """
            {
              "schemaVersion": 99,
              "sources": ["fx/OrdersGraph.java"],
              "binaries": ["fx/OrdersGraph.class"],
              "resources": ["META-INF/graphmaquina/exports.json"],
              "excludedBinaries": []
            }
            """);

        CodegenException failure = assertThrows(CodegenException.class, () -> ManifestCodec.readOwned(file));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("unsupported schemaVersion 99 in " + file + "; expected 2", failure.getMessage());
    }

    /** An index of {@code compilationId} with an empty model and no annotation type. */
    private static ExportIndex index(String compilationId) {
        return new ExportIndex(2, "2.0.0-test", compilationId, new GenerationModel(List.of(), List.of()),
            List.of());
    }

    /** The JSON object {@code file} holds, its arrays in the order the document spells them. */
    private static JsonObject parse(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** The strings of {@code array}, in the order the document spells them. */
    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    /** The string member {@code name} of each object of {@code array}, in the order the document spells them. */
    private static List<String> memberValues(JsonArray array, String name) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsJsonObject().get(name).getAsString());
        }
        return values;
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
            if (isMemberName(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** Whether {@code key} names a member, and is neither the {@code graphs} key nor a coordinate of an origin. */
    private static boolean isMemberName(String key) {
        return !"path".equals(key) && !"line".equals(key) && !"column".equals(key) && !"graphs".equals(key);
    }
}
