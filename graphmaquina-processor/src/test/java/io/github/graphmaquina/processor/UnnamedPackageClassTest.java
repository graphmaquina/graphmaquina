package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * javac admits a class of the unnamed package named like the first segment of a package, such as a class {@code fx}
 * beside the package {@code fx}. Code in a named package never sees that class, while in code of the unnamed package
 * the class obscures the package (JLS 6.4.2), so a canonical name such as {@code fx.A.B}, spelled there, means a type
 * of the package in the one and a member type of the class in the other. The processor looks a name up for the
 * package of the declaration whose code spells it. The type that declares a member of a host it takes from the
 * supertypes of that host instead, as javac bound them: an import, in which no class of the unnamed package is in
 * scope (JLS 6.3), or a supertype of a named package can give code of the unnamed package a type of the package.
 *
 * <p>For a declaration of a named package, every type whose canonical name the processor looks up in such a package
 * is found there, whether the class is empty or nests types of the very names looked up. That holds for the vocabulary
 * of a machine and of the machines a set holds, for symbols, hosts, enums and the types that declare exported members,
 * nested or top-level, and for the types of a package javac has entered none of yet, which a dependency's index names
 * or the processor names itself. For a machine of the unnamed package, the member types of the class are found
 * instead, though the package declares types of the same canonical names, and one compilation gets both sides right
 * at once. A host or an entity of the unnamed package whose action or getter a type of the package declares, which it
 * reaches through an import or through a supertype of another named package, gets that member, though the class nests
 * a homonym of that type, whether the class is a source or a class of the class path. It gets that member as well when
 * that homonym, nested by a class of the sources, is among its supertypes too and met first, whether the homonym
 * declares nothing of the member's name or something else of it that does not carry the member's own mark: a field,
 * an overload, an override without a mark, a method marked in another role, or a getter that is not public. A host
 * that overrides such an action without a mark keeps the action's role. A host keeps its action, too, when its
 * supertypes hold two types of one canonical name that both mark it alike: the homonym by an override that keeps the
 * mark, or by a declaration of its own. A name no type of the package has is reported as it is without that class,
 * even when the class nests a type of that canonical name.</p>
 */
class UnnamedPackageClassTest {

    /**
     * A member type {@code Charging} with no member, the homonym of {@code shop.Charging} that a class {@code shop} of
     * the unnamed package nests.
     */
    private static final String CHARGING = "    public static final class Charging { }\n";

    /** An override of {@code charge}, without a mark, as a host of the unnamed package declares it. */
    private static final String OVERRIDE = """
                @Override
                public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                    return ActionOutcome.ok();
                }
            """;

    /** Homonyms of the user types of {@link #nested()}: its enums with other constants, its entity and its hosts. */
    private static final String USER_TYPE_HOMONYMS = """
                static final class Statuses {
                    private Statuses() { }
                    enum OrderStatus { OPEN }
                    enum OrderTransition { CLOSE }
                }
                static final class Model {
                    private Model() { }
                    static final class Order { }
                }
                static final class Hosts {
                    private Hosts() { }
                    static class Charging { }
                    static final class Payments extends Charging { }
                }
            """;

