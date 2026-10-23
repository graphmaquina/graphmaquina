package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A host marked {@code @GraphBindings} exports the marked static methods it inherits, and the companion calls a
 * static binding through that host, so the method the call reaches is the method the symbol exports. A static method
 * the host does not inherit is not exported through it: one an unmarked method of the host hides, whose symbol is
 * then javac's to refuse as any unknown name is, and one of an interface, which only the interface's own symbol
 * exports. A marked method that hides another is the one exported and called. A hidden method the generator cannot
 * tell is hidden, because its parameter is a type variable whose bound only javac knows, is refused as an export
 * that does not hold.
 */
class InheritedStaticBindingsTest {

    /** The base whose marked static action records that it ran. */
    private static final String BASE = """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.TransitionContext;
            public class BaseActions {
                public static String ran = "none";
                @GraphAction public static ActionOutcome archive(TransitionContext<Doc, DocStatus, DocTransition> \
            context) {
                    ran = "BaseActions.archive";
                    return ActionOutcome.ok();
                }
            }
            """;

    /** The host, which hides the base's action with one of its own, marked as {@code %s} spells. */
    private static final String HOST = """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphBindings;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.TransitionContext;
            @GraphBindings public final class Host extends BaseActions {
                %s public static ActionOutcome archive(TransitionContext<Doc, DocStatus, DocTransition> context) {
                    ran = "Host.archive";
                    return ActionOutcome.ok();
                }
                @GraphAction public static ActionOutcome keep(TransitionContext<Doc, DocStatus, DocTransition> \
            context) {
                    return ActionOutcome.ok();
                }
            }
            """;

    /** Publishes a document through the companion and answers which action ran. */
    private static final String SCENARIO = """
            package fx;
            import io.github.graphmaquina.runtime.StateMachines;
            public final class Scenario {
                private Scenario() { }
                public static String run() {
                    Docs docs = new DocsRuntime(StateMachines.from(DocsRuntime.graph())
                            .state((Doc doc) -> doc.status(), \
            (Doc doc, DocStatus status) -> doc.status(status)).build());
                    docs.publish(new Doc());
                    return BaseActions.ran;
                }
            }
            """;

    @Test
    void callsTheMarkedStaticMethodThatHidesAnother() throws Exception {
        Map<String, String> sources = machine("action = HostRefs.Archive.class", "");
        sources.put("fx.BaseActions", BASE);
        sources.put("fx.Host", HOST.formatted("@GraphAction"));
        sources.put("fx.Scenario", SCENARIO);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertEquals("Host.archive", result.load("fx.Scenario").getMethod("run").invoke(null));
    }

    @Test
    void doesNotExportAStaticMethodTheHostHidesWithAnUnmarkedOne() {
        Map<String, String> sources = machine("action = HostRefs.Archive.class", "");
        sources.put("fx.BaseActions", BASE);
        sources.put("fx.Host", HOST.formatted(""));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(List.of(), sgErrors(result), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("cannot find symbol")
                && error.contains("class Archive")), result.describe());
    }

    @Test
    void exportsAStaticMethodOfAnInterfaceOnlyThroughTheInterface() throws Exception {
        Map<String, String> hooks = Map.of("fx.Hooks", """
                package fx;
                import io.github.graphmaquina.annotation.GraphHook;
                public interface Hooks {
                    @GraphHook static void announced(Doc doc) {
                        BaseActions.ran = "Hooks.announced";
                    }
                }
                """);
        String host = """
                package fx;
                import io.github.graphmaquina.annotation.GraphBindings;
                @GraphBindings public final class Host extends BaseActions implements Hooks { }
                """;
        Map<String, String> throughHost = machine("action = BaseActionsRefs.Archive.class",
                ", afterCommit = HostRefs.Announced.class");
        throughHost.putAll(hooks);
        throughHost.put("fx.BaseActions", BASE);
        throughHost.put("fx.Host", host);
        Map<String, String> throughInterface = machine("action = BaseActionsRefs.Archive.class",
                ", afterCommit = HooksRefs.Announced.class");
        throughInterface.putAll(hooks);
        throughInterface.put("fx.BaseActions", BASE);
        throughInterface.put("fx.Host", host);
        throughInterface.put("fx.Scenario", SCENARIO);

        Compilation.Result refused = Compilation.compileTyped(throughHost);
        Compilation.Result accepted = Compilation.compileTyped(throughInterface);

        assertFalse(refused.success(), refused.describe());
        assertEquals(List.of(), sgErrors(refused), refused.describe());
        assertTrue(refused.errors().stream().anyMatch((String error) -> error.contains("cannot find symbol")
                && error.contains("class Announced")), refused.describe());
        assertTrue(accepted.success(), accepted.describe());
        assertEquals("Hooks.announced", accepted.load("fx.Scenario").getMethod("run").invoke(null));
    }

    @Test
    void refusesAHiddenStaticMethodWhoseParameterIsATypeVariable() {
        Map<String, String> sources = machine("action = DocActionsRefs.Publish.class", "");
        sources.put("fx.BaseActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public class BaseActions {
                    @GraphAction
                    public static <C extends TransitionContext<Doc, DocStatus, DocTransition>> ActionOutcome \
                archive(C context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Host", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                @GraphBindings public final class Host extends BaseActions {
                    public static <X extends TransitionContext<Doc, DocStatus, DocTransition>> ActionOutcome \
                archive(X context) {
                        return ActionOutcome.ok();
                    }
                    @GraphAction public static ActionOutcome keep(TransitionContext<Doc, DocStatus, DocTransition> \
                context) {
                        return ActionOutcome.ok();
                    }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(List.of("[SG604] fx.HostRefs.Archive exports fx.BaseActions.archive through fx.Host, which"
                + " does not inherit it: fx.Host.archive hides it"), sgErrors(result), result.describe());
    }

    /**
     * The smallest machine, whose one edge runs the action {@code action} names, with {@code extras} appended to
     * its declaration.
     */
    private static Map<String, String> machine(String action, String extras) {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Docs", sources.get("fx.Docs").replace("action = DocActionsRefs.Publish.class",
                action + extras));
        return sources;
    }

    /** The errors of {@code result} that carry an SG code. */
    private static List<String> sgErrors(Compilation.Result result) {
        return result.errors().stream().filter((String error) -> error.startsWith("[SG")).toList();
    }
}
