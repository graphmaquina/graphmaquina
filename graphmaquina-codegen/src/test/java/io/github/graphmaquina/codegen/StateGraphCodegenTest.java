package io.github.graphmaquina.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.source.ProducerJars;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the order of one generation: the export indexes of the dependencies are read and checked before any peer
 * is resolved or any source is emitted, so a fault of a dependency is reported before a fault of the compilation's
 * own sources.
 */
class StateGraphCodegenTest {

    @TempDir
    Path project;

    /**
     * The machine {@code _1machine} normalizes to no valid type name, which the emitter refuses with {@code SG603}, as
     * the control without the dependency shows. With a dependency whose index another tool version wrote, that index
     * is what is reported.
     */
    @Test
    void aDependencyIndexErrorComesBeforeAnEmissionError() throws IOException {
        Path root = project.resolve("sources");
        SourceFiles.write(root, Map.of("fx._1machine", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface _1machine extends Machine<String, Thread.State, java.time.DayOfWeek> {}
            """));
        Path jar = ProducerJars.archived(project.resolve("libs/other.jar"), GenerationFiles.PUBLISHED_INDEX,
                new ExportIndex(GenerationFiles.SCHEMA_VERSION, "0.0.0-other", "other:lib:main",
                        new GenerationModel(List.of(), List.of()), List.of()));
        CodegenException control = assertThrows(CodegenException.class,
                () -> StateGraphCodegen.generate(request(root, List.of())));
        assertEquals(DiagnosticCode.SG603.name(), control.code());

        CodegenException failure = assertThrows(CodegenException.class,
                () -> StateGraphCodegen.generate(request(root, List.of(jar))));

        assertEquals(DiagnosticCode.SG602.name(), failure.code());
        assertEquals("the StateGraph export index published by " + jar.toAbsolutePath().normalize()
                + " was written by tool version 0.0.0-other; this tool is " + ToolVersion.current(),
                failure.getMessage());
    }

    /** A request over the sources under {@code root}, with {@code extra} after the test classpath. */
    private GenerationRequest request(Path root, List<Path> extra) {
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.addAll(extra);
        return new GenerationRequest(project, List.of(root), classpath, project.resolve("generated"),
                project.resolve("processor-generated"), project.resolve("classes"), project.resolve("work"),
                "fx:test:main");
    }
}