    /**
     * The smallest machine, whose companion writes {@code billing.Payment} qualified, beside a class {@code fx} that
     * is empty or declares a member type {@code billing} and nests an annotation type whose canonical name is the very
     * name of the machine's {@code Definition}, {@code fx.DocsGraph.Definition}.
     */
    @Test
    void aClassNamedLikeTheFacadesPackageTakesNoneOfItsNames() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("billing.Payment", "package billing;\npublic record Payment(long cents) { }\n");
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace("import static fx.DocStatus.*;", "import billing.Payment;\nimport static fx.DocStatus.*;")
                .replace("publish(Doc doc);", "publish(Doc doc, Payment payment);"));

        Compilation.Result empty = Compilation.compileTyped(beside(sources, "fx", ""));
        Compilation.Result members = Compilation.compileTyped(beside(sources, "fx", """
                    public static final class billing { }
                    public static final class DocsGraph { public @interface Definition { } }
                """));

        assertAll(() -> assertCompiled(empty, "fx.DocsRuntime"), () -> assertCompiled(members, "fx.DocsRuntime"));
    }

    /**
     * A set and the two machines it holds, the one watching the other, beside a class {@code fx} that is empty or
     * nests annotation types whose canonical names are those of the {@code Definition} and the {@code Watch} of the
     * watching machine.
     */
    @Test
    void aClassNamedLikeThePackageOfASetAndItsMachinesTakesNoneOfTheirNames() {
        Map<String, String> sources = TypedFixtures.watching();

        Compilation.Result empty = Compilation.compileTyped(beside(sources, "fx", ""));
        Compilation.Result members = Compilation.compileTyped(beside(sources, "fx", """
                    public static final class DocsGraph { public @interface Definition { } }
                    public static final class DocsPages { public @interface Watch { } }
                """));

        assertAll(() -> assertCompiled(empty, "fx.DocsRuntime", "fx.PagesRuntime", "fx.LibraryRuntime"),
                () -> assertCompiled(members, "fx.DocsRuntime", "fx.PagesRuntime", "fx.LibraryRuntime"));
    }

    /**
     * Every type the processor looks up by a name from the manifest is a member type here: the enums of the machine,
     * its entity, whose marked getter {@code id} is its identity, the hosts of its action, the concrete one overriding
     * without a mark the action its base marks, and the generated vocabulary and symbols. The class {@code shop}
     * beside them is empty or nests types whose canonical names are those of a generated annotation type and of two
     * generated symbols.
     */
    @Test
    void aClassNamedLikeThePackageOfNestedDeclarationsTakesNoneOfTheirNames() {
        Map<String, String> sources = nested();

        Compilation.Result empty = Compilation.compileTyped(beside(sources, "shop", ""));
        Compilation.Result members = Compilation.compileTyped(beside(sources, "shop", """
                    public static final class OrdersGraph { public @interface Definition { } }
                    public static final class ModelOrderRefs { public static final class Id { } }
                    public static final class HostsPaymentsRefs { public static final class Charge { } }
                """));

        assertAll(() -> assertCompiled(empty, "shop.OrdersRuntime"),
                () -> assertCompiled(members, "shop.OrdersRuntime"));
    }

    /**
     * The index of a dependency names its machine, enums, vocabulary and symbols, and this compilation names nothing
     * of its package {@code dep}, so javac has entered no type of that package when the processor first looks one up.
     * A class {@code dep} beside the sources, empty or declaring an enum whose canonical name is that of the
     * dependency's state enum, takes none of those names.
     */
    @Test
    void aClassNamedLikeThePackageOfADependencyTakesNoneOfTheNamesOfItsIndex() {
        List<Path> dependency = List.of(published(producer()));
        Map<String, String> sources = TypedFixtures.minimal();

        Compilation.Result empty = Compilation.compileTyped(beside(sources, "dep", ""), dependency);
        Compilation.Result members = Compilation.compileTyped(beside(sources, "dep", """
                    public enum BillStatus { OPEN }
                """), dependency);

        assertAll(() -> assertCompiled(empty, "fx.DocsRuntime"), () -> assertCompiled(members, "fx.DocsRuntime"));
    }

    /**
     * A transition that fails on an exception makes the processor look up the library's
     * {@code io.github.graphmaquina.error.StateMachineException}, whose package this compilation names nothing of,
     * beside a class {@code io}.
     */
    @Test
    void aClassNamedLikeTheFirstSegmentOfTheLibrarysPackagesTakesNoneOfItsTypes() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Docs", sources.get("fx.Docs").replace("action = DocActionsRefs.Publish.class)",
                "action = DocActionsRefs.Publish.class,\n        failsOn = java.io.UncheckedIOException.class)"));

        assertCompiled(Compilation.compileTyped(beside(sources, "io", "")), "fx.DocsRuntime");
    }

    /**
     * A name no type of the package {@code fx} has, which the manifest lists as an annotation type of that package, is
     * reported with the very error the processor reports for it without a class {@code fx} beside the sources, and as
     * the only error, whether that class is empty or nests an annotation type of that very canonical name.
     */
    @Test
    void aNameNoTypeHasIsReportedAsWithoutTheClass() {
        Map<String, String> sources = TypedFixtures.minimal();

        List<String> without = errorsListing("fx.Gone.Nope", sources);
        List<String> beside = errorsListing("fx.Gone.Nope", beside(sources, "fx", ""));
        List<String> nesting = errorsListing("fx.Gone.Nope", beside(sources, "fx", """
                    public static final class Gone { public @interface Nope { } }
                """));

        assertEquals(List.of("[SG602] the annotation manifest <manifest> lists the annotation type fx.Gone.Nope, which"
                + " this compilation does not declare as an annotation"), without);
        assertEquals(without, beside);
        assertEquals(without, nesting);
    }

    /**
     * A machine of the unnamed package spells its enums {@code shop.Statuses.OrderStatus} and
     * {@code shop.Statuses.OrderTransition}, which there name member types of a class {@code shop} of that package,
     * since the class obscures the package {@code shop} (JLS 6.4.2). The package {@code shop} declares enums of those
     * very canonical names with other constants, and they take nothing from the machine.
     */
    @Test
    void theEnumsOfAMachineOfTheUnnamedPackageAreMemberTypesOfTheClass() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop", """
                public final class shop {
                    private shop() { }
                    public static final class Statuses {
                        private Statuses() { }
                        public enum OrderStatus { NEW, PAID }
                        public enum OrderTransition { PAY }
                    }
                }
                """);
        sources.put("shop.Statuses", """
                package shop;
                public final class Statuses {
                    private Statuses() { }
                    public enum OrderStatus { OPEN, CLOSED }
                    public enum OrderTransition { CLOSE }
                }
                """);
        sources.put("Order", """
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Order {
                    @GraphProperty private shop.Statuses.OrderStatus status = shop.Statuses.OrderStatus.NEW;
                    @GraphProperty public String id() { return "o1"; }
                    public shop.Statuses.OrderStatus status() { return status; }
                    public void status(shop.Statuses.OrderStatus value) { status = value; }
                }
                """);
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = shop.Statuses.OrderStatus.NEW,
                    terminals = shop.Statuses.OrderStatus.PAID, state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = shop.Statuses.OrderStatus.NEW, otherwise = "a new order is paid")
                public interface Orders
                        extends Machine<Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> {
                    @OrdersGraph.Transition(id = shop.Statuses.OrderTransition.PAY,
                        from = shop.Statuses.OrderStatus.NEW, to = shop.Statuses.OrderStatus.PAID)
                    Outcome<Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> pay(Order order);
                }
                """);

        assertCompiled(Compilation.compileTyped(sources), "OrdersRuntime");
    }

    /**
     * A machine of the unnamed package calls the action of its host {@code shop.Hosts.Payments}, a member type of a
     * class {@code shop} of that package. The package {@code shop} declares a type of that very canonical name without
     * that action, and it takes nothing from the machine.
     */
    @Test
    void theHostOfAMachineOfTheUnnamedPackageIsAMemberTypeOfTheClass() {
        Map<String, String> sources = new LinkedHashMap<>();
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
        sources.put("shop", """
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class shop {
                    private shop() { }
                    public static final class Hosts {
                        private Hosts() { }
                        public static final class Payments {
                            @GraphAction
                            public ActionOutcome charge(
                                    TransitionContext<Order, OrderStatus, OrderTransition> context) {
                                return ActionOutcome.ok();
                            }
                        }
                    }
                }
                """);
        sources.put("shop.Hosts", """
                package shop;
                public final class Hosts {
                    private Hosts() { }
                    public static final class Payments { }
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
                    @OrdersGraph.Transition(id = OrderTransition.PAY, from = OrderStatus.NEW, to = OrderStatus.PAID,
                        action = ShopHostsPaymentsRefs.Charge.class)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);
                }
                """);

        assertCompiled(Compilation.compileTyped(sources), "OrdersRuntime");
    }

    /**
     * The nested declarations of {@link #aClassNamedLikeThePackageOfNestedDeclarationsTakesNoneOfTheirNames} as member
     * types of a class {@code shop} of the unnamed package, for a machine of that package, beside a package
     * {@code shop} that declares types of the same canonical names with other constants, no marked member and no
     * action. The machine's enums, its entity with the marked getter {@code id}, and both hosts, the concrete one
     * overriding without a mark the action its base marks, are those member types, and the package's types take
     * nothing from the machine.
     */
    @Test
    void theNestedDeclarationsOfAMachineOfTheUnnamedPackageAreMemberTypesOfTheClass() {
        assertCompiled(Compilation.compileTyped(unnamedNested()), "OrdersRuntime");
    }

    /**
     * One compilation looks up the canonical names of a dependency's types for both sides of those names. Its own
     * machine of the unnamed package means, by {@code dep.Statuses.BillStatus} and {@code dep.Hosts.Payments}, member
     * types of its class {@code dep}. The index of the dependency, and the machine of the dependency that its set
     * {@code fx.Shelf} holds, mean by those names, and by {@code dep.Bill} and {@code dep.Hosts.Charging}, types of the
     * package {@code dep}; their homonyms in the class have other constants, no getter, and other actions or none.
     * Each side gets its own types.
     */
    @Test
    void eachSideOfACanonicalNameGetsItsOwnTypes() {
        List<Path> dependency = List.of(published(nestedProducer()));

        assertCompiled(Compilation.compileTyped(unnamedOverDep(), dependency), "OrdersRuntime", "fx.ShelfRuntime");
    }

    /**
     * A host of the unnamed package extends {@code shop.Charging}, which it imports, and inherits the action that
     * type marks or overrides it without a mark. A class of the unnamed package is not in scope in an import (JLS 6.3),
     * so the import, and with it the host's supertype, is the type of the package {@code shop}, which declares the
     * action, though a class {@code shop} of the unnamed package nests a {@code Charging} without it.
     */
    @Test
    void theImportedSupertypeOfAHostOfTheUnnamedPackageDeclaresItsAction() {
        Compilation.Result inherits = Compilation.compileTyped(inAnotherFile(importing(""), "shop", CHARGING));
        Compilation.Result overrides = Compilation.compileTyped(inAnotherFile(importing(OVERRIDE), "shop", CHARGING));

        assertAll(() -> assertCompiled(inherits, "OrdersRuntime"), () -> assertCompiled(overrides, "OrdersRuntime"));
    }

    /**
     * The host of {@link #theImportedSupertypeOfAHostOfTheUnnamedPackageDeclaresItsAction} that inherits the action,
     * beside a class {@code shop} of the unnamed package that only the class path holds and that nests a
     * {@code Charging} without it.
     */
    @Test
    void theImportedSupertypeOfAHostOfTheUnnamedPackageDeclaresItsActionBesideAClassOfTheClassPath() {
        Path homonym = compiledAlone("shop", "public final class shop {\n" + CHARGING + "}\n");

        assertCompiled(Compilation.compileTyped(importing(""), List.of(homonym)), "OrdersRuntime");
    }

    /**
     * A host of the unnamed package extends {@code mid.Mid}, which extends {@code shop.Charging}. Code of the package
     * {@code mid} never sees a class of the unnamed package, so that type of the package {@code shop} declares the
     * action the host inherits, though a class {@code shop} of the unnamed package nests a {@code Charging} without
     * it.
     */
    @Test
    void theSupertypeANamedIntermediateBindsDeclaresTheActionOfAHostOfTheUnnamedPackage() {
        assertCompiled(Compilation.compileTyped(inAnotherFile(intermediate(), "shop", CHARGING)), "OrdersRuntime");
    }

    /**
     * An entity of the unnamed package extends {@code shop.BaseOrder}, which it imports, and whose marked getter
     * {@code id} is the identity of the machine. That type of the package {@code shop} declares the getter, though a
     * class {@code shop} of the unnamed package nests a {@code BaseOrder} without it.
     */
    @Test
    void theImportedSupertypeOfAnEntityOfTheUnnamedPackageDeclaresItsGetter() {
        Map<String, String> sources = inAnotherFile(inheritedGetter(), "shop",
                "    static final class BaseOrder { }\n");

        assertCompiled(Compilation.compileTyped(sources), "OrdersRuntime");
    }

    /**
     * A host of the unnamed package extends {@code shop.Charging} as its code spells it, a {@code Charging} that a
     * class {@code shop} of the unnamed package nests and that extends {@code mid.Mid}, a subclass of the type
     * {@code shop.Charging} of the package. The host's supertypes hold both types of that name, the homonym first, and
     * only the type of the package declares the action, which the host inherits.
     */
    @Test
    void theActionOfAHostThatExtendsAHomonymIsDeclaredByTheTypeOfThePackageTheHomonymExtends() {
        Map<String, String> sources = inAnotherFile(throughHomonym(""), "shop",
                "    static class Charging extends mid.Mid { }\n");

        assertCompiled(Compilation.compileTyped(sources), "OrdersRuntime");
    }

    /**
     * An entity of the unnamed package extends {@code shop.BaseOrder} as its code spells it, a {@code BaseOrder} that a
     * class {@code shop} of the unnamed package nests and that extends {@code mid.MidOrder}, a subclass of the type
     * {@code shop.BaseOrder} of the package. Only that type, met after the homonym, declares the marked getter
     * {@code id}, the identity of the machine.
     */
    @Test
    void theGetterOfAnEntityThatExtendsAHomonymIsDeclaredByTheTypeOfThePackageTheHomonymExtends() {
        Map<String, String> sources = inAnotherFile(getterThroughHomonym(), "shop",
                "    abstract static class BaseOrder extends mid.MidOrder { }\n");

        assertCompiled(Compilation.compileTyped(sources), "OrdersRuntime");
    }

    /**
     * A host of the unnamed package extends {@code shop.Charging} as its code spells it, a {@code Charging} that a
     * class {@code shop} of the unnamed package nests, and implements the interface {@code shop.Charging} of the
     * package, which it imports and whose marked default action it inherits. Its supertypes hold both types of that
     * name, the superclass first, and only the interface declares the action.
     */
    @Test
    void theActionOfAHostThatExtendsAHomonymIsDeclaredByTheImportedInterfaceItImplements() {
        Map<String, String> sources = inAnotherFile(implementing(""), "shop", "    static class Charging { }\n");

        assertCompiled(Compilation.compileTyped(sources), "OrdersRuntime");
    }

    /**
     * The hosts of {@link #theActionOfAHostThatExtendsAHomonymIsDeclaredByTheTypeOfThePackageTheHomonymExtends} and
     * {@link #theActionOfAHostThatExtendsAHomonymIsDeclaredByTheImportedInterfaceItImplements}, each overriding the
     * action without a mark. The override keeps the role that the type of the package gives the action, though the
     * host's supertypes hold the homonym as well, met first.
     */
    @Test
    void theOverrideOfAHostThatExtendsAHomonymKeepsTheRoleOfTheActionItOverrides() {
        Compilation.Result throughIt = Compilation.compileTyped(inAnotherFile(throughHomonym(OVERRIDE), "shop",
                "    static class Charging extends mid.Mid { }\n"));
        Compilation.Result besideIt = Compilation.compileTyped(inAnotherFile(implementing(OVERRIDE), "shop",
                "    static class Charging { }\n"));

        assertAll(() -> assertCompiled(throughIt, "OrdersRuntime"), () -> assertCompiled(besideIt, "OrdersRuntime"));
    }

    /**
     * The hosts of {@link #theActionOfAHostThatExtendsAHomonymIsDeclaredByTheTypeOfThePackageTheHomonymExtends} and
     * {@link #theActionOfAHostThatExtendsAHomonymIsDeclaredByTheImportedInterfaceItImplements}, whose homonym, met
     * first, declares a member named like the action that is not the action: a field, or an overload without
     * parameters. Beside that overload, one host also overrides the action without a mark.
     */
    @Test
    void aHomonymThatDeclaresAnotherMemberOfTheActionsNameDoesNotHideTheAction() {
        Compilation.Result field = Compilation.compileTyped(inAnotherFile(throughHomonym(""), "shop",
                "    static class Charging extends mid.Mid { int charge; }\n"));
        Compilation.Result overload = Compilation.compileTyped(inAnotherFile(throughHomonym(""), "shop",
                "    static class Charging extends mid.Mid { public void charge() { } }\n"));
        Compilation.Result overloadOverridden = Compilation.compileTyped(inAnotherFile(throughHomonym(OVERRIDE),
                "shop", "    static class Charging extends mid.Mid { public void charge() { } }\n"));
        Compilation.Result overloadBeside = Compilation.compileTyped(inAnotherFile(implementing(""), "shop",
                "    static class Charging { public void charge() { } }\n"));

        assertAll(() -> assertCompiled(field, "OrdersRuntime"), () -> assertCompiled(overload, "OrdersRuntime"),
                () -> assertCompiled(overloadOverridden, "OrdersRuntime"),
                () -> assertCompiled(overloadBeside, "OrdersRuntime"));
    }

    /**
     * The host of {@link #theActionOfAHostThatExtendsAHomonymIsDeclaredByTheTypeOfThePackageTheHomonymExtends}, whose
     * homonym, met first, declares a {@code charge} that does not mark the action's role: an override without a mark,
     * or a guard of that name.
     */
    @Test
    void aHomonymThatDoesNotMarkTheActionInItsRoleDoesNotHideTheAction() {
        Compilation.Result unmarked = Compilation.compileTyped(inAnotherFile(throughHomonym(""), "shop", """
                    static class Charging extends mid.Mid {
                        @Override
                        public io.github.graphmaquina.api.ActionOutcome charge(
                                io.github.graphmaquina.api.TransitionContext<model.Order, model.OrderStatus,
                                        model.OrderTransition> context) {
                            return io.github.graphmaquina.api.ActionOutcome.ok();
                        }
                    }
                """));
        // The homonym's guard makes it a host, whose references container names the auxiliary class shop.
        Compilation.Result guard = Compilation.compilePrepared(Compilation.prepare(inAnotherFile(throughHomonym(""),
                "shop", """
                    static class Charging extends mid.Mid {
                        @io.github.graphmaquina.annotation.GraphGuard
                        public io.github.graphmaquina.api.GuardResult charge(
                                io.github.graphmaquina.api.GuardContext<model.Order, model.OrderStatus,
                                        model.OrderTransition> context) {
                            return io.github.graphmaquina.api.GuardResult.pass();
                        }
                    }
                """)), List.of("-Xlint:-auxiliaryclass"));

        assertAll(() -> assertCompiled(unmarked, "OrdersRuntime"), () -> assertCompiled(guard, "OrdersRuntime"));
    }

    /**
     * Two types of one canonical name that mark one action alike: the host of
     * {@link #theActionOfAHostThatExtendsAHomonymIsDeclaredByTheTypeOfThePackageTheHomonymExtends}, whose homonym, met
     * first, overrides the action and keeps its mark; and a host {@code Host} that implements the interface
     * {@code shop.B} of the package, which it imports, and {@code shop.C}, a member type of the class that extends the
     * class's own {@code shop.B}, where both {@code shop.B} mark one action alike and the host overrides it without a
     * mark. Each host keeps the action, and the compilation succeeds.
     */
    @Test
    void twoTypesOfOneCanonicalNameThatMarkAnActionAlikeLeaveTheHostThatAction() {
        Map<String, String> override = inAnotherFile(throughHomonym(""), "shop", """
                    static class Charging extends mid.Mid {
                        @io.github.graphmaquina.annotation.GraphAction
                        @Override
                        public io.github.graphmaquina.api.ActionOutcome charge(
                                io.github.graphmaquina.api.TransitionContext<model.Order, model.OrderStatus,
                                        model.OrderTransition> context) {
                            return io.github.graphmaquina.api.ActionOutcome.ok();
                        }
                    }
                """);
        Map<String, String> alike = new LinkedHashMap<>();
        alike.put("shop.B", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public interface B {
                    @GraphAction
                    ActionOutcome charge(TransitionContext<Object, java.time.DayOfWeek, java.time.Month> context);
                }
                """);
        alike.put("Host", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import shop.B;
                @GraphBindings
                public final class Host implements B, shop.C {
                    @Override
                    public ActionOutcome charge(
                            TransitionContext<Object, java.time.DayOfWeek, java.time.Month> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        Map<String, String> alikeBeside = inAnotherFile(alike, "shop", """
                    public interface B {
                        @io.github.graphmaquina.annotation.GraphAction
                        io.github.graphmaquina.api.ActionOutcome charge(io.github.graphmaquina.api.TransitionContext<
                                Object, java.time.DayOfWeek, java.time.Month> context);
                    }
                    public interface C extends B { }
                """);

        // Each homonym marks an action, which makes it a host whose references container names the auxiliary class.
        assertAll(() -> assertCompiled(Compilation.compilePrepared(Compilation.prepare(override),
                        List.of("-Xlint:-auxiliaryclass")), "OrdersRuntime"),
                () -> assertCompiled(Compilation.compilePrepared(Compilation.prepare(alikeBeside),
                        List.of("-Xlint:-auxiliaryclass"))));
    }

    /**
     * Two entities whose homonym, met first, declares a member named like the marked getter {@code id} that is not
     * that getter: the entity of
     * {@link #theGetterOfAnEntityThatExtendsAHomonymIsDeclaredByTheTypeOfThePackageTheHomonymExtends}, whose homonym
     * declares a field {@code id}, and one that extends a homonym class declaring a private {@code id()} and implements
     * the interface {@code shop.BaseOrder}, whose marked default getter {@code id} is the identity.
     */
    @Test
    void aHomonymThatDeclaresAnotherMemberOfTheGettersNameDoesNotHideTheGetter() {
        Compilation.Result field = Compilation.compileTyped(inAnotherFile(getterThroughHomonym(), "shop",
                "    abstract static class BaseOrder extends mid.MidOrder { String id; }\n"));
        Compilation.Result unmarked = Compilation.compileTyped(inAnotherFile(getterBeside(), "shop",
                "    static class BaseOrder { private String id() { return \"x\"; } }\n"));

        assertAll(() -> assertCompiled(field, "OrdersRuntime"), () -> assertCompiled(unmarked, "OrdersRuntime"));
    }

    /**
     * The machine of {@link #aClassNamedLikeThePackageOfNestedDeclarationsTakesNoneOfTheirNames} beside a class
     * {@code shop} of the unnamed package that nests homonyms of the user types of the package: its enums with other
     * constants, its entity and both hosts without members.
     */
    @Test
    void homonymsOfTheUserTypesOfThePackageTakeNoneOfTheirNames() {
        assertCompiled(Compilation.compileTyped(inAnotherFile(nested(), "shop", USER_TYPE_HOMONYMS)),
                "shop.OrdersRuntime");
    }

    /**
     * The host of {@link #theImportedSupertypeOfAHostOfTheUnnamedPackageDeclaresItsAction} that inherits the action,
     * edited after the generator ran so that it no longer extends {@code shop.Charging}, which the manifest still says
     * declares the action. That type is no longer among the host's supertypes, so it is looked up by its name for the
     * host's package, and the one error is the machine's binding to the action, which the host no longer has.
     */
    @Test
    void aDeclaringTypeTheHostNoLongerExtendsIsLookedUpByItsName() {
        Compilation.Prepared prepared = Compilation.prepare(importing(""));
        rewrite(prepared, "Payments", """
                import io.github.graphmaquina.annotation.GraphBindings;
                @GraphBindings
                public final class Payments { }
                """);

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertEquals(List.of("[SG505] no exported action named charge on Payments has the signature this machine"
                + " needs; expected ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition>) or"
                + " CompletionStage<ActionOutcome> charge(TransitionContext<Order, OrderStatus, OrderTransition>)"),
                result.errors(), result.describe());
    }

    /** {@code sources} and a class {@code name} of the unnamed package whose body is {@code members}. */
    private static Map<String, String> beside(Map<String, String> sources, String name, String members) {
        Map<String, String> with = new LinkedHashMap<>(sources);
        with.put(name, "public final class " + name + " {\n" + members + "}\n");
        return with;
    }

    /**
     * {@code sources} and a class {@code name} of the unnamed package whose body is {@code members}, declared as an
     * auxiliary class of the file {@code zzz.java}. The references container the generator writes for a host the class
     * nests names the class from outside that file, which javac warns about when its lint {@code auxiliaryclass} is
     * enabled, as {@code -Xlint:all} does. A build with {@code -Xlint:all -Werror} then fails unless it disables that
     * lint, as such a build fails for any host declared as an auxiliary class, homonym or not.
     */
    private static Map<String, String> inAnotherFile(Map<String, String> sources, String name, String members) {
        Map<String, String> with = new LinkedHashMap<>(sources);
        with.put("zzz", "final class " + name + " {\n    private " + name + "() { }\n" + members + "}\n");
        return with;
    }

    /**
     * Replaces the original {@code name} of {@code prepared} with {@code source}, as an edit made after the generator
     * ran does.
     */
    private static void rewrite(Compilation.Prepared prepared, String name, String source) {
        Path file = prepared.request().sources().get(0).resolve(name.replace('.', File.separatorChar) + ".java");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The class output of {@code source}, the one source of the type {@code name}, compiled by plain javac. */
    private static Path compiledAlone(String name, String source) {
        try {
            Path root = Files.createTempDirectory("graphmaquina-processor-");
            Path file = root.resolve("src").resolve(name.replace('.', File.separatorChar) + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source, StandardCharsets.UTF_8);
            Path classes = Files.createDirectories(root.resolve("classes"));
            int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                    file.toString());
            assertEquals(0, status, "could not compile " + name);
            return classes;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** {@code result} compiled, and it wrote every one of {@code companions}. */
    private static void assertCompiled(Compilation.Result result, String... companions) {
        assertTrue(result.success(), result.describe());
        for (String companion : companions) {
            assertTrue(result.hasGenerated(companion), companion + " is not written:\n" + result.describe());
        }
    }

    /**
     * The errors of compiling {@code sources} once their manifest lists {@code type} as an annotation type of the
     * compilation as well, with the manifest's path spelled {@code <manifest>}.
     */
    private static List<String> errorsListing(String type, Map<String, String> sources) {
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Path file = prepared.generation().annotationManifest();
        try {
            CompileManifest manifest = ManifestCodec.readCompile(file);
            ExportIndex local = manifest.local();
            List<String> types = new ArrayList<>(local.annotationTypes());
            types.add(type);
            ManifestCodec.writeCompile(file, new CompileManifest(manifest.schemaVersion(), manifest.toolVersion(),
                    new ExportIndex(local.schemaVersion(), local.toolVersion(), local.compilationId(), local.model(),
                            types),
                    manifest.dependencies(), manifest.processorInventory(), manifest.ownedSources()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        List<String> errors = new ArrayList<>();
        for (String error : Compilation.compileWithoutLifecycle(prepared, List.of()).errors()) {
            errors.add(error.replace(file.toString(), "<manifest>"));
        }
        return errors;
    }

    /** The class output of {@code sources}, compiled with the build's lifecycle, which publishes their index there. */
    private static Path published(Map<String, String> sources) {
        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = Compilation.compilePrepared(prepared);
        assertTrue(result.success(), result.describe());
        return prepared.request().classesOutput();
    }

    /** A machine of the package {@code dep}, with its enums, entity and action host. */
    private static Map<String, String> producer() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("dep.BillStatus", "package dep;\npublic enum BillStatus { OPEN, PAID }\n");
        sources.put("dep.BillTransition", "package dep;\npublic enum BillTransition { SETTLE }\n");
        sources.put("dep.Bill", """
                package dep;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Bill {
                    @GraphProperty private BillStatus status = BillStatus.OPEN;
                    @GraphProperty public String id() { return "b1"; }
                    public BillStatus status() { return status; }
                    public void status(BillStatus value) { status = value; }
                }
                """);
        sources.put("dep.BillActions", """
                package dep;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class BillActions {
                    @GraphAction
                    public ActionOutcome settle(TransitionContext<Bill, BillStatus, BillTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("dep.Bills", """
                package dep;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static dep.BillStatus.*;
                import static dep.BillTransition.*;
                @GenerateGraphApi
                @BillsGraph.Definition(initial = OPEN, terminals = PAID, state = BillRefs.Status.class,
                    id = BillRefs.Id.class)
                @BillsGraph.StatePolicy(state = OPEN, otherwise = "a bill is settled")
                public interface Bills extends Machine<Bill, BillStatus, BillTransition> {
                    @BillsGraph.Transition(id = SETTLE, from = OPEN, to = PAID, action = BillActionsRefs.Settle.class)
                    Outcome<Bill, BillStatus, BillTransition> settle(Bill bill);
                }
                """);
        return sources;
    }

    /**
     * A machine of the package {@code shop} whose enums, entity and action hosts are member types: the concrete host
     * {@code Hosts.Payments} overrides, without marking it, the action its base {@code Hosts.Charging} marks.
     */
    private static Map<String, String> nested() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop.Statuses", """
                package shop;
                public final class Statuses {
                    private Statuses() { }
                    public enum OrderStatus { NEW, PAID, CANCELLED }
                    public enum OrderTransition { PAY, CANCEL }
                }
                """);
        sources.put("shop.Model", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Model {
                    private Model() { }
                    public static final class Order {
                        @GraphProperty private Statuses.OrderStatus status = Statuses.OrderStatus.NEW;
                        @GraphProperty public String id() { return "o1"; }
                        public Statuses.OrderStatus status() { return status; }
                        public void status(Statuses.OrderStatus value) { status = value; }
                    }
                }
                """);
        sources.put("shop.Hosts", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import shop.Model.Order;
                import shop.Statuses.OrderStatus;
                import shop.Statuses.OrderTransition;
                public final class Hosts {
                    private Hosts() { }
                    public static class Charging {
                        @GraphAction
                        public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                            return ActionOutcome.ok();
                        }
                    }
                    @GraphBindings
                    public static final class Payments extends Charging {
                        @Override
                        public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                            return ActionOutcome.ok();
                        }
                    }
                }
                """);
        sources.put("shop.Orders", """
                package shop;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import shop.Model.Order;
                import shop.Statuses.OrderStatus;
                import shop.Statuses.OrderTransition;
                import static shop.Statuses.OrderStatus.*;
                import static shop.Statuses.OrderTransition.*;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = NEW, terminals = {PAID, CANCELLED},
                    state = ModelOrderRefs.Status.class, id = ModelOrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = NEW, otherwise = "a new order is paid or cancelled")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = PAY, from = NEW, to = PAID, action = HostsPaymentsRefs.Charge.class)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);

                    @OrdersGraph.Transition(id = CANCEL, from = NEW, to = CANCELLED)
                    Outcome<Order, OrderStatus, OrderTransition> cancel(Order order);
                }
                """);
        return sources;
    }

    /**
     * The declarations of {@link #nested()} as member types of a class {@code shop} of the unnamed package, with a
     * machine of that package over them, beside a package {@code shop} that declares types of the same canonical
     * names with other constants, no marked member and no action.
     */
    private static Map<String, String> unnamedNested() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shop", """
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.annotation.GraphProperty;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class shop {
                    private shop() { }
                    public static final class Statuses {
                        private Statuses() { }
                        public enum OrderStatus { NEW, PAID, CANCELLED }
                        public enum OrderTransition { PAY, CANCEL }
                    }
                    public static final class Model {
                        private Model() { }
                        public static final class Order {
                            @GraphProperty private Statuses.OrderStatus status = Statuses.OrderStatus.NEW;
                            @GraphProperty public String id() { return "o1"; }
                            public Statuses.OrderStatus status() { return status; }
                            public void status(Statuses.OrderStatus value) { status = value; }
                        }
                    }
                    public static final class Hosts {
                        private Hosts() { }
                        public static class Charging {
                            @GraphAction
                            public ActionOutcome charge(TransitionContext<Model.Order, Statuses.OrderStatus,
                                    Statuses.OrderTransition> context) {
                                return ActionOutcome.ok();
                            }
                        }
                        @GraphBindings
                        public static final class Payments extends Charging {
                            @Override
                            public ActionOutcome charge(TransitionContext<Model.Order, Statuses.OrderStatus,
                                    Statuses.OrderTransition> context) {
                                return ActionOutcome.ok();
                            }
                        }
                    }
                }
                """);
        sources.put("shop.Statuses", """
                package shop;
                public final class Statuses {
                    private Statuses() { }
                    public enum OrderStatus { OPEN, CLOSED }
                    public enum OrderTransition { CLOSE }
                }
                """);
        sources.put("shop.Model", """
                package shop;
                public final class Model {
                    private Model() { }
                    public static final class Order { }
                }
                """);
        sources.put("shop.Hosts", """
                package shop;
                public final class Hosts {
                    private Hosts() { }
                    public static class Charging { }
                    public static final class Payments extends Charging { }
                }
                """);
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = shop.Statuses.OrderStatus.NEW,
                    terminals = {shop.Statuses.OrderStatus.PAID, shop.Statuses.OrderStatus.CANCELLED},
                    state = ShopModelOrderRefs.Status.class, id = ShopModelOrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = shop.Statuses.OrderStatus.NEW,
                    otherwise = "a new order is paid or cancelled")
                public interface Orders
                        extends Machine<shop.Model.Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> {
                    @OrdersGraph.Transition(id = shop.Statuses.OrderTransition.PAY,
                        from = shop.Statuses.OrderStatus.NEW, to = shop.Statuses.OrderStatus.PAID,
                        action = ShopHostsPaymentsRefs.Charge.class)
                    Outcome<shop.Model.Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> pay(
                            shop.Model.Order order);

                    @OrdersGraph.Transition(id = shop.Statuses.OrderTransition.CANCEL,
                        from = shop.Statuses.OrderStatus.NEW, to = shop.Statuses.OrderStatus.CANCELLED)
                    Outcome<shop.Model.Order, shop.Statuses.OrderStatus, shop.Statuses.OrderTransition> cancel(
                            shop.Model.Order order);
                }
                """);
        return sources;
    }

    /**
     * A machine of the package {@code dep} whose enums and action hosts are member types: the concrete host
     * {@code Hosts.Payments} overrides, without marking it, the action its base {@code Hosts.Charging} marks.
     */
    private static Map<String, String> nestedProducer() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("dep.Statuses", """
                package dep;
                public final class Statuses {
                    private Statuses() { }
                    public enum BillStatus { OPEN, PAID }
                    public enum BillTransition { SETTLE }
                }
                """);
        sources.put("dep.Bill", """
                package dep;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Bill {
                    @GraphProperty private Statuses.BillStatus status = Statuses.BillStatus.OPEN;
                    @GraphProperty public String id() { return "b1"; }
                    public Statuses.BillStatus status() { return status; }
                    public void status(Statuses.BillStatus value) { status = value; }
                }
                """);
        sources.put("dep.Hosts", """
                package dep;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class Hosts {
                    private Hosts() { }
                    public static class Charging {
                        @GraphAction
                        public ActionOutcome settle(
                                TransitionContext<Bill, Statuses.BillStatus, Statuses.BillTransition> context) {
                            return ActionOutcome.ok();
                        }
                    }
                    @GraphBindings
                    public static final class Payments extends Charging {
                        @Override
                        public ActionOutcome settle(
                                TransitionContext<Bill, Statuses.BillStatus, Statuses.BillTransition> context) {
                            return ActionOutcome.ok();
                        }
                    }
                }
                """);
        sources.put("dep.Bills", """
                package dep;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @BillsGraph.Definition(initial = Statuses.BillStatus.OPEN, terminals = Statuses.BillStatus.PAID,
                    state = BillRefs.Status.class, id = BillRefs.Id.class)
                @BillsGraph.StatePolicy(state = Statuses.BillStatus.OPEN, otherwise = "a bill is settled")
                public interface Bills extends Machine<Bill, Statuses.BillStatus, Statuses.BillTransition> {
                    @BillsGraph.Transition(id = Statuses.BillTransition.SETTLE, from = Statuses.BillStatus.OPEN,
                        to = Statuses.BillStatus.PAID, action = HostsPaymentsRefs.Settle.class)
                    Outcome<Bill, Statuses.BillStatus, Statuses.BillTransition> settle(Bill bill);
                }
                """);
        return sources;
    }

    /**
     * A machine of the unnamed package over member types of a class {@code dep} of that package, which nests types of
     * the canonical names of the enums, the entity and both action hosts of {@link #nestedProducer()}, with other
     * constants, no getter, and other actions or none, and a set {@code fx.Shelf} holding the machine of that
     * dependency.
     */
    private static Map<String, String> unnamedOverDep() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("dep", """
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                public final class dep {
                    private dep() { }
                    public static final class Statuses {
                        private Statuses() { }
                        public enum BillStatus { NEW, PAID }
                        public enum BillTransition { PAY }
                    }
                    public static final class Hosts {
                        private Hosts() { }
                        public static class Charging { }
                        public static final class Payments {
                            @GraphAction
                            public ActionOutcome charge(
                                    TransitionContext<Order, Statuses.BillStatus, Statuses.BillTransition> context) {
                                return ActionOutcome.ok();
                            }
                        }
                    }
                    public static final class Bill { }
                }
                """);
        sources.put("Order", """
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Order {
                    @GraphProperty private dep.Statuses.BillStatus status = dep.Statuses.BillStatus.NEW;
                    @GraphProperty public String id() { return "o1"; }
                    public dep.Statuses.BillStatus status() { return status; }
                    public void status(dep.Statuses.BillStatus value) { status = value; }
                }
                """);
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = dep.Statuses.BillStatus.NEW, terminals = dep.Statuses.BillStatus.PAID,
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = dep.Statuses.BillStatus.NEW, otherwise = "a new order is paid")
                public interface Orders extends Machine<Order, dep.Statuses.BillStatus, dep.Statuses.BillTransition> {
                    @OrdersGraph.Transition(id = dep.Statuses.BillTransition.PAY, from = dep.Statuses.BillStatus.NEW,
                        to = dep.Statuses.BillStatus.PAID, action = DepHostsPaymentsRefs.Charge.class)
                    Outcome<Order, dep.Statuses.BillStatus, dep.Statuses.BillTransition> pay(Order order);
                }
                """);
        sources.put("fx.Shelf", """
                package fx;
                import dep.Bills;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Shelf extends Machines {
                    Bills bills();
                }
                """);
        return sources;
    }

    /**
     * The package {@code model} of a machine's enums and entity, the base host {@code shop.Charging} that marks the
     * action {@code charge}, and a machine of the unnamed package that runs it through a host {@code Payments}, which
     * the caller adds.
     */
    private static Map<String, String> model() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("model.OrderStatus", "package model;\npublic enum OrderStatus { NEW, PAID }\n");
        sources.put("model.OrderTransition", "package model;\npublic enum OrderTransition { PAY }\n");
        sources.put("model.Order", """
                package model;
                import io.github.graphmaquina.annotation.GraphProperty;
                public final class Order {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    @GraphProperty public String id() { return "o1"; }
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                }
                """);
        sources.put("shop.Charging", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import model.Order;
                import model.OrderStatus;
                import model.OrderTransition;
                public class Charging {
                    @GraphAction
                    public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("Orders", """
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import model.Order;
                import model.OrderStatus;
                import model.OrderTransition;
                @GenerateGraphApi
                @OrdersGraph.Definition(initial = OrderStatus.NEW, terminals = OrderStatus.PAID,
                    state = model.OrderRefs.Status.class, id = model.OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = OrderStatus.NEW, otherwise = "a new order is paid")
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = OrderTransition.PAY, from = OrderStatus.NEW, to = OrderStatus.PAID,
                        action = PaymentsRefs.Charge.class)
                    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);
                }
                """);
        return sources;
    }

    /**
     * {@link #model()} with the host {@code Payments}, which imports {@code shop.Charging}, extends it and declares
     * {@code members}.
     */
    private static Map<String, String> importing(String members) {
        Map<String, String> sources = model();
        sources.put("Payments", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import model.Order;
                import model.OrderStatus;
                import model.OrderTransition;
                import shop.Charging;
                @GraphBindings
                public final class Payments extends Charging {
                %s}
                """.formatted(members));
        return sources;
    }

    /**
     * {@link #model()} with the host {@code Payments}, which extends {@code mid.Mid}, a subclass of
     * {@code shop.Charging}.
     */
    private static Map<String, String> intermediate() {
        Map<String, String> sources = model();
        sources.put("mid.Mid", "package mid;\npublic class Mid extends shop.Charging { }\n");
        sources.put("Payments", """
                import io.github.graphmaquina.annotation.GraphBindings;
                @GraphBindings
                public final class Payments extends mid.Mid { }
                """);
        return sources;
    }

    /**
     * A machine of the unnamed package whose entity {@code Order} extends {@code shop.BaseOrder}, which it imports and
     * whose marked getter {@code id} is the identity.
     */
    private static Map<String, String> inheritedGetter() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("OrderStatus", "public enum OrderStatus { NEW, PAID }\n");
        sources.put("OrderTransition", "public enum OrderTransition { PAY }\n");
        sources.put("shop.BaseOrder", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public abstract class BaseOrder {
                    @GraphProperty public String id() { return "o1"; }
                }
                """);
        sources.put("Order", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.annotation.GraphProperty;
                import shop.BaseOrder;
                @GraphBindings
                public final class Order extends BaseOrder {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
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
        return sources;
    }

    /**
     * {@link #intermediate()} with a host {@code Payments} that declares {@code members} and extends the type the name
     * {@code shop.Charging} means in its code: the member type of a class {@code shop} of the unnamed package, which
     * the caller adds.
     */
    private static Map<String, String> throughHomonym(String members) {
        Map<String, String> sources = intermediate();
        sources.put("Payments", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import model.Order;
                import model.OrderStatus;
                import model.OrderTransition;
                @GraphBindings
                public final class Payments extends shop.Charging {
                %s}
                """.formatted(members));
        return sources;
    }

    /**
     * {@link #inheritedGetter()} with {@code mid.MidOrder}, an abstract subclass of {@code shop.BaseOrder}, and an
     * entity {@code Order} that extends the type the name {@code shop.BaseOrder} means in its code: the member type of
     * a class {@code shop} of the unnamed package, which the caller adds.
     */
    private static Map<String, String> getterThroughHomonym() {
        Map<String, String> sources = inheritedGetter();
        sources.put("mid.MidOrder", "package mid;\npublic abstract class MidOrder extends shop.BaseOrder { }\n");
        sources.put("Order", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.annotation.GraphProperty;
                @GraphBindings
                public final class Order extends shop.BaseOrder {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                }
                """);
        return sources;
    }

    /**
     * {@link #inheritedGetter()} with {@code shop.BaseOrder} an interface whose default getter {@code id} it marks, and
     * an entity {@code Order} that imports and implements that interface and extends the type the name
     * {@code shop.BaseOrder} means in its code: the member type of a class {@code shop} of the unnamed package, which
     * the caller adds.
     */
    private static Map<String, String> getterBeside() {
        Map<String, String> sources = inheritedGetter();
        sources.put("shop.BaseOrder", """
                package shop;
                import io.github.graphmaquina.annotation.GraphProperty;
                public interface BaseOrder {
                    @GraphProperty default String id() { return "o1"; }
                }
                """);
        sources.put("Order", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.annotation.GraphProperty;
                import shop.BaseOrder;
                @GraphBindings
                public final class Order extends shop.BaseOrder implements BaseOrder {
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                }
                """);
        return sources;
    }

    /**
     * {@link #model()} with {@code shop.Charging} an interface whose default action {@code charge} it marks, and a host
     * {@code Payments} that declares {@code members}, imports and implements that interface, and extends the type the
     * name {@code shop.Charging} means in its code: the member type of a class {@code shop} of the unnamed package,
     * which the caller adds.
     */
    private static Map<String, String> implementing(String members) {
        Map<String, String> sources = model();
        sources.put("shop.Charging", """
                package shop;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import model.Order;
                import model.OrderStatus;
                import model.OrderTransition;
                public interface Charging {
                    @GraphAction
                    default ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("Payments", """
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import model.Order;
                import model.OrderStatus;
                import model.OrderTransition;
                import shop.Charging;
                @GraphBindings
                public final class Payments extends shop.Charging implements Charging {
                %s}
                """.formatted(members));
        return sources;
    }
}
