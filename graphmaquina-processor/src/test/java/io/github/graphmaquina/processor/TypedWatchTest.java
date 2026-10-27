package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A watch is declared with the vocabulary of one ordered pair, so the machine that carries it, the peer it
 * follows and the states of both are fixed by the types the generator emitted. What the language already
 * refuses is left to javac; everything a declaration can still get wrong keeps the code the rule had.
 */
class TypedWatchTest {

    @Test
    void readsAWatchWithItsRulesInOrder() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching());

        assertTrue(result.success(), result.describe());
        String companion = result.generated("fx.DocsRuntime");
        assertTrue(companion.contains(".watch(DocStatus.REVIEW, MachineKey.of(Page.class, PageStatus.class,"
                + " PageTransition.class), (Doc doc) -> doc.pages(), (Page page) -> page.doc())"), companion);
        int condition = companion.indexOf(".when(\"ready\", (doc, peers, view) -> docActions.ready(");
        int filter = companion.indexOf(".when(WatchCondition.allIn(PageStatus.DONE)).execute(DocTransition.PUBLISH)");
        assertTrue(condition > 0 && filter > condition, companion);
    }

    /**
     * An owner reader may return the owner itself rather than an Optional of it, but never null, since a peer without
     * an owner is an empty Optional. A null from either kind of reader is refused when the commit of the peer looks
     * its owner up: the peer's outcome carries an aborted reaction whose NullPointerException says the ownerOf
     * returned null, and the owner is not reevaluated.
     */
    @Test
    void anOwnerReaderThatReturnsNullIsRefusedWhetherItReturnsAnOptionalOrTheOwner() throws Exception {
        String plain = "@GraphProperty public Doc doc() { return orphan ? null : doc; }";
        String optional = "@GraphProperty public Optional<Doc> doc() { return orphan ? null : Optional.of(doc); }";
        for (String reader : List.of(plain, optional)) {
            Map<String, String> sources = TypedFixtures.watching();
            sources.put("fx.Page", sources.get("fx.Page").replace(
                    "@GraphProperty public Optional<Doc> doc() { return Optional.of(doc); }",
                    "public boolean orphan;\n    " + reader));
            sources.put("fx.Scenario", """
                    package fx;
                    import io.github.graphmaquina.api.Outcome;
                    import io.github.graphmaquina.api.Reaction;
                    public final class Scenario {
                        public static String run() {
                            try (Library library = LibraryRuntime.builder(new DocActions()).build()) {
                                Doc doc = new Doc("d");
                                Page page = new Page("p", doc);
                                page.orphan = true;
                                library.docs().submit(doc);
                                Outcome<Page, PageStatus, PageTransition> finished = library.pages().finish(page);
                                if (finished instanceof Outcome.Applied<?, ?, ?> applied
                                        && applied.reactions().size() == 1
                                        && applied.reactions().get(0) instanceof Reaction.Aborted aborted) {
                                    return aborted.cause().getClass().getSimpleName() + ": "
                                            + aborted.cause().getMessage() + ", " + doc.status();
                                }
                                return finished + ", " + doc.status();
                            }
                        }
                    }
                    """);

            Compilation.Result result = Compilation.compileTyped(sources);

            assertTrue(result.success(), result.describe());
            assertEquals("NullPointerException: the ownerOf of Doc/DocStatus.REVIEW returned null, REVIEW",
                    result.load("fx.Scenario").getMethod("run").invoke(null), reader);
            if (reader.equals(plain)) {
                String companion = result.generated("fx.DocsRuntime");
                assertTrue(companion.contains("(Page page) -> Optional.of(Objects.requireNonNull(page.doc(),"
                        + " \"the ownerOf of Doc/DocStatus.REVIEW returned null\"))"), companion);
            }
        }
    }

    @Test
    void leavesTheStatesOfTheOtherMachineToJavac() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = PageStatus.OPEN, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = DocStatus.PUBLISHED, transition = PUBLISH))
                """));

        assertFalse(result.success(), result.describe());
        assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("fx.PageStatus cannot be converted to fx.DocStatus")), result.describe());
    }

    @Test
    void reportsAPropertyOfAnotherEntity() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = PageRefs.Doc.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = PageStatus.DONE, transition = PUBLISH))
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG605.name()), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[" + DiagnosticCode.SG605 + "]")
                && error.contains("is a property of fx.Page, not of Doc")), result::describe);
    }

    @Test
    void reportsAConditionThatDoesNotFitTheMachineAndItsPeer() {
        Map<String, String> sources = TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(condition = DocConditionsRefs.Stale.class, transition = PUBLISH))
                """);
        sources.put("fx.DocConditions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphCondition;
                import io.github.graphmaquina.api.PeerView;
                import java.util.Collection;
                public final class DocConditions {
                    @GraphCondition
                    public boolean stale(Page page, Collection<? extends Page> pages,
                            PeerView<Page, PageStatus, PageTransition> view) {
                        return pages.isEmpty();
                    }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG505.name()), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[" + DiagnosticCode.SG505 + "]")
                && error.contains("boolean stale(Doc, Collection<? extends Page>, "
                        + "PeerView<Page, PageStatus, PageTransition>)")),
                result::describe);
    }

    @Test
    void reportsARuleWithNoAlternativeAndOneWithTwo() {
        Compilation.Result none = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(transition = PUBLISH))
                """));
        assertFalse(none.success(), none.describe());
        assertEquals(1, none.countError(DiagnosticCode.SG508.name()), none::describe);

        Compilation.Result two = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = PageStatus.DONE, noneIn = PageStatus.OPEN, transition = PUBLISH))
                """));
        assertFalse(two.success(), two.describe());
        assertEquals(1, two.countError(DiagnosticCode.SG508.name()), two::describe);
    }

    @Test
    void reportsAnAlternativeDeclaredWithMoreThanOneValue() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = {PageStatus.DONE, PageStatus.OPEN}, transition = PUBLISH))
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG607.name()), result::describe);
    }

    @Test
    void reportsAWatchWithoutRules() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = {})
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG404.name()), result::describe);
    }

    @Test
    void reportsTwoWatchesOfOneStateEvenInDifferentPairs() {
        Map<String, String> sources = notes(TypedFixtures.watching("@GraphPeers({Pages.class, Notes.class})\n",
                TypedFixtures.WATCH + """
                @DocsNotes.Watch(state = REVIEW, peers = DocRefs.Notes.class, owner = NoteRefs.Doc.class,
                    rules = @DocsNotes.When(allIn = NoteStatus.FILED, transition = PUBLISH))
                """));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG403.name()), result::describe);
    }

    @Test
    void reportsAPeerNoWatchUses() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, ""));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG608.name()), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[" + DiagnosticCode.SG608 + "]")
                && error.contains("fx.Pages is declared as a peer of Docs")), result::describe);
    }

    /**
     * A peer declared twice would generate the vocabulary of that pair twice, so the generator refuses it before
     * javac runs, with the code the processor reports it with, rather than as two declarations that claim one
     * generated type.
     */
    @Test
    void refusesAPeerDeclaredTwiceBeforeJavacRuns() {
        Map<String, String> sources = TypedFixtures.watching("@GraphPeers({Pages.class, Pages.class})\n",
                TypedFixtures.WATCH);

        CodegenException failure = assertThrows(CodegenException.class, () -> Compilation.prepare(sources));

        assertEquals(DiagnosticCode.SG608.name(), failure.code());
        assertEquals("fx.Pages is declared twice as a peer of fx.Docs", failure.getMessage());
        assertEquals("originals/fx/Docs.java", failure.location().orElseThrow().path());
    }

    /**
     * The generator refuses a peer declared twice, so the duplicate is written here after the generation, as an
     * edit between the two stages does, and the processor is the one that has to answer for it.
     */
    @Test
    void reportsAPeerDeclaredTwice() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.watching());
        rewrite(prepared, "fx.Docs", TypedFixtures.watching()
                .get("fx.Docs").replace("@GraphPeers(Pages.class)", "@GraphPeers({Pages.class, Pages.class})"));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG608.name()), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[" + DiagnosticCode.SG608 + "]")
                && error.contains("fx.Pages is declared twice as a peer of Docs")), result::describe);
    }

    /**
     * The watching fixture with a second peer machine, over notes of the same documents, so two pairs can watch one
     * state.
     */
    private static Map<String, String> notes(Map<String, String> sources) {
        Map<String, String> extended = new LinkedHashMap<>(sources);
        extended.put("fx.NoteStatus", """
                package fx;
                public enum NoteStatus { OPEN, FILED }
                """);
        extended.put("fx.NoteTransition", """
                package fx;
                public enum NoteTransition { FILE }
                """);
        extended.put("fx.Note", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                public final class Note {
                    private final String id;
                    @GraphProperty private NoteStatus status = NoteStatus.OPEN;
                    private final Doc doc;
                    public Note(String id, Doc doc) { this.id = id; this.doc = doc; }
                    @GraphProperty public String id() { return id; }
                    public NoteStatus status() { return status; }
                    public void status(NoteStatus value) { status = value; }
                    @GraphProperty public Optional<Doc> doc() { return Optional.of(doc); }
                }
                """);
        extended.put("fx.Notes", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.NoteStatus.*;
                import static fx.NoteTransition.*;
                @GenerateGraphApi
                @NotesGraph.Definition(initial = OPEN, terminals = FILED,
                    state = NoteRefs.Status.class, id = NoteRefs.Id.class)
                @NotesGraph.StatePolicy(state = OPEN, otherwise = "a note is filed")
                public interface Notes extends Machine<Note, NoteStatus, NoteTransition> {
                    @NotesGraph.Transition(id = FILE, from = OPEN, to = FILED)
                    Outcome<Note, NoteStatus, NoteTransition> file(Note note);
                }
                """);
        extended.put("fx.Doc", sources.get("fx.Doc").replace("    void attach(Page page)",
                "    private final List<Note> notes = new ArrayList<>();\n"
                        + "    @GraphProperty public Collection<Note> notes() { return notes; }\n"
                        + "    void attach(Page page)"));
        return extended;
    }

    /** Replaces one original after the generator has already read it, as an edit between the two stages does. */
    private static void rewrite(Compilation.Prepared prepared, String qualifiedName, String source) {
        Path file = prepared.request().sources().get(0)
                .resolve(qualifiedName.replace('.', File.separatorChar) + ".java");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
