package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies how Definition's default afterCommit hook is inherited, replaced and validated for every edge. */
class DefaultAfterCommitTest {
    private static final String DEFAULT = ", afterCommit = RecorderRefs.Persist.class";
    private static final String EXPLICIT = ", afterCommit = DocActionsRefs.Notified.class";

    private static long hooks(Compilation.Result result, String name) {
        return result.generated("fx.DocsRuntime").lines()
            .filter(line -> line.contains(".afterCommit(\"" + name + "\""))
            .count();
    }

    @Test void omittedAndEmptyDefinitionKeepExistingBehavior() {
        for (String value : new String[] {"", ", afterCommit = {}"}) {
            Compilation.Result result = Compilation.compileTyped(DefaultHookFixtures.sources(value, ""));
            assertTrue(result.success(), result::describe);
            assertEquals(0, hooks(result, "persist"));
            assertFalse(result.generated("fx.LibraryRuntime").contains("Recorder"));
        }
    }

    @Test void olderVocabularyWithoutDefaultHookKeepsExistingBehavior() throws IOException {
        Map<String, String> sources = DefaultHookFixtures.sources("", EXPLICIT);
        Compilation.Result current = Compilation.compileTyped(sources);
        assertTrue(current.success(), current::describe);
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Path vocabulary = prepared.request().sourcesOutput().resolve("fx/DocsGraph.java");
        String generated = Files.readString(vocabulary);
        String member = "Class<? extends HookSymbol>[] afterCommit() default {};";
        int definition = generated.indexOf("@interface Definition {");
        assertTrue(definition >= 0, generated);
        int at = generated.indexOf(member, definition);
        assertTrue(at >= 0, generated);
        // Remove only Definition's new element; the per-edge hook elements remain available.
        Files.writeString(vocabulary, generated.substring(0, at) + generated.substring(at + member.length()));

        Compilation.Result legacy = Compilation.compilePrepared(prepared);
        assertTrue(legacy.success(), legacy::describe);
        assertEquals(1, hooks(legacy, "notified"));
        assertEquals(current.generated("fx.DocsRuntime"), legacy.generated("fx.DocsRuntime"));
        assertEquals(current.generated("fx.LibraryRuntime"), legacy.generated("fx.LibraryRuntime"));
    }

    @Test void defaultReachesEveryDeclaredEdgeAndItsDependency() {
        Compilation.Result result = Compilation.compileTyped(DefaultHookFixtures.sources(DEFAULT, ""));
        assertTrue(result.success(), result::describe);
        assertEquals(3, hooks(result, "persist"));
        assertTrue(result.generated("fx.LibraryRuntime")
            .contains("public static Builder builder(Recorder recorder, DocActions docActions)"));
        // Only a declared edge carries a hook: nothing else in the built graph may.
        assertFalse(result.generated("fx.DocsRuntime").lines()
            .anyMatch(line -> line.contains(".afterCommit(") && !line.contains(".transition(")));
    }

    @Test void explicitActionReplacesDefaultAndEmptyInheritsIt() {
        Compilation.Result override = Compilation.compileTyped(DefaultHookFixtures.sources(DEFAULT, EXPLICIT));
        assertTrue(override.success(), override::describe);
        assertEquals(2, hooks(override, "persist"));
        assertEquals(1, hooks(override, "notified"));
        Compilation.Result empty = Compilation.compileTyped(DefaultHookFixtures.sources(DEFAULT, ", afterCommit = {}"));
        assertTrue(empty.success(), empty::describe);
        assertEquals(3, hooks(empty, "persist"));
    }

