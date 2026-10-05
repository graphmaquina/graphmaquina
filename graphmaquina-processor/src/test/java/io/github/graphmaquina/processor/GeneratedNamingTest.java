package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * The names the processor gives what it generates, and the ones it refuses. A facade or a set named with
 * underscores, such as {@code _Docs}, gets a companion named from its PascalCase form, {@code DocsRuntime}, and the
 * interface the author wrote is left as it is; an interface whose name normalizes to nothing, the second of two
 * interfaces that normalize to one companion and an interface whose companion name a type already has are refused
 * as {@code SG603}, which withholds only what depends on that interface, while every declaration of the round is
 * still checked. In the unnamed package the companions live there too, and a qualified name of one of its types
 * starts with a type, never with a package. A type name that decapitalizes to a keyword still names a legal
 * parameter.
 */
class GeneratedNamingTest {
    private static final String LIBRARY = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Library extends Machines { Docs docs(); }
            """;

    /** {@code Hosts.Payments}, a host of the unnamed package whose instance action {@code charge} pays an order. */
    private static final String UNNAMED_HOSTS = """
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.TransitionContext;
            public final class Hosts {
                public static final class Payments {
                    @GraphAction public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
            }
            """;

    @Test
    void machineAndSetNamesNormalizeWithoutChangingTheirHandwrittenInterfaces() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx._Docs", sources.remove("fx.Docs").replace("interface Docs ", "interface _Docs "));
        sources.put("fx._Library", LIBRARY.replace("interface Library ", "interface _Library ").replace("Docs docs()", "_Docs docs()"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains("implements _Docs"));
        assertTrue(result.generated("fx.LibraryRuntime").contains("implements _Library"));
        assertFalse(result.hasGenerated("fx._Docs_"));
    }

    /**
     * Two sets whose names normalize to one companion cannot share it silently. javac hands the declarations of a
     * round over in the order of their sources, which the harness sorts, so {@code fx.Library} claims the name
     * first: its companion is written, and {@code fx._Library} is refused where it is declared, naming both.
     */
    @Test
    void normalizedSetsCannotSilentlyShareOneRuntime() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Library", LIBRARY);
        sources.put("fx._Library", LIBRARY.replace("interface Library ", "interface _Library "));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertEquals(List.of("[SG603] the generated runtime fx.LibraryRuntime is claimed by both fx.Library and"
                + " fx._Library"), result.errors(), result.describe());
        Diagnostic<? extends JavaFileObject> refusal = result.diagnostics().stream()
                .filter((Diagnostic<? extends JavaFileObject> diagnostic) ->
                        diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .findFirst().orElseThrow();
        assertTrue(refusal.getSource().getName().endsWith("_Library.java"), refusal.getSource().getName());
        String companion = result.generated("fx.LibraryRuntime");
        assertTrue(companion.contains("public final class LibraryRuntime implements Library"), companion);
    }

    @Test
    void handwrittenRuntimeNameIsReportedAsCollision() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.DocsRuntime", "package fx; public class DocsRuntime {}");
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG603"), result.describe());
        assertTrue(result.describe().contains("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"));
    }

    /**
     * A refused companion name ends nothing but that companion: the round still reports a declaration of the retired
     * vocabulary, a machine and a set with problems of their own, each once.
     */
    @Test
    void aRefusedRuntimeNameDoesNotHideTheOtherProblemsOfItsRound() {
        Map<String, String> sources = Fixtures.withPages(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \""),
                Fixtures.DOCS_BODY);
        sources.put("fx.PagesRuntime", "package fx;\npublic final class PagesRuntime { }\n");
        sources.put("io.github.graphmaquina.annotation.Row", """
                package io.github.graphmaquina.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.TYPE)
                public @interface Row { }
                """);
        sources.put("fx.Sheets", "package fx;\n@io.github.graphmaquina.annotation.Row\npublic interface Sheets { }\n");
        sources.put("fx.Shelf", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Shelf extends Machines {
                    String label();
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertEquals(1, result.countError("SG103"), result.describe());
        assertEquals(1, result.countError("SG609"), result.describe());
        assertEquals(1, result.countError("SG510"), result.describe());
        assertEquals(4, result.errors().size(), result.describe());
    }

    /**
     * A refused companion name withholds only what depends on it: that companion, and the companion of a set that
     * holds its machine and would build it with it. A machine that watches the refused one names its types, never
     * its companion, so it is generated like every other companion of the round.
     */
    @Test
    void aRefusedRuntimeNameWithholdsOnlyWhatDependsOnIt() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, Fixtures.LIBRARY);
        sources.put("fx.PagesRuntime", "package fx;\npublic final class PagesRuntime { }\n");

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG603] the generated runtime fx.PagesRuntime for fx.Pages conflicts with existing type"
                + " fx.PagesRuntime"), result.errors(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"), result.describe());
    }

    /** The machine whose companion name is refused is still checked, so its other problems are reported with it. */
    @Test
    void aMachineWhoseRuntimeNameIsRefusedIsStillChecked() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Pages", Fixtures.PAGES.replace("otherwise = \"a page is finished\"", "otherwise = \" \""));
        sources.put("fx.PagesRuntime", "package fx;\npublic final class PagesRuntime { }\n");

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertEquals(1, result.countError("SG103"), result.describe());
        assertEquals(2, result.errors().size(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * So is a set whose companion name is refused, even one whose name has no PascalCase form at all: an accessor
     * whose signature is the static {@code builder()} its companion would declare is reported beside the name.
     */
    @Test
    void aSetWhoseRuntimeNameIsRefusedIsStillChecked() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.__", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface __ extends Machines {
                    Pages builder();
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertEquals(1, result.countError("SG510"), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains(
                "[SG510] builder() is the static builder() the companion of __ declares")), result.describe());
        assertEquals(2, result.errors().size(), result.describe());
    }

    /**
     * The generator supports a machine in the unnamed package, so its companions live there too: a source of
     * the unnamed package declares no package at all, and its types are named without a qualifier.
     */
    @Test
    void aMachineAndASetInTheUnnamedPackageCompileAndRun() throws Exception {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("OrderStatus", "public enum OrderStatus { NEW, PAID }\n");
        sources.put("OrderTransition", "public enum OrderTransition { PAY }\n");
        sources.put("Order", """
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Order {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    @GraphProperty public String id() { return "o1"; }
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                }
                """);
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = OrderStatus.NEW, terminals = OrderStatus.PAID,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = OrderStatus.NEW, otherwise = "a new order is paid")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = OrderTransition.PAY, from = OrderStatus.NEW, to = OrderStatus.PAID)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);
                }
                """);
        sources.put("Shop", """
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Shop extends Machines { Orders orders(); }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertFalse(result.generated("OrdersRuntime").contains("package "), result.generated("OrdersRuntime"));
        assertFalse(result.generated("ShopRuntime").contains("package "), result.generated("ShopRuntime"));
        Object builder = result.load("ShopRuntime").getMethod("builder").invoke(null);
        try (io.github.graphmaquina.api.Machines shop =
                (io.github.graphmaquina.api.Machines) builder.getClass().getMethod("build").invoke(builder)) {
            Object orders = shop.getClass().getMethod("orders").invoke(shop);
            Object order = result.load("Order").getConstructor().newInstance();
            orders.getClass().getMethod("pay", result.load("Order")).invoke(orders, order);
            assertTrue(order.getClass().getMethod("status").invoke(order).toString().equals("PAID"));
        }
    }

    /**
     * The names the companions give their parameters come from type names, and an everyday type name can
     * decapitalize to a keyword: an entity {@code Case}, an action host {@code Default}, a peer entity
     * {@code Interface}. Each one still gets a legal name of its own.
     */
    @Test
    void typeNamesThatDecapitalizeToKeywordsStillNameLegalParameters() {
        Compilation.Result result = Compilation.compileTyped(keywordNamedDomain());

        assertTrue(result.success(), result.describe());
        String cases = result.generated("fx.CasesRuntime");
        assertTrue(cases.contains("public static GraphDefinition<Case, CaseStatus, CaseTransition> graph(Default default2)"), cases);
        assertTrue(cases.contains("close(Case caseEntity)"), cases);
        assertTrue(cases.contains("(Interface interface2) -> interface2.owner()"), cases);
    }

    /**
     * A watching machine whose entity, action host and peer entity are named {@code Case}, {@code Default} and
     * {@code Interface}, and a set holding both machines.
     */
    private static Map<String, String> keywordNamedDomain() {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("fx.CaseStatus", "package fx;\npublic enum CaseStatus { OPEN, CLOSED }\n");
        sources.put("fx.CaseTransition", "package fx;\npublic enum CaseTransition { CLOSE }\n");
        sources.put("fx.InterfaceStatus", "package fx;\npublic enum InterfaceStatus { OPEN, DONE }\n");
        sources.put("fx.InterfaceTransition", "package fx;\npublic enum InterfaceTransition { FINISH }\n");
        sources.put("fx.Case", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.ArrayList;
                import java.util.List;
                public final class Case {
                    @GraphProperty private CaseStatus status = CaseStatus.OPEN;
                    private final List<Interface> parts = new ArrayList<>();
                    @GraphProperty public String id() { return "case"; }
                    public CaseStatus status() { return status; }
                    public void status(CaseStatus value) { status = value; }
                    @GraphProperty public List<Interface> parts() { return parts; }
                }
                """);
        sources.put("fx.Interface", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                public final class Interface {
                    @GraphProperty private InterfaceStatus status = InterfaceStatus.OPEN;
                    @GraphProperty public String id() { return "interface"; }
                    public InterfaceStatus status() { return status; }
                    public void status(InterfaceStatus value) { status = value; }
                    @GraphProperty public Optional<Case> owner() { return Optional.empty(); }
                }
                """);
        sources.put("fx.Default", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphCondition;
                import io.github.graphmaquina.annotation.GraphHook;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.PeerView;
                import io.github.graphmaquina.api.TransitionContext;
                import java.util.Collection;
                public final class Default {
                    @GraphAction public ActionOutcome close(TransitionContext<Case, CaseStatus, CaseTransition> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphHook public void closed(Case closed) { }
                    @GraphCondition public boolean done(Case owner, Collection<? extends Interface> parts,
                            PeerView<Interface, InterfaceStatus, InterfaceTransition> view) {
                        return view.allIn(parts, InterfaceStatus.DONE);
                    }
                }
                """);
        sources.put("fx.Interfaces", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @InterfacesGraph.Definition(initial = InterfaceStatus.OPEN, terminals = InterfaceStatus.DONE,
                    state = InterfaceRefs.Status.class, id = InterfaceRefs.Id.class)
                @InterfacesGraph.StatePolicy(state = InterfaceStatus.OPEN, otherwise = "an interface is finished")
                public interface Interfaces extends Machine<Interface, InterfaceStatus, InterfaceTransition> {
                    @InterfacesGraph.Transition(id = InterfaceTransition.FINISH, from = InterfaceStatus.OPEN, to = InterfaceStatus.DONE)
                    Outcome<Interface, InterfaceStatus, InterfaceTransition> finish(Interface part);
                }
                """);
        sources.put("fx.Cases", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @GraphPeers(Interfaces.class)
                @CasesGraph.Definition(initial = CaseStatus.OPEN, terminals = CaseStatus.CLOSED,
                    state = CaseRefs.Status.class, id = CaseRefs.Id.class, afterCommit = DefaultRefs.Closed.class)
                @CasesGraph.StatePolicy(state = CaseStatus.OPEN, otherwise = "an open case is closed")
                @CasesInterfaces.Watch(state = CaseStatus.OPEN, peers = CaseRefs.Parts.class, owner = InterfaceRefs.Owner.class,
                    rules = @CasesInterfaces.When(condition = DefaultRefs.Done.class, transition = CaseTransition.CLOSE))
                public interface Cases extends Machine<Case, CaseStatus, CaseTransition> {
                    @CasesGraph.Transition(id = CaseTransition.CLOSE, from = CaseStatus.OPEN, to = CaseStatus.CLOSED,
                        action = DefaultRefs.Close.class)
                    Outcome<Case, CaseStatus, CaseTransition> close(Case legalCase);
                }
                """);
        sources.put("fx.Docket", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Docket extends Machines { Cases cases(); Interfaces interfaces(); }
                """);
        return sources;
    }

    /**
     * In the unnamed package the first segment of a qualified name is a type of that package, never a package, so
     * an input type and a nested host of the unnamed package are written as such and compile.
     */
    @Test
    void anInputTypeAndANestedHostOfTheUnnamedPackageCompile() {
        Map<String, String> sources = unnamedOrders("    interface Payments { }\n", "HostsPaymentsRefs.Charge.class",
                "Order order, Payment payment");
        sources.put("Payment", "public record Payment(long cents) { }\n");
        sources.put("Hosts", UNNAMED_HOSTS);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("OrdersRuntime").contains("graph(Hosts.Payments payments)"),
                result.generated("OrdersRuntime"));
    }

    /**
     * A type of the unnamed package has no name but its own there, which a single-type import of another type of
     * that simple name would hide, so the companion writes that other type qualified instead of importing it.
     */
    @Test
    void aTypeOfTheUnnamedPackageNamedLikeATypeTheCompanionImportsCompiles() {
        Map<String, String> sources = unnamedOrders("", "ObjectsRefs.Charge.class", "Order order");
        sources.put("Objects", """
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class Objects {
                    @GraphAction public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        String companion = result.generated("OrdersRuntime");
        assertTrue(companion.contains("graph(Objects objects)"), companion);
        assertTrue(companion.contains("java.util.Objects.requireNonNull("), companion);
        assertFalse(companion.contains("import java.util.Objects;"), companion);
    }

    /**
     * A member type of the facade named like a top-level type of the unnamed package hides that type throughout
     * the companion, and no other name reaches it there, so the facade is refused with SG603 naming both.
     */
    @Test
    void sg603AMemberTypeOfTheFacadeHidesATypeOfTheUnnamedPackageTheCompanionWrites() {
        Map<String, String> sources = unnamedOrders("    interface Hosts { }\n", "HostsPaymentsRefs.Charge.class",
                "Order order");
        sources.put("Hosts", UNNAMED_HOSTS);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG603"), result.describe());
        assertTrue(result.errors().get(0).contains("the companion OrdersRuntime of Orders writes Hosts.Payments, but in"
                + " its scope Hosts names the member type Orders.Hosts, not the type Hosts of the unnamed package:"
                + " rename that member type or the type Hosts"), result.describe());
        assertFalse(result.hasGenerated("OrdersRuntime"), result.describe());
    }

    /**
     * Orders in the unnamed package, whose facade declares {@code members} and pays an order with the action
     * {@code action} through the operation {@code pay(parameters)}; the action's host is left to the caller.
     */
    private static Map<String, String> unnamedOrders(String members, String action, String parameters) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("OrderStatus", "public enum OrderStatus { NEW, PAID }\n");
        sources.put("OrderTransition", "public enum OrderTransition { PAY }\n");
        sources.put("Order", """
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Order {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    @GraphProperty public String id() { return "o1"; }
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                }
                """);
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = OrderStatus.NEW, terminals = OrderStatus.PAID,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = OrderStatus.NEW, otherwise = "a new order is paid")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                %s    @OrdersGraph.Transition(id = OrderTransition.PAY, from = OrderStatus.NEW, to = OrderStatus.PAID,
                        action = %s)
                    Outcome<Order, OrderStatus, OrderTransition> pay(%s);
                }
                """.formatted(members, action, parameters));
        return sources;
    }

    @Test
    void setWithEmptyNormalizedNameReportsDiagnosticRatherThanCrashingProcessor() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.__", LIBRARY.replace("interface Library ", "interface __ "));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG603"), result.describe());
    }
}
