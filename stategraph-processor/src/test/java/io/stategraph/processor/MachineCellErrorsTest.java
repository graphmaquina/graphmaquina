package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MachineCellErrorsTest {

    private static Compilation.Result body(String members) {
        return Compilation.compile(Fixtures.withDocs(Fixtures.DOCS_HEADER, members));
    }

    private static Compilation.Result header(String typeAnnotations) {
        return Compilation.compile(Fixtures.withDocs(typeAnnotations, Fixtures.DOCS_BODY));
    }

    static final String SUBMIT = """
                @Move(from = "DRAFT", to = "REVIEW")
                Outcome<Doc, DocStatus> submit(Doc doc);
            """;

    static final String ARCHIVE = """
                @Action(from = "PUBLISHED", to = "ARCHIVED", by = DocActions.class, action = "archiveAsync", timeout = "PT2S")
                Outcome<Doc, DocStatus> archive(Doc doc);
            """;

    private static String publish(String elements) {
        return "    @Action(from = \"REVIEW\", to = \"PUBLISHED\", by = DocActions.class" + elements + ")\n"
                + "    Outcome<Doc, DocStatus> publish(Doc doc);\n";
    }

    @Test
    void sg504OperationSignatureAndMissingOrDoubleAnnotation() {
        assertTrue(body(SUBMIT + "    Outcome<Doc, DocStatus> orphan(Doc doc);\n" + publish("") + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @Move(from = \"REVIEW\", to = \"PUBLISHED\")\n" + publish("") + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @Action(from = \"REVIEW\", to = \"PUBLISHED\", by = DocActions.class)\n"
                + "    Outcome<Doc, DocStatus> publish(Doc doc, String reason);\n" + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @Action(from = \"REVIEW\", to = \"PUBLISHED\", by = DocActions.class)\n"
                + "    void publish(Doc doc);\n" + ARCHIVE).hasError("SG504"));
        Compilation.Result defaults = body(SUBMIT + publish("") + ARCHIVE
                + "    default Outcome<Doc, DocStatus> publishTwice(Doc doc) { publish(doc); return publish(doc); }\n");
        assertTrue(defaults.success(), defaults.describe());
    }

    @Test
    void sg505BindingProblems() {
        assertTrue(body(SUBMIT + publish(", action = \"nope\"") + ARCHIVE).hasError("SG505"));
        assertTrue(body(SUBMIT + publish(", action = \"notAnAction\"") + ARCHIVE).hasError("SG505"));
        assertTrue(body(SUBMIT + publish(", action = \"hidden\"") + ARCHIVE).hasError("SG505"));
        assertTrue(body(SUBMIT + publish(", guards = \"publish\"") + ARCHIVE).hasError("SG505"));
        assertTrue(body(SUBMIT + publish(", afterCommit = \"approved\"") + ARCHIVE).hasError("SG505"));
        Compilation.Result hiddenClass = body(SUBMIT
                + "    @Action(from = \"REVIEW\", to = \"PUBLISHED\", by = HiddenActions.class)\n    Outcome<Doc, DocStatus> publish(Doc doc);\n"
                + ARCHIVE);
        assertTrue(hiddenClass.hasError("SG505"), hiddenClass.describe());
    }

    @Test
    void sg507ElementDependencies() {
        assertTrue(body(SUBMIT + publish(", timeout = \"PT1S\"") + ARCHIVE).hasError("SG507"));
        assertTrue(body("    @Move(from = \"DRAFT\", to = \"REVIEW\", guards = \"approved\")\n    Outcome<Doc, DocStatus> submit(Doc doc);\n"
                + publish("") + ARCHIVE).hasError("SG507"));
        assertTrue(body(SUBMIT + publish(", guardsBy = DocActions.class") + ARCHIVE).hasError("SG507"));
        assertTrue(header(Fixtures.DOCS_HEADER.replace("@Cell(from = \"REVIEW\", to = \"DRAFT\")",
                "@Cell(from = \"REVIEW\", to = \"DRAFT\", by = DocActions.class)")).hasError("SG507"));
        assertTrue(header(Fixtures.DOCS_HEADER.replace("@Cell(from = \"REVIEW\", to = \"DRAFT\")",
                "@Cell(from = \"REVIEW\", to = \"DRAFT\", action = \"publish\")")).hasError("SG507"));
        assertTrue(header(Fixtures.DOCS_HEADER.replace("@Cell(from = \"REVIEW\", to = \"DRAFT\")",
                "@Cell(from = \"REVIEW\", to = \"DRAFT\", onFailure = \"REVIEW\")")).hasError("SG507"));
    }

    @Test
    void sg511TimeoutFormat() {
        assertTrue(body(SUBMIT + publish("") + ARCHIVE.replace("PT2S", "5s")).hasError("SG511"));
        assertTrue(body(SUBMIT + publish("") + ARCHIVE.replace("PT2S", "PT0S")).hasError("SG511"));
    }

    @Test
    void sg104SelfLoopSg105DuplicateGuardSg302ForbiddenFailsOnSg501OnFailure() {
        assertTrue(body(SUBMIT + publish("").replace("from = \"REVIEW\"", "from = {\"REVIEW\", \"PUBLISHED\"}") + ARCHIVE).hasError("SG104"));
        assertTrue(body(SUBMIT + publish(", guards = {\"approved\", \"approved\"}") + ARCHIVE).hasError("SG105"));
        assertTrue(body(SUBMIT + publish(", failsOn = RuntimeException.class") + ARCHIVE).hasError("SG302"));
        assertTrue(body(SUBMIT + publish(", onFailure = \"DRAF\"") + ARCHIVE).hasError("SG501"));
    }

    @Test
    void sg102DuplicateCellAcrossOperationsAndCells() {
        Compilation.Result result = header(Fixtures.DOCS_HEADER + "@Cell(from = \"DRAFT\", to = \"REVIEW\")\n");
        assertTrue(result.hasError("SG102"), result.describe());
        Compilation.Result forbid = header(Fixtures.DOCS_HEADER + "@Forbid(from = \"REVIEW\", to = \"DRAFT\", reason = \"no\")\n");
        assertTrue(forbid.hasError("SG102"), forbid.describe());
    }

    @Test
    void staticGuardsGuardsByAndForbidAreAccepted() {
        Compilation.Result result = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@Forbid(from = \"DRAFT\", to = \"ARCHIVED\", reason = \"a draft is never archived\")\n",
                SUBMIT + publish(", guards = {\"ready\", \"approved\"}, failsOn = IOException.class") + ARCHIVE));
        assertTrue(result.success(), result.describe());
        Compilation.Result guardsBy = body("    @Move(from = \"DRAFT\", to = \"REVIEW\", by = DocActions.class, guards = \"ready\")\n"
                + "    Outcome<Doc, DocStatus> submit(Doc doc);\n" + publish(", guardsBy = DocActions.class, guards = \"ready\"") + ARCHIVE);
        assertTrue(guardsBy.success(), guardsBy.describe());
    }

    @Test
    void errorsAreCountedOncePerProblem() {
        Compilation.Result result = body(SUBMIT + publish(", action = \"nope\", onFailure = \"DRAF\"") + ARCHIVE);
        assertEquals(1, result.countError("SG505"), result.describe());
        assertEquals(1, result.countError("SG501"), result.describe());
    }
}
