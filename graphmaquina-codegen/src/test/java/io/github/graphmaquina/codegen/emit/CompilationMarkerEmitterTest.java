package io.github.graphmaquina.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.util.JavacTask;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the carrier of a compilation: for an identifier that holds line breaks, control characters, quotes, a
 * backslash and a non-ASCII letter, the carrier compiles and javac reads that identifier back exactly.
 */
class CompilationMarkerEmitterTest {

    @TempDir
    Path directory;

    /**
     * A line break ends a Java string literal and no control character, C0, DEL or C1, belongs in source text, so
     * the carrier spells every one of them as an escape, which javac reads back as the very character the
     * identifier holds. Quotes, backslashes, text that only looks like a Unicode escape and non-ASCII letters
     * survive as well.
     */
    @Test
    void carriesAnIdentifierWithLineBreaksAndControlCharactersExactly() throws IOException {
        String compilationId = "acme:docs\nmain\r\t\001\037\177\200\205\237 \"quoted\" \\u0022 café";
        GeneratedSource carrier = CompilationMarkerEmitter.emit(compilationId);
        Path file = SourceFiles.write(directory.resolve("generated"),
                Map.of(carrier.qualifiedName(), carrier.content())).get(0);

        SourceFiles.Compilation compilation = SourceFiles.compileStrict(List.of(file), directory.resolve("classes"));

        assertTrue(carrier.content().chars().filter((int character) -> character != '\n')
                .noneMatch(Character::isISOControl), carrier.content());
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(compilationId, annotationValue(file));
    }

    /** The value of the one annotation the carrier declares, as javac parses its literal. */
    private static String annotationValue(Path file) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjects(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            ClassTree carrier = (ClassTree) unit.getTypeDecls().get(0);
            AnnotationTree annotation = carrier.getModifiers().getAnnotations().get(0);
            return (String) ((LiteralTree) annotation.getArguments().get(0)).getValue();
        }
    }
}
