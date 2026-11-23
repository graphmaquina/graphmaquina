package io.github.graphmaquina.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.source.Bootstrap;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that every generated symbol type whose family exports a guard declares {@code NAME}, the runtime name of
 * the guard, as a constant expression, and that no other symbol type declares it.
 */
class GuardNameConstantTest {

    private static final String NAME_LINE = "public static final java.lang.String NAME = ";

    @TempDir
    Path directory;

    @Test
    void aGuardSymbolCarriesItsRuntimeName() {
        String refs = refsOf("""
            @GraphAction public void publish(Doc doc) { }
            @GraphGuard public boolean approved(Doc doc) { return true; }
            """);

        assertTrue(bodyOf(refs, "Approved").contains(NAME_LINE + "\"approved\";"), refs);
    }

    @Test
    void anActionSymbolCarriesNoName() {
        String refs = refsOf("""
            @GraphAction public void publish(Doc doc) { }
            @GraphGuard public boolean approved(Doc doc) { return true; }
            """);

        assertFalse(bodyOf(refs, "Publish").contains("NAME"), refs);
    }

    @Test
    void aSymbolWithAGuardAmongItsKindsCarriesTheName() {
        String refs = refsOf("""
            @GraphGuard public boolean approved(Doc doc) { return true; }
            @GraphCondition public boolean approved(Doc doc, int level) { return true; }
            """);

        assertTrue(refs.contains("member = \"approved\", kinds = {"), refs);
        assertTrue(bodyOf(refs, "Approved").contains(NAME_LINE + "\"approved\";"), refs);
    }

    @Test
    void theNameIsAConstantExpression() {
        Map<String, String> usage = Map.of("fx.Usage", """
            package fx;
            public class Usage {
                int pick(String s) {
                    return switch (s) {
                        case DocActionsRefs.Approved.NAME -> 1;
                        default -> 0;
                    };
                }
            }
            """);

        SourceFiles.Compilation compilation = compile("""
            @GraphGuard public boolean approved(Doc doc) { return true; }
            """, usage);

        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    @Test
    void aGuardNamedStringStillCompiles() {
        SourceFiles.Compilation compilation = compile("""
            @GraphGuard public boolean string(Doc doc) { return true; }
            """, Map.of());

        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    private String refsOf(String members) {
        return emit(members).stream()
            .filter((GeneratedSource source) -> source.qualifiedName().equals("fx.DocActionsRefs"))
            .findFirst().orElseThrow().content();
    }

    private SourceFiles.Compilation compile(String members, Map<String, String> usage) {
        List<GeneratedSource> emitted = emit(members);
        List<Path> files = new ArrayList<>(SourceFiles.write(directory.resolve("sources"), originals(members)));
        files.addAll(SourceFiles.write(directory.resolve("usage"), usage));
        for (GeneratedSource source : emitted) {
            Path file = directory.resolve("generated").resolve(source.qualifiedName().replace('.', '/') + ".java");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.content(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            files.add(file);
        }
        return SourceFiles.compileStrict(files, directory.resolve("classes"));
    }

    private List<GeneratedSource> emit(String members) {
        Path sources = directory.resolve("sources");
        SourceFiles.write(sources, originals(members));
        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(sources),
            SourceFiles.classpath(), directory.resolve("generated"), directory.resolve("processor-generated"),
            directory.resolve("classes"), directory.resolve("work"), "guard-names"));
        return ApiEmitter.emit(model, "guard-names");
    }

    private static Map<String, String> originals(String members) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", "package fx; public enum DocStatus { DRAFT, PUBLISHED }");
        sources.put("fx.DocTransition", "package fx; public enum DocTransition { PUBLISH }");
        sources.put("fx.Doc", "package fx; public class Doc { }");
        sources.put("fx.Docs", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {}
            """);
        sources.put("fx.DocActions", """
            package fx;
            import io.github.graphmaquina.annotation.*;
            public class DocActions {
            %s}
            """.formatted(members.indent(4)));
        return sources;
    }

    /** The text of the symbol type {@code name}, from its declaration to the closing brace of its own body. */
    private static String bodyOf(String refs, String name) {
        int start = refs.indexOf("public static final class " + name + " ");
        int end = refs.indexOf("\n    }\n", start);
        return refs.substring(start, end);
    }
}
