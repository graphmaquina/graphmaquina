package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GeneratedNamingTest {
    private static final String LIBRARY = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Library extends Machines { Docs docs(); }
            """;

    @Test
    void machineAndSetNamesNormalizeWithoutChangingTheirHandwrittenInterfaces() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx._Docs", sources.remove("fx.Docs").replace("interface Docs ", "interface _Docs "));
        sources.put("fx._Library", LIBRARY.replace("interface Library ", "interface _Library ").replace("Docs docs()", "_Docs docs()"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains("implements _Docs"));
        assertTrue(result.generated("fx.LibraryRuntime").contains("implements _Library"));
        assertFalse(result.hasGenerated("fx._Docs_"));
    }

    @Test
    void normalizedSetsCannotSilentlyShareOneRuntime() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Library", LIBRARY);
        sources.put("fx._Library", LIBRARY.replace("interface Library ", "interface _Library "));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG603"), result.describe());
        assertTrue(result.describe().contains("fx.Library") && result.describe().contains("fx._Library"), result.describe());
    }

    @Test
    void handwrittenRuntimeNameIsReportedAsCollision() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.DocsRuntime", "package fx; public class DocsRuntime {}");
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG603"), result.describe());
        assertTrue(result.describe().contains("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
    }

    @Test
    void setWithEmptyNormalizedNameReportsDiagnosticRatherThanCrashingProcessor() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.__", LIBRARY.replace("interface Library ", "interface __ "));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG603"), result.describe());
    }
}
