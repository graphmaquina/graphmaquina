package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        String companion = result.generated("fx.Docs_");
        assertTrue(companion.contains(".watches(MachineKey.of(Page.class, PageStatus.class),"
                + " (Doc doc) -> doc.pages(), (Page page) -> page.doc())"), companion);
        int condition = companion.indexOf(".when(\"ready\", (doc, peers, view) -> docActions.ready(");
        int filter = companion.indexOf(".when(WatchCondition.allIn(PageStatus.DONE)).goTo(DocStatus.PUBLISHED)");
        assertTrue(condition > 0 && filter > condition, companion);
    }

    @Test
    void leavesTheStatesOfTheOtherMachineToJavac() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = PageStatus.OPEN, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = DocStatus.PUBLISHED, goTo = PUBLISHED))
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
                    rules = @DocsPages.When(allIn = PageStatus.DONE, goTo = PUBLISHED))
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG605"), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG605]")
                && error.contains("is a property of fx.Page, not of Doc")), result::describe);
    }

    @Test
    void reportsAConditionThatDoesNotFitTheMachineAndItsPeer() {
        Map<String, String> sources = TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(condition = DocConditionsRefs.Stale.class, goTo = PUBLISHED))
                """);
        sources.put("fx.DocConditions", """
                package fx;
                import io.stategraph.annotation.GraphCondition;
                import io.stategraph.api.PeerView;
                import java.util.Collection;
                public final class DocConditions {
                    @GraphCondition
                    public boolean stale(Page page, Collection<? extends Page> pages,
                            PeerView<Page, PageStatus> view) {
                        return pages.isEmpty();
                    }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG505"), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG505]")
                && error.contains("boolean stale(Doc, Collection<? extends Page>, PeerView<Page, PageStatus>)")),
                result::describe);
    }

    @Test
    void reportsARuleWithNoAlternativeAndOneWithTwo() {
        Compilation.Result none = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(goTo = PUBLISHED))
                """));
        assertFalse(none.success(), none.describe());
        assertEquals(1, none.countError("SG508"), none::describe);

        Compilation.Result two = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = PageStatus.DONE, noneIn = PageStatus.OPEN, goTo = PUBLISHED))
                """));
        assertFalse(two.success(), two.describe());
        assertEquals(1, two.countError("SG508"), two::describe);
    }

    @Test
    void reportsAnAlternativeDeclaredWithMoreThanOneValue() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = {PageStatus.DONE, PageStatus.OPEN}, goTo = PUBLISHED))
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG607"), result::describe);
    }

    @Test
    void reportsAWatchWithoutRules() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = {})
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG404"), result::describe);
    }

    @Test
    void reportsTwoWatchesOfOneStateEvenInDifferentPairs() {
        Map<String, String> sources = notes(TypedFixtures.watching("@GraphPeers({Pages.class, Notes.class})\n",
                TypedFixtures.WATCH + """
                @DocsNotes.Watch(state = REVIEW, peers = DocRefs.Notes.class, owner = NoteRefs.Doc.class,
                    rules = @DocsNotes.When(allIn = NoteStatus.FILED, goTo = PUBLISHED))
                """));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG403"), result::describe);
    }

    @Test
    void reportsAPeerNoWatchUses() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.watching(TypedFixtures.PEERS, ""));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG608"), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG608]")
                && error.contains("fx.Pages is declared as a peer of Docs")), result::describe);
    }

    /**
     * A peer declared twice generates the vocabulary of that pair twice, which the generator itself refuses
     * before javac runs; the duplicate is written here after the generation, so the processor is the one that
     * has to answer for it.
     */
    @Test
    void reportsAPeerDeclaredTwice() {
        Compilation.Prepared prepared = Compilation.prepare(TypedFixtures.watching());
        rewrite(prepared, "fx.Docs", TypedFixtures.watching()
                .get("fx.Docs").replace("@GraphPeers(Pages.class)", "@GraphPeers({Pages.class, Pages.class})"));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG608"), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG608]")
                && error.contains("fx.Pages is declared twice as a peer of Docs")), result::describe);
    }

    /** The watching fixture with a second machine over the same documents, so two pairs can watch one state. */
    private static Map<String, String> notes(Map<String, String> sources) {
        Map<String, String> extended = new LinkedHashMap<>(sources);
        extended.put("fx.NoteStatus", """
                package fx;
                public enum NoteStatus { OPEN, FILED }
                """);
        extended.put("fx.Note", """
                package fx;
                import io.stategraph.annotation.GraphProperty;
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
                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import static fx.NoteStatus.*;
                @GenerateGraphApi
                @NotesGraph.Definition(initial = OPEN, terminals = FILED,
                    state = NoteRefs.Status.class, id = NoteRefs.Id.class)
                @NotesGraph.Row(state = OPEN, otherwise = "a note is filed")
                public interface Notes extends Machine<Note, NoteStatus> {
                    @NotesGraph.Move(from = OPEN, to = FILED)
                    Outcome<Note, NoteStatus> file(Note note);
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
