package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SetErrorsTest {

    private static Compilation.Result library(String source) {
        return Compilation.compile(Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, source));
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
        Compilation.Result result = Compilation.compile(Fixtures.withLibrary(
                Fixtures.DOCS_HEADER.replace("initial = \"DRAFT\"", "initial = \"DRAFTT\"") + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertEquals(1, result.errors().size(), result.describe());
        assertTrue(result.hasError("SG501"), result.describe());
    }
}
