package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SetErrorsTest {

    private static Compilation.Result library(String source) {
        return Compilation.compileTyped(Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, source));
    }

    @Test
    void validSetIsAccepted() {
        Compilation.Result result = library(Fixtures.LIBRARY);
        assertTrue(result.success(), result.describe());
    }

    @Test
    void sg502NotAnInterfaceOrNotMachines() {
        assertTrue(library(Fixtures.LIBRARY.replace("public interface Library extends Machines", "public abstract class Library implements Machines"))
                .hasError("SG502"));
        assertTrue(library(Fixtures.LIBRARY.replace(" extends Machines", "")).hasError("SG502"));
    }

    @Test
    void sg502NestedSetIsRejectedAndGeneratesNothing() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY);
        sources.put("fx.Holder", """
                package fx;
                import io.stategraph.annotation.StateGraphs;
                import io.stategraph.api.Machines;
                public final class Holder {
                    @StateGraphs
                    public interface Library extends Machines {
                        Docs docs();
                        Pages pages();
                    }
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.hasError("SG502"), result.describe());
        assertFalse(result.hasGenerated("fx.Library_"), result.describe());
    }

    @Test
    void sg510AccessorShapeDuplicatesAndMissingPeer() {
        assertTrue(library(Fixtures.LIBRARY.replace("Docs docs();", "Docs docs(String name);")).hasError("SG510"));
        assertTrue(library(Fixtures.LIBRARY.replace("Docs docs();", "String docs();")).hasError("SG510"));
        assertTrue(library(Fixtures.LIBRARY.replace("Pages pages();", "Pages pages();\n    Pages more();")).hasError("SG510"));
        Compilation.Result missingPeer = library(Fixtures.LIBRARY.replace("    Pages pages();\n", ""));
        assertTrue(missingPeer.hasError("SG510"), missingPeer.describe());
        assertTrue(missingPeer.errors().get(0).contains("Pages"), missingPeer.describe());
        assertTrue(library(Fixtures.LIBRARY.replace("    Docs docs();\n    Pages pages();\n", "")).hasError("SG510"));
    }

    @Test
    void aMachineWithErrorsDoesNotAddSetErrors() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \"")
                        + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertEquals(1, result.errors().size(), result.describe());
        assertTrue(result.hasError("SG103"), result.describe());
    }
}
