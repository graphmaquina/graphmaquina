package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An exported member is a published contract, so it is checked against javac's own declarations whether or not
 * a graph of this compilation uses it. None of these fixtures declares a machine: what fails is the export
 * itself.
 */
class ExportValidationTest {

    @Test
    void refusesAMarkedMethodTheGeneratedCodeCouldNotCall() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.Hidden", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                public final class Hidden {
                    @GraphAction private ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
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
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.TransitionContext;
                public final class Wrong {
                    @GraphAction public String publish(TransitionContext<Doc, DocStatus> context) {
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
                import io.stategraph.annotation.GraphGuard;
                import io.stategraph.api.GuardContext;
                public final class Voids {
                    @GraphGuard public void approved(GuardContext<Doc, DocStatus> context) { }
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
                import io.stategraph.annotation.GraphHook;
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
                import io.stategraph.annotation.GraphHook;
                public class BaseHooks {
                    @GraphHook public void notified(Doc doc) { }
                }
                """, "fx.SubHooks", """
                package fx;
                import io.stategraph.annotation.GraphGuard;
                public final class SubHooks extends BaseHooks {
                    @Override @GraphGuard public void notified(Doc doc) { }
                }
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("notified") && error.contains("overrides")), result::describe);
    }

    @Test
    void acceptsAWellFormedGenericBaseWithoutAConcreteEntity() {
        Compilation.Result result = Compilation.compileTyped(exports("fx.BaseActions", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.annotation.GraphGuard;
                import io.stategraph.annotation.GraphHook;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.GuardContext;
                import io.stategraph.api.GuardResult;
                import io.stategraph.api.TransitionContext;
                public class BaseActions<E, S extends Enum<S>> {
                    @GraphAction public ActionOutcome publish(TransitionContext<E, S> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphGuard public GuardResult approved(GuardContext<E, S> context) {
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
                import io.stategraph.annotation.GraphProperty;
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
                import io.stategraph.annotation.GraphProperty;
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
                .replace("import io.stategraph.annotation.GraphAction;", "import io.stategraph.annotation.GraphGuard;"));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG604]")
                && error.contains("as an action, which it no longer declares")), result::describe);
    }

    /** A well-formed action host, so that only what a test changes about it can make its export invalid. */
    private static final String ACTION_HOST = """
            package fx;
            import io.stategraph.annotation.GraphAction;
            import io.stategraph.api.ActionOutcome;
            import io.stategraph.api.TransitionContext;
            public final class Host {
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
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

    /** The entity and its states, which every fixture needs, plus the host whose exports are under test. */
    private static Map<String, String> exports(String name, String source) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
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