    @Test void internalActionAndMultipleFromStatesInherit() {
        Map<String, String> sources = DefaultHookFixtures.sources(DEFAULT, "");
        sources.compute("fx.Docs", (key, text) -> text
            .replace("@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)",
                "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT, action = DocActionsRefs.Publish.class)")
            .replace("@DocsGraph.Transition(id = PUBLISH, from = REVIEW,",
                "@DocsGraph.Transition(id = PUBLISH, from = {DRAFT, REVIEW},"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        assertEquals(4, hooks(result, "persist"));
    }

    @Test void explicitMovesAndInternalEdgesReplaceDefaultWithoutUnusedInjection() {
        Map<String, String> sources = DefaultHookFixtures.sources(DEFAULT, EXPLICIT);
        sources.compute("fx.Docs", (key, text) -> text
            .replace("@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)",
                "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT" + EXPLICIT + ")")
            .replace("@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)",
                "@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW" + EXPLICIT + ")"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        assertEquals(0, hooks(result, "persist"));
        assertEquals(3, hooks(result, "notified"));
        assertFalse(result.generated("fx.LibraryRuntime").contains("Recorder"));
        // Even an entirely overridden default must be validated at its declaration.
        sources.compute("fx.Recorder", (key, text) -> text.replace("persist(Doc doc)", "persist(String doc)"));
        Compilation.Result invalid = Compilation.compileTyped(sources);
        assertFalse(invalid.success());
        assertTrue(invalid.hasError("SG505"), invalid::describe);
        assertFalse(invalid.hasGenerated("fx.DocsRuntime"));
    }

    @Test void staticDefaultNeedsNoRecorderInstance() {
        Map<String, String> sources = DefaultHookFixtures.sources(DEFAULT, "");
        sources.compute("fx.Recorder", (key, text) -> text.replace("public void persist", "public static void persist"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        assertEquals(3, hooks(result, "persist"));
        assertTrue(result.generated("fx.LibraryRuntime")
            .contains("public static Builder builder(DocActions docActions)"));
    }

    @Test void rejectsMultipleDefaultsAndAnchorsTheDeclaration() {
        Compilation.Result result = Compilation.compileTyped(DefaultHookFixtures.sources(
            ", afterCommit = {RecorderRefs.Persist.class, RecorderRefs.Persist.class}", ""));
        assertFalse(result.success());
        assertEquals(1, result.countError("SG607"), result::describe);
        assertEquals(10, result.lineOf("SG607"), result::describe);
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
    }

    @Test void rejectsForgedHookSymbols() {
        Map<String, String> sources = DefaultHookFixtures.sources(", afterCommit = Fake.class", "");
        sources.put("fx.Fake", """
            package fx;
            public final class Fake implements io.github.graphmaquina.symbol.HookSymbol { }
            """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success());
        assertTrue(result.hasError("SG605"), result::describe);
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
    }

    @Test void rejectsAmbiguousDefaultOverloadsOnceAtTheDefinition() {
        Map<String, String> sources = DefaultHookFixtures.sources(DEFAULT, "");
        sources.compute("fx.Recorder", (key, text) -> text.replace(
            "@GraphHook public void persist(Doc doc) { }", """
            @GraphHook public void persist(Doc doc) { }
            @GraphHook public void persist(Doc doc, io.github.graphmaquina.api.CommitContext<DocStatus, DocTransition> commit) { }
            """));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success());
        assertEquals(1, result.countError("SG505"), result::describe);
        assertEquals(10, result.lineOf("SG505"), result::describe);
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
        assertFalse(result.hasGenerated("fx.LibraryRuntime"));
    }

    /** An explicit self-loop is a real edge, so it commits and runs exactly the one hook in force on it. */
    @Test void explicitSelfLoopCarriesOneEffectiveHook() {
        Map<String, String> sources = DefaultHookFixtures.sources(DEFAULT, "");
        sources.compute("fx.Docs", (key, text) -> text.replace(
            "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)",
            "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = REVIEW)"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        String selfLoop = result.generated("fx.DocsRuntime").lines()
            .filter(line -> line.contains(".transition(DocTransition.RETURN, DocStatus.REVIEW, DocStatus.REVIEW)"))
            .findFirst().orElseThrow(() -> new AssertionError(result.generated("fx.DocsRuntime")));
        assertEquals(1, selfLoop.split("\\.afterCommit\\(", -1).length - 1, selfLoop);
        assertTrue(selfLoop.contains(".afterCommit(\"persist\""), selfLoop);
    }

    @Test void invalidExplicitHookDoesNotFallBackSilently() {
        Compilation.Result result = Compilation.compileTyped(DefaultHookFixtures.sources(DEFAULT,
            ", afterCommit = {DocActionsRefs.Notified.class, DocActionsRefs.Notified.class}"));
        assertFalse(result.success());
        assertTrue(result.hasError("SG607"), result::describe);
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
    }
}
