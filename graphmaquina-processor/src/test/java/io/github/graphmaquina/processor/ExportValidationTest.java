package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An exported member is a published contract, so it is checked against javac's own declarations whether or not
 * a graph of this compilation uses it. Most of these fixtures declare no machine: what fails is the export
 * itself. The last two declare machines, to show what a failing export leaves of the rest of the compilation.
 */
class ExportValidationTest {

    @Test
    void refusesAMarkedMethodTheGeneratedCodeCouldNotCall() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.Hidden", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class Hidden {
                    @GraphAction private ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("publish") && error.contains("public")), result::describe);
    }

    @Test
    void refusesAnActionThatDoesNotReturnAnOutcome() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.Wrong", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.TransitionContext;
                public final class Wrong {
                    @GraphAction public String publish(TransitionContext<Doc, DocStatus, DocTransition> context) {
                        return "no";
                    }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("publish") && error.contains("ActionOutcome")), result::describe);
    }

    @Test
    void refusesAGuardThatDecidesNothing() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.Voids", """
                package fx;
                import io.github.graphmaquina.annotation.GraphGuard;
                import io.github.graphmaquina.api.GuardContext;
                public final class Voids {
                    @GraphGuard public void approved(GuardContext<Doc, DocStatus, DocTransition> context) { }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("approved") && error.contains("GuardResult")), result::describe);
    }

    @Test
    void refusesAHookThatDoesNotTakeTheEntityAlone() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.Hooks", """
                package fx;
                import io.github.graphmaquina.annotation.GraphHook;
                public final class Hooks {
                    @GraphHook public String notified(Doc doc, String note) { return note; }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("notified")), result::describe);
    }

    @Test
    void refusesAnOverrideThatContradictsTheRoleOfItsContract() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.BaseHooks", """
                package fx;
                import io.github.graphmaquina.annotation.GraphHook;
                public class BaseHooks {
                    @GraphHook public void notified(Doc doc) { }
                }
                """, "fx.SubHooks", """
                package fx;
                import io.github.graphmaquina.annotation.GraphGuard;
                public final class SubHooks extends BaseHooks {
                    @Override @GraphGuard public void notified(Doc doc) { }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("notified") && error.contains("overrides")), result::describe);
    }

    /**
     * The contract of an override that contradicts its role is a supertype of the overriding class along three paths:
     * through the superclass that implements it, and through two interfaces that extend it. It is reported once.
     */
    @Test
    void reportsAContractReachedAlongSeveralPathsOnce() {
        Map<String, String> sources = exports("fx.Notifying", """
                package fx;
                import io.github.graphmaquina.annotation.GraphHook;
                public interface Notifying {
                    @GraphHook default void notified(Doc doc) { }
                }
                """, "fx.SubHooks", """
                package fx;
                import io.github.graphmaquina.annotation.GraphGuard;
                public final class SubHooks extends BaseHooks implements Left, Right {
                    @Override @GraphGuard public void notified(Doc doc) { }
                }
                """);
        sources.put("fx.BaseHooks", "package fx;\npublic class BaseHooks implements Notifying { }\n");
        sources.put("fx.Left", "package fx;\npublic interface Left extends Notifying { }\n");
        sources.put("fx.Right", "package fx;\npublic interface Right extends Notifying { }\n");

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG604] fx.SubHooks.notified is exported as a guard, but it overrides"
                + " fx.Notifying.notified, whose contract is a hook"), result.errors().stream()
                .filter((String error) -> error.contains("overrides")).toList(), result.describe());
    }

    @Test
    void acceptsAWellFormedGenericBaseWithoutAConcreteEntity() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.BaseActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphGuard;
                import io.github.graphmaquina.annotation.GraphHook;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.GuardContext;
                import io.github.graphmaquina.api.GuardResult;
                import io.github.graphmaquina.api.TransitionContext;
                public class BaseActions<E, S extends Enum<S>, T extends Enum<T>> {
                    @GraphAction public ActionOutcome publish(TransitionContext<E, S, T> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphGuard public GuardResult approved(GuardContext<E, S, T> context) {
                        return GuardResult.pass();
                    }
                    @GraphHook public void notified(E entity) { }
                }
                """));

        assertTrue(result.success(), result.describe());
        assertEquals(0, result.countError("SG604"), result::describe);
    }

    @Test
    void refusesAMarkedGetterTheGeneratedCodeCouldNotCall() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.Shut", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Shut {
                    @GraphProperty private DocStatus status() { return DocStatus.DRAFT; }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("status") && error.contains("public instance method")), result::describe);
    }

    @Test
    void refusesAMarkedGetterThatIsNotReadFromAnEntityAtAll() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.Shared", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Shared {
                    @GraphProperty public static String id() { return "shared"; }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("id") && error.contains("public instance method")), result::describe);
    }

    @Test
    void refusesAnExportWhoseMarkerDisappearedAfterItWasGenerated() {
        Compilation.Prepared prepared = Compilation.prepare(exports("fx.Host", ACTION_HOST));
        rewriteOriginal(prepared, "fx.Host", ACTION_HOST.replace("@GraphAction ", ""));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("no longer declares as a graph member")), result::describe);
    }

    @Test
    void refusesAnExportWhoseRoleChangedAfterItWasGenerated() {
        Compilation.Prepared prepared = Compilation.prepare(exports("fx.Host", ACTION_HOST));
        rewriteOriginal(prepared, "fx.Host", ACTION_HOST.replace("@GraphAction", "@GraphGuard")
                .replace("import io.github.graphmaquina.annotation.GraphAction;", "import io.github.graphmaquina.annotation.GraphGuard;"));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("as an action, which it no longer declares")), result::describe);
    }

    /**
     * An export that does not hold stops what is generated, not what is checked: a machine that does not use it is
     * still validated, so its own problems are reported in the same build. Nothing else is generated either: neither
     * the companion of Pages, a valid machine that does not use the export, nor that of Shelf, a valid set that holds
     * Pages alone, although nothing else is wrong with them.
     */
    @Test
    void reportsTheOtherProblemsOfTheCompilationBesideAnExportThatDoesNotHold() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER
                .replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \""), Fixtures.DOCS_BODY);
        sources.put("fx.Audit", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.TransitionContext;
                public final class Audit {
                    @GraphAction public String record(TransitionContext<Doc, DocStatus, DocTransition> context) {
                        return "recorded";
                    }
                }
                """);
        sources.put("fx.Shelf", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Shelf extends Machines {
                    Pages pages();
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG604"), result.describe());
        assertEquals(1, result.countError("SG103"), result.describe());
        assertEquals(2, result.errors().size(), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.ShelfRuntime"), result.describe());
    }

    /**
     * A machine that uses the export which does not hold is not reported again where it uses it: the export's own
     * SG604 already says what is wrong. Every other machine is validated all the same.
     */
    @Test
    void doesNotRepeatAnExportThatDoesNotHoldAtItsUseButValidatesTheOtherMachines() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace(
                "@GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) { calls.add(\"publish\"); return ActionOutcome.ok(); }",
                "@GraphAction public String publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) { return \"publish\"; }"));
        sources.put("fx.Pages", Fixtures.PAGES.replace("otherwise = \"a page is finished\"", "otherwise = \" \""));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG604"), result.describe());
        assertEquals(0, result.countError("SG505"), result.describe());
        assertEquals(1, result.countError("SG103"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
    }

    /** A well-formed action host, so that only what a test changes about it can make its export invalid. */
    private static final String ACTION_HOST = """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.TransitionContext;
            public final class Host {
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> context) {
                    return ActionOutcome.ok();
                }
            }
            """;

    /** Replaces one original after the generator has read it, as an edit between the two stages does. */
    private static void rewriteOriginal(Compilation.Prepared prepared, String qualifiedName, String source) {
        try {
            Files.writeString(prepared.request().sources().get(0)
                    .resolve(qualifiedName.replace('.', File.separatorChar) + ".java"), source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The entity and its state and transition enums, which every fixture needs, plus the host under test. */
    private static Map<String, String> exports(String name, String source) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
                """);
        sources.put("fx.DocTransition", """
                package fx;
                public enum DocTransition { PUBLISH }
                """);
        sources.put("fx.Doc", """
                package fx;
                public final class Doc { }
                """);
        sources.put(name, source);
        return sources;
    }

    /** The same, for a contract and the subtype that overrides it. */
    private static Map<String, String> exports(String name, String source, String other, String otherSource) {
        Map<String, String> sources = exports(name, source);
        sources.put(other, otherSource);
        return sources;
    }
}
