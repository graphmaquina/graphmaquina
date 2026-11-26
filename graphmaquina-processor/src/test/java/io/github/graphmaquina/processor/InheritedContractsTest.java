package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The contracts a host's methods inherit from its supertypes, pinned whole: a role an override takes from the contract
 * it overrides two classes up, or from an interface, in the companion that binds it, byte for byte, as
 * {@link PinnedCompanions} keeps it; and every contract an override contradicts, reported nearest supertype first,
 * each supertype once, in the order of a walk that takes the superclass before the interfaces at every level.
 */
class InheritedContractsTest {

    /**
     * The generic base whose action and hook {@code DocActions} overrides, the action two classes up, and whose hook
     * the class between them overrides without a marker.
     */
    private static final String ROOT_ACTIONS = """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphHook;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.TransitionContext;
            public class RootActions<E, S extends Enum<S>, T extends Enum<T>> {
                @GraphAction public ActionOutcome publish(TransitionContext<E, S, T> context) {
                    return ActionOutcome.ok();
                }
                @GraphHook public void notified(E entity) { }
            }
            """;

    /** The interface whose guard {@code DocActions} implements without a marker. */
    private static final String GUARDING = """
            package fx;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            public interface Guarding<E, S extends Enum<S>, T extends Enum<T>> {
                @GraphGuard GuardResult approved(GuardContext<E, S, T> context);
            }
            """;

    private static final String MIDDLE_ACTIONS = """
            package fx;
            public abstract class MiddleActions<E, S extends Enum<S>, T extends Enum<T>>
                    extends RootActions<E, S, T> implements Guarding<E, S, T> {
                @Override public void notified(E entity) { }
            }
            """;

    private static final String DOC_ACTIONS = """
            package fx;
            import io.github.graphmaquina.annotation.GraphBindings;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.TransitionContext;
            @GraphBindings
            public final class DocActions extends MiddleActions<Doc, DocStatus, DocTransition> {
                @Override public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> context) {
                    return ActionOutcome.ok();
                }
                @Override public GuardResult approved(GuardContext<Doc, DocStatus, DocTransition> context) {
                    return GuardResult.pass();
                }
            }
            """;

    @Test
    void bindsTheRolesAHostInheritsThroughItsSuperclassesAndItsInterfaces() throws IOException {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.RootActions", ROOT_ACTIONS);
        sources.put("fx.Guarding", GUARDING);
        sources.put("fx.MiddleActions", MIDDLE_ACTIONS);
        sources.put("fx.DocActions", DOC_ACTIONS);
        sources.put("fx.Docs", sources.get("fx.Docs").replace("action = DocActionsRefs.Publish.class)",
                "action = DocActionsRefs.Publish.class,\n        guards = DocActionsRefs.Approved.class,"
                + " afterCommit = DocActionsRefs.Notified.class)"));

        PinnedCompanions.assertGenerated("contracts", Compilation.compileTyped(sources));
    }

    /**
     * {@code SubHooks.notified} overrides three contracts of other roles: the one of {@code RootHooks}, the superclass
     * of its superclass, the one of {@code Right}, an interface of its superclass, and the one of {@code Notifying},
     * which its own interface extends. Each marked declaration is checked on its own, so the ones whose signature
     * does not suit their role are reported too.
     */
    @Test
    void reportsTheContractsAnOverrideContradictsNearestSupertypeFirst() {
        Compilation.Result result = Compilation.compileTyped(contradictingHooks());

        assertFalse(result.success(), result.describe());
        assertEquals(List.of(
                "ERROR Notifying.java:4:5 [" + DiagnosticCode.SG604 + "] fx.Notifying.notified does not have the"
                        + " signature of a condition: expected boolean from the entity, a supertype of Collection and a"
                        + " PeerView",
                "ERROR RootHooks.java:4:5 [" + DiagnosticCode.SG604 + "] fx.RootHooks.notified does not have the"
                        + " signature of an action: expected ActionOutcome or CompletionStage<ActionOutcome> from one"
                        + " TransitionContext",
                "ERROR SubHooks.java:4:15 [" + DiagnosticCode.SG604 + "] fx.SubHooks.notified does not have the"
                        + " signature of a guard: expected GuardResult or CompletionStage<GuardResult> from one"
                        + " GuardContext",
                "ERROR SubHooks.java:4:15 [" + DiagnosticCode.SG604 + "] fx.SubHooks.notified is exported as a guard,"
                        + " but it overrides fx.RootHooks.notified, whose contract is an action",
                "ERROR SubHooks.java:4:15 [" + DiagnosticCode.SG604 + "] fx.SubHooks.notified is exported as a guard,"
                        + " but it overrides fx.Right.notified, whose contract is a hook",
                "ERROR SubHooks.java:4:15 [" + DiagnosticCode.SG604 + "] fx.SubHooks.notified is exported as a guard,"
                        + " but it overrides fx.Notifying.notified, whose contract is a condition"),
                result.located(), result.describe());
    }

    /** The hosts of {@link #reportsTheContractsAnOverrideContradictsNearestSupertypeFirst}, and their domain. */
    private static Map<String, String> contradictingHooks() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, PUBLISHED }\n");
        sources.put("fx.DocTransition", "package fx;\npublic enum DocTransition { PUBLISH }\n");
        sources.put("fx.Doc", "package fx;\npublic final class Doc { }\n");
        sources.put("fx.RootHooks", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class RootHooks {
                    @GraphAction public void notified(Doc doc) { }
                }
                """);
        sources.put("fx.Right", """
                package fx;
                import io.github.graphmaquina.annotation.GraphHook;
                public interface Right {
                    @GraphHook default void notified(Doc doc) { }
                }
                """);
        sources.put("fx.BaseHooks", "package fx;\npublic class BaseHooks extends RootHooks implements Right { }\n");
        sources.put("fx.Notifying", """
                package fx;
                import io.github.graphmaquina.annotation.GraphCondition;
                public interface Notifying {
                    @GraphCondition default void notified(Doc doc) { }
                }
                """);
        sources.put("fx.Left", "package fx;\npublic interface Left extends Notifying { }\n");
        sources.put("fx.SubHooks", """
                package fx;
                import io.github.graphmaquina.annotation.GraphGuard;
                public final class SubHooks extends BaseHooks implements Left {
                    @Override @GraphGuard public void notified(Doc doc) { }
                }
                """);
        return sources;
    }
}
