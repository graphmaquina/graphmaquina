package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A symbol names one family of declarations; the role it is used as decides which member of that family the
 * generated code calls. The resolver answers with the effective declaration of the concrete host, never with
 * whatever {@code getAllMembers} happens to return first.
 */
class TypedBindingsTest {

    @Test
    void resolvesTheEffectiveOverrideOfAnInheritedConcreteHost() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.method("fx.ConcreteActionsRefs.Publish", Bindings.Role.ACTION, "fx.Doc/fx.DocStatus");

        Compilation.Result result = resolve(hosts());

        assertTrue(result.success(), result.describe());
        assertEquals("fx.ConcreteActions#fx.ConcreteActions.publish"
                        + "(io.stategraph.api.TransitionContext<fx.Doc,fx.DocStatus>)",
                TypedProcessors.Resolving.resolved("fx.ConcreteActionsRefs.Publish", Bindings.Role.ACTION)
                        .orElseThrow(() -> new AssertionError(result.describe())));
        assertEquals("host.publish(context)",
                TypedProcessors.Resolving.emitted("fx.ConcreteActionsRefs.Publish", Bindings.Role.ACTION)
                        .orElseThrow(() -> new AssertionError(result.describe())));
    }

    @Test
    void writesTheParameterTypeThatKeepsTheSelectedOverloadAndNothingMore() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.method("fx.ConditionsRefs.Ready", Bindings.Role.CONDITION,
                "fx.Doc/fx.DocStatus", "fx.Page/fx.PageStatus");
        Map<String, String> sources = competing();

        Compilation.Result result = resolve(sources);

        assertTrue(result.success(), result.describe());
        String call = TypedProcessors.Resolving.emitted("fx.ConditionsRefs.Ready", Bindings.Role.CONDITION)
                .orElseThrow(() -> new AssertionError(result.describe()));
        assertEquals("host.ready(entity, (java.lang.Iterable<? extends fx.Page>) peers, view)", call);

        Map<String, String> bound = new LinkedHashMap<>(sources);
        bound.put("fx.Bound", """
                package fx;
                import io.stategraph.api.WatchCondition;
                public final class Bound {
                    public static WatchCondition<Doc, Page, PageStatus> of(Conditions host) {
                        return (entity, peers, view) -> %s;
                    }
                }
                """.formatted(call));
        Compilation.Result compiled = Compilation.compileTyped(bound);

        assertTrue(compiled.success(), compiled.describe());
    }

    @Test
    void picksTheOverloadOfTheRequestedRoleAndKeepsAStaticOneOffTheBuilder() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.method("fx.OverloadsRefs.Run", Bindings.Role.ACTION, "fx.Doc/fx.DocStatus");
        TypedProcessors.Resolving.method("fx.OverloadsRefs.Run", Bindings.Role.GUARD, "fx.Doc/fx.DocStatus");
        TypedProcessors.Resolving.method("fx.OverloadsRefs.Ready", Bindings.Role.GUARD, "fx.Doc/fx.DocStatus");

        Compilation.Result result = resolve(hosts());

        assertTrue(result.success(), result.describe());
        assertEquals("fx.Overloads#fx.Overloads.run"
                        + "(io.stategraph.api.TransitionContext<fx.Doc,fx.DocStatus>)",
                TypedProcessors.Resolving.resolved("fx.OverloadsRefs.Run", Bindings.Role.ACTION)
                        .orElseThrow(() -> new AssertionError(result.describe())));
        assertEquals("fx.Overloads#fx.Overloads.run"
                        + "(io.stategraph.api.GuardContext<fx.Doc,fx.DocStatus>)",
                TypedProcessors.Resolving.resolved("fx.OverloadsRefs.Run", Bindings.Role.GUARD)
                        .orElseThrow(() -> new AssertionError(result.describe())));
        assertEquals("fx.Overloads#fx.Overloads.ready"
                        + "(io.stategraph.api.GuardContext<fx.Doc,fx.DocStatus>) static",
                TypedProcessors.Resolving.resolved("fx.OverloadsRefs.Ready", Bindings.Role.GUARD)
                        .orElseThrow(() -> new AssertionError(result.describe())));
    }

    @Test
    void refusesASymbolThisCompilationDoesNotExport() {
        Map<String, String> sources = hosts();
        sources.put("fx.Fake", """
                package fx;
                import io.stategraph.annotation.internal.GeneratedSymbol;
                import io.stategraph.annotation.internal.SymbolKind;
                import io.stategraph.symbol.ActionSymbol;
                @GeneratedSymbol(host = Overloads.class, member = "run", kinds = SymbolKind.ACTION)
                public final class Fake implements ActionSymbol { }
                """);
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.method("fx.Fake", Bindings.Role.ACTION, "fx.Doc/fx.DocStatus");

        Compilation.Result result = resolve(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG605"), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG605]")
                && error.contains("fx.Fake")), result::describe);
    }

    @Test
    void refusesACandidateThatDoesNotFitTheEntityOfTheMachineUsingIt() {
        TypedProcessors.Resolving.reset();
        TypedProcessors.Resolving.method("fx.OverloadsRefs.Run", Bindings.Role.ACTION, "fx.Note/fx.NoteStatus");

        Compilation.Result result = resolve(hosts());

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG505"), result::describe);
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG505]")
                && error.contains("TransitionContext<Note, NoteStatus>")), result::describe);
    }

    /** An entity, a peer, and a marked condition an unmarked overload of the same arity competes with. */
    private static Map<String, String> competing() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
                """);
        sources.put("fx.Doc", """
                package fx;
                public final class Doc { }
                """);
        sources.put("fx.PageStatus", """
                package fx;
                public enum PageStatus { OPEN, DONE }
                """);
        sources.put("fx.Page", """
                package fx;
                public final class Page { }
                """);
        sources.put("fx.Conditions", """
                package fx;
                import io.stategraph.annotation.GraphCondition;
                import io.stategraph.api.PeerView;
                import java.util.Collection;
                public final class Conditions {
                    @GraphCondition
                    public boolean ready(Doc doc, Iterable<? extends Page> pages, PeerView<Page, PageStatus> view) {
                        return true;
                    }
                    public boolean ready(Doc doc, Collection<? extends Page> pages, PeerView<Page, PageStatus> view) {
                        return false;
                    }
                }
                """);
        return sources;
    }

    /** Prepares the fixture and compiles it with the resolving processor instead of the real one. */
    private static Compilation.Result resolve(Map<String, String> sources) {
        return Compilation.compileWithoutLifecycle(Compilation.prepare(sources),
                List.of("-processor", TypedProcessors.Resolving.class.getName()));
    }

    /** Two entities with their states, a marked generic base with a concrete host, and an overload family. */
    private static Map<String, String> hosts() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
                """);
        sources.put("fx.Doc", """
                package fx;
                public final class Doc { }
                """);
        sources.put("fx.NoteStatus", """
                package fx;
                public enum NoteStatus { OPEN, CLOSED }
                """);
        sources.put("fx.Note", """
                package fx;
                public final class Note { }
                """);
        sources.put("fx.BaseActions", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                public class BaseActions<E, S extends Enum<S>> {
                    @GraphAction public ActionOutcome publish(TransitionContext<E, S> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.ConcreteActions", """
                package fx;
                import io.stategraph.annotation.GraphBindings;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                @GraphBindings
                public final class ConcreteActions extends BaseActions<Doc, DocStatus> {
                    @Override public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Overloads", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.annotation.GraphGuard;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.GuardContext;
                import io.stategraph.api.GuardResult;
                import io.stategraph.api.TransitionContext;
                public final class Overloads {
                    @GraphAction public ActionOutcome run(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphGuard public GuardResult run(GuardContext<Doc, DocStatus> context) {
                        return GuardResult.pass();
                    }
                    @GraphGuard public static GuardResult ready(GuardContext<Doc, DocStatus> context) {
                        return GuardResult.pass();
                    }
                }
                """);
        return sources;
    }
}
