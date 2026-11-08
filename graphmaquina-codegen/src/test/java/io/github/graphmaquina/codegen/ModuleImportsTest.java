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
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.codegen.source.Bootstrap;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the generator resolves the types a single-module-import declaration brings into scope, Java 25 syntax,
 * as javac resolves them at release 25: the packages the module exports to every module and those of the modules it
 * requires transitively, below every other declaration in scope, with two modules that bring one simple name refused
 * as ambiguous. Each test reads the guard {@code fx.Rules.usable} and compares the canonical names the generator
 * recorded for its signature with the ones javac gives it. A JDK below 25 parses no module import at release 25, so
 * the tests run on JDK 25 or later only.
 */
class ModuleImportsTest {

    @TempDir
    Path directory;

    @BeforeEach
    void requireJava25() {
        int feature = Runtime.version().feature();
        assumeTrue(feature >= 25, "module imports are Java 25 syntax; this test runs on Java " + feature);
    }

    @Test
    void resolvesTheTypesAModuleImportBrings() throws IOException {
        MemberDescriptor usable = usable("""
            import module java.base;
            """, "CompletionStage<Boolean>", "BigDecimal amount, List<String> names, Map.Entry<String, UUID> entry");

        assertEquals(List.of("java.math.BigDecimal", "java.util.List<java.lang.String>",
                "java.util.Map.Entry<java.lang.String, java.util.UUID>"), usable.parameterTypes());
        assertEquals("java.util.concurrent.CompletionStage<java.lang.Boolean>", usable.returnType());
    }

    @Test
    void followsTheTransitiveRequirementsOfTheImportedModule() throws IOException {
        // java.sql requires java.logging transitively, and java.base only as every module does.
        MemberDescriptor usable = usable("""
            import module java.sql;
            """, "boolean", "Connection connection, Level level, BigDecimal amount");

        assertEquals(List.of("java.sql.Connection", "java.util.logging.Level", "BigDecimal"),
                usable.parameterTypes());
    }

    @Test
    void letsEveryOtherDeclarationInScopeShadowAModuleImport() throws IOException {
        // fx.Duration shadows java.time.Duration, java.awt.* shadows java.util.List and java.lang shadows nothing of
        // java.desktop's: javac reads the signature the same way.
        MemberDescriptor usable = usable("""
            import module java.base;
            import module java.desktop;
            import java.awt.*;
            import java.util.Map;
            """, "boolean", "Duration duration, List names, Map<Object, String> map");

        assertEquals(List.of("fx.Duration", "java.awt.List", "java.util.Map<java.lang.Object, java.lang.String>"),
                usable.parameterTypes());
    }

    @Test
    void refusesASimpleNameTwoModuleImportsBring() throws IOException {
        CodegenException ambiguous = assertThrows(CodegenException.class, () -> usable("""
            import module java.base;
            import module java.desktop;
            """, "boolean", "List names"));

        assertEquals(DiagnosticCode.SG601.name(), ambiguous.code(), ambiguous.getMessage());
        assertTrue(ambiguous.getMessage().contains("the type List is ambiguous between")
                && ambiguous.getMessage().contains("java.util.List")
                && ambiguous.getMessage().contains("java.awt.List"), ambiguous.getMessage());
    }

    @Test
    void importsNoPackageTheModuleExportsToNamedModulesOnly() throws IOException {
        // java.base exports jdk.internal.misc to a list of JDK modules only, and javac reports Unsafe as unknown.
        MemberDescriptor usable = usable("""
            import module java.base;
            """, "boolean", "Unsafe unsafe");

        assertEquals(List.of("Unsafe"), usable.parameterTypes());
    }

    @Test
    void leavesAModuleJavacCannotFindToJavac() throws IOException {
        MemberDescriptor usable = usable("""
            import module fx.missing;
            """, "boolean", "Connection connection");

        assertEquals(List.of("Connection"), usable.parameterTypes());
    }

    /**
     * The one candidate of {@code fx.Rules.usable}, a guard with {@code parameters} returning {@code result}, in a
     * source with {@code imports}, as the generator reads it at release 25. The package also declares
     * {@code fx.Duration}.
     */
    private MemberDescriptor usable(String imports, String result, String parameters) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Duration", "package fx;\npublic final class Duration {}\n");
        sources.put("fx.Rules", """
            package fx;
            %s
            import io.github.graphmaquina.annotation.GraphGuard;
            public final class Rules {
                @GraphGuard
                public static %s usable(%s) { return null; }
            }
            """.formatted(imports, result, parameters));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = directory.resolve("originals").resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        SymbolDescriptor symbol = Bootstrap.inspect(request()).symbols().stream()
                .filter((SymbolDescriptor described) -> described.qualifiedName().equals("fx.RulesRefs.Usable"))
                .findFirst().orElseThrow();
        assertEquals(1, symbol.candidates().size(), symbol::toString);
        return symbol.candidates().get(0);
    }

    /** The request of the fixture at release 25, written as a build plugin writes it and read back. */
    private GenerationRequest request() throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("projectDirectory", directory.toString());
        request.add("sources", strings(List.of(directory.resolve("originals").toString())));
        request.add("classpath", strings(SourceFiles.classpath().stream().map(Path::toString).toList()));
        request.addProperty("sourcesOutput", directory.resolve("generated").toString());
        request.addProperty("processorSourcesOutput", directory.resolve("processor").toString());
        request.addProperty("classesOutput", directory.resolve("classes").toString());
        request.addProperty("workDirectory", directory.resolve("work").toString());
        request.addProperty("compilationId", "fx:rules:main");
        request.addProperty("release", 25);
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
