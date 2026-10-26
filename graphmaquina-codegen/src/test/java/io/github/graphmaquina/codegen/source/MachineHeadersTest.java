package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.source.util.JavacTask;
import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that a machine facade is specialized to its entity, its state enum, its transition enum, the
 * constants of both and its peers.
 */
class MachineHeadersTest {

    @TempDir
    Path directory;

    private static final String BASE = """
        package fx;
        import io.github.graphmaquina.api.Machine;
        public interface Base<T, U extends Enum<U>, V extends Enum<V>> extends Machine<T, U, V> {}
        """;

    private static final String TRANSITION =
            "package fx; public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }";

    @Test
    void resolvesEntityStateAndConstantsThroughAGenericBase() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", TRANSITION,
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status, DocTransition> {
                        default void probe() { MissingGeneratedType.run(); }
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor descriptor = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Order", descriptor.entityType());
            assertEquals("fx.Status", descriptor.stateType());
            assertEquals(List.of("NEW", "DONE"), descriptor.states());
            assertEquals("fx.DocTransition", descriptor.transitionType());
            assertEquals(List.of("SUBMIT", "PUBLISH", "ARCHIVE"), descriptor.transitions());
            assertEquals(List.of(), descriptor.peers());
            assertEquals("fx/Orders.java", descriptor.origin().path());
            assertEquals(3L, descriptor.origin().line());
        }
    }

    @Test
    void resolvesPeersDeclaredAsClassLiterals() {
        Map<String, String> sources = new LinkedHashMap<>(Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", TRANSITION,
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Bills", "package fx; public interface Bills extends Base<Order, Status, DocTransition> {}",
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    @GenerateGraphApi
                    @GraphPeers({Bills.class})
                    public interface Orders extends Base<Order, Status, DocTransition> {}
                    """));
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            assertEquals(List.of("fx.Bills"), MachineHeaders.resolve(index, "fx.Orders").peers());
        }
    }

    /**
     * A peer named twice, here once by its simple name and once by its canonical name, would generate the
     * vocabulary of one pair twice, so it is refused as the duplicated peer it is, at the annotation that names it.
     */
    @Test
    void refusesAPeerDeclaredTwice() {
        Map<String, String> sources = new LinkedHashMap<>(Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", TRANSITION,
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Bills", "package fx; public interface Bills extends Base<Order, Status, DocTransition> {}",
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    @GenerateGraphApi
                    @GraphPeers({Bills.class, fx.Bills.class})
                    public interface Orders extends Base<Order, Status, DocTransition> {}
                    """));
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> MachineHeaders.resolve(index, "fx.Orders"));

            assertEquals(DiagnosticCode.SG608.name(), failure.code());
            assertEquals("fx.Bills is declared twice as a peer of fx.Orders", failure.getMessage());
            assertEquals("fx/Orders.java", failure.location().orElseThrow().path());
            assertEquals(5L, failure.location().orElseThrow().line());
        }
    }

    @Test
    void resolvesThePackageOfAFacadeWhoseSegmentsAreNotAllLowerCase() {
        Map<String, String> sources = Map.of(
                "com.Acme.docs.Status", "package com.Acme.docs; public enum Status { NEW, DONE }",
                "com.Acme.docs.DocTransition",
                    "package com.Acme.docs; public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }",
                "com.Acme.docs.Order", "package com.Acme.docs; public class Order {}",
                "com.Acme.docs.Orders", """
                    package com.Acme.docs;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status, DocTransition> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "com.Acme.docs.Orders");
            assertEquals("com.Acme.docs.Orders", graph.facade());
            assertEquals("com.Acme.docs", graph.packageName());
            assertEquals("com.Acme.docs.Status", graph.stateType());
            assertEquals("com.Acme.docs.DocTransition", graph.transitionType());
        }
    }

    @Test
    void resolvesAMarkedFacadeWhoseGeneratedImportsAndAnnotationsDoNotExistYet() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", TRANSITION,
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Bills", "package fx; public interface Bills extends Base<Order, Status, DocTransition> {}",
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    import fx.OrdersGraph.Definition;
                    import fx.OrdersGraph.*;
                    import static fx.PaymentActionsRefs.*;
                    @GenerateGraphApi
                    @GraphPeers({Bills.class})
                    @Definition(state = PaymentOrderRefs.Status.class, id = PaymentOrderRefs.Id.class)
                    public interface Orders extends Base<Order, Status, DocTransition> {
                        default void probe() { MissingGeneratedType.run(Authorize.class); }
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Order", graph.entityType());
            assertEquals("fx.Status", graph.stateType());
            assertEquals("fx.DocTransition", graph.transitionType());
            assertEquals(List.of("fx.Bills"), graph.peers());
        }
    }

    @Test
    void resolvesNestedTypesInheritedThroughTheOwnerHierarchy() {
        Map<String, String> sources = Map.of(
                "fx.Holder", """
                    package fx;
                    public class Holder {
                        public static class Order {}
                        public enum Status { NEW, DONE }
                        public enum Id { SUBMIT, PUBLISH, ARCHIVE }
                    }
                    """,
                "fx.Sub", "package fx; public class Sub extends Holder {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Sub.Order, Sub.Status, Sub.Id> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            assertEquals(List.of("fx.Base", "fx.Holder", "fx.Holder.Id", "fx.Holder.Order", "fx.Holder.Status",
                            "fx.Orders", "fx.Sub"),
                    index.sourceHeaders().stream().map(TypeHeader::canonicalName).sorted().toList());
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Holder.Order", graph.entityType());
            assertEquals("fx.Holder.Status", graph.stateType());
            assertEquals(List.of("NEW", "DONE"), graph.states());
            assertEquals("fx.Holder.Id", graph.transitionType());
            assertEquals(List.of("SUBMIT", "PUBLISH", "ARCHIVE"), graph.transitions());
        }
    }

    @Test
    void reportsAmbiguousOnDemandImportsInsteadOfChoosingByOrder() {
        Map<String, String> sources = Map.of(
                "a.Status", "package a; public enum Status { NEW }",
                "b.Status", "package b; public enum Status { DONE }",
                "fx.DocTransition", TRANSITION,
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import a.*;
                    import b.*;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status, DocTransition> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals(DiagnosticCode.SG601.name(), failure.code());
            assertEquals("the type Status is ambiguous between a.Status and b.Status", failure.getMessage());
            assertEquals("fx/Orders.java", failure.location().orElseThrow().path());
        }
    }

    /**
     * An on-demand import only imports the types it can access (JLS 7.5.2): a package-private type of another
     * package is never offered, so it makes no name ambiguous. A member type of an interface is implicitly
     * public, so an on-demand import of its interface does offer it.
     */
    @Test
    void ignoresTypesAnOnDemandImportCannotAccess() {
        Map<String, String> sources = Map.of(
                "a.Status", "package a; enum Status { HIDDEN }",
                "a.Tools", "package a; public class Tools {}",
                "b.Status", "package b; public enum Status { DRAFT, LIVE }",
                "c.Api", "package c; public interface Api { enum Step { GO } }",
                "fx.Doc", "package fx; public class Doc {}",
                "fx.Docs", """
                    package fx;
                    import a.*;
                    import b.*;
                    import c.Api.*;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Docs extends Machine<Doc, Status, Step> {
                        Tools tools();
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Docs");
            assertEquals(List.of("fx.Doc", "b.Status", "c.Api.Step"),
                    List.of(graph.entityType(), graph.stateType(), graph.transitionType()));
            assertEquals(machineArgumentsAccordingToJavac(files, "fx.Docs"),
                    List.of(graph.entityType(), graph.stateType(), graph.transitionType()));
        }
    }

    /** In the unnamed package a sibling's canonical name is its simple name, and it resolves like any sibling. */
    @Test
    void resolvesSiblingsOfTheUnnamedPackage() {
        Map<String, String> sources = Map.of(
                "Doc", "public class Doc { public enum Status { DRAFT, LIVE } public enum Step { PUBLISH } }",
                "Base", """
                    import io.github.graphmaquina.api.Machine;
                    public interface Base<E> extends Machine<E, Doc.Status, Doc.Step> {}
                    """,
                "Pages", """
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Pages extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """,
                "Docs", """
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    @GenerateGraphApi
                    @GraphPeers(Pages.class)
                    public interface Docs extends Base<Doc> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "Docs");
            assertEquals(List.of("Doc", "Doc.Status", "Doc.Step"),
                    List.of(graph.entityType(), graph.stateType(), graph.transitionType()));
            assertEquals(List.of("Pages"), graph.peers());
        }
    }

    @Test
    void keepsMethodTypeVariablesOutOfTheClassSubstitutionEnvironment() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", TRANSITION,
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", """
                    package fx;
                    import io.github.graphmaquina.api.Machine;
                    public interface Base<T, U extends Enum<U>, V extends Enum<V>> extends Machine<T, U, V> {
                        <T extends Number> T map(T value);
                        T get();
                    }
                    """,
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status, DocTransition> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            TypeHeader base = index.find("fx.Base").orElseThrow();
            TypeHeader.Member map = member(base, "map");
            TypeHeader.Member get = member(base, "get");
            assertEquals(new TypeUse.Variable("T", new TypeParameterKey("fx.Base#map(T)", 0)), map.type());
            assertEquals(List.of("T"), map.typeParameters());
            assertEquals(new TypeUse.Variable("T", new TypeParameterKey("fx.Base", 0)), get.type());

            Map<TypeParameterKey, TypeUse> environment =
                    Map.of(new TypeParameterKey("fx.Base", 0), TypeUse.Declared.of("java.lang.String"));
            assertEquals("java.lang.String", index.substitute(get.type(), environment).render());
            assertEquals("T", index.substitute(map.type(), environment).render());

            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals("fx.Order", graph.entityType());
            assertEquals("fx.Status", graph.stateType());
            assertEquals("fx.DocTransition", graph.transitionType());
        }
    }

    @Test
    void reportsAStateTypeThatIsNotAnEnum() {
        Map<String, String> sources = Map.of(
                "fx.Order", "package fx; public class Order {}",
                "fx.Status", "package fx; public class Status {}",
                "fx.DocTransition", TRANSITION,
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status, DocTransition> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals(DiagnosticCode.SG601.name(), failure.code());
            assertEquals("the state type fx.Status of fx.Orders is not an enum", failure.getMessage());
            assertEquals("fx/Orders.java", failure.location().orElseThrow().path());
        }
    }

    @Test
    void reportsATransitionTypeThatIsNotAnEnum() {
        Map<String, String> sources = Map.of(
                "fx.Order", "package fx; public class Order {}",
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", "package fx; public class DocTransition {}",
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status, DocTransition> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals(DiagnosticCode.SG601.name(), failure.code());
            assertEquals("the transition type fx.DocTransition of fx.Orders is not an enum", failure.getMessage());
            assertEquals("fx/Orders.java", failure.location().orElseThrow().path());
        }
    }

    @Test
    void reportsAMachineWhoseStatesAndTransitionsAreTheSameEnum() {
        Map<String, String> sources = Map.of(
                "fx.Order", "package fx; public class Order {}",
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status, Status> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals(DiagnosticCode.SG601.name(), failure.code());
            assertEquals("the machine fx.Orders uses fx.Status as both its state type and its transition type; "
                    + "states and transitions require distinct enum types", failure.getMessage());
            assertEquals("fx/Orders.java", failure.location().orElseThrow().path());
        }
    }

    @Test
    void reportsATransitionTypeNoDeclarationResolves() {
        Map<String, String> sources = Map.of(
                "fx.Order", "package fx; public class Order {}",
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status, DocTransition> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals(DiagnosticCode.SG601.name(), failure.code());
            assertEquals("the transition type DocTransition of fx.Orders is unresolved", failure.getMessage());
            assertEquals("fx/Orders.java", failure.location().orElseThrow().path());
        }
    }

    /**
     * The vocabulary names its machine by a class literal, and the raw literal of a generic facade is not a
     * {@code Class<? extends Machine<?, ?, ?>>}: such a facade is refused before anything is generated for it.
     */
    @Test
    void reportsAFacadeThatDeclaresTypeParameters() {
        Map<String, String> sources = Map.of(
                "fx.Doc", "package fx; public class Doc { public enum Status { DRAFT } public enum Step { GO } }",
                "fx.Docs", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Docs<X, Y> extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Docs"));
            assertEquals(DiagnosticCode.SG601.name(), failure.code());
            assertEquals("the machine fx.Docs declares the type parameters <X, Y>; a @GenerateGraphApi machine "
                    + "cannot be generic", failure.getMessage());
            assertEquals("fx/Docs.java", failure.location().orElseThrow().path());
        }
    }

    /**
     * The vocabulary is a top-level type of the facade's package that names the facade and both of its enums,
     * so a facade or an enum that is private, or nested in a private type, is refused at the facade.
     */
    @Test
    void refusesAMachineWhoseVocabularyCannotNameItsFacadeOrItsEnums() {
        List<String[]> cases = List.of(
                new String[] {"fx.Shop.Flow", "the state type fx.Shop.Phase", "fx.Shop.Phase", """
                    public class Shop {
                        private enum Phase { OPEN, CLOSED }
                        enum Step { CLOSE }
                        @GenerateGraphApi
                        interface Flow extends Machine<Doc, Phase, Step> {}
                    }
                    """},
                new String[] {"fx.Shop.Flow", "the transition type fx.Shop.Step", "fx.Shop.Step", """
                    public class Shop {
                        enum Phase { OPEN, CLOSED }
                        private enum Step { CLOSE }
                        @GenerateGraphApi
                        interface Flow extends Machine<Doc, Phase, Step> {}
                    }
                    """},
                new String[] {"fx.Shop.Hidden.Flow", "the machine interface fx.Shop.Hidden.Flow", "fx.Shop.Hidden", """
                    public class Shop {
                        enum Phase { OPEN, CLOSED }
                        enum Step { CLOSE }
                        private static class Hidden {
                            @GenerateGraphApi
                            interface Flow extends Machine<Doc, Phase, Step> {}
                        }
                    }
                    """});
        for (String[] shop : cases) {
            Path project = directory.resolve(String.valueOf(cases.indexOf(shop)));
            List<Path> files = SourceFiles.write(project, Map.of(
                    "fx.Doc", "package fx; public class Doc {}",
                    "fx.Shop", """
                        package fx;
                        import io.github.graphmaquina.annotation.GenerateGraphApi;
                        import io.github.graphmaquina.api.Machine;
                        """ + shop[3]));
            List<SourceUnit> units = SourceDeclarations.parse(project, files);
            try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
                CodegenException failure =
                        assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, shop[0]));
                assertEquals(DiagnosticCode.SG601.name(), failure.code());
                assertEquals(shop[1] + (shop[1].startsWith("the machine") ? "" : " of " + shop[0])
                        + " is not accessible to the vocabulary generated in package fx: " + shop[2]
                        + " is private", failure.getMessage());
                assertEquals("fx/Shop.java", failure.location().orElseThrow().path());
            }
        }
    }

    /**
     * The vocabulary of a pair is generated in the owner's package and names the peer's state enum, so a
     * peer whose state enum is not public, in a package of its own, is refused at the owner.
     */
    @Test
    void refusesAPeerWhoseStateTypeTheOwnersPackageCannotName() {
        Path sources = directory.resolve("src");
        SourceFiles.write(sources, Map.of(
                "shop.Order",
                    "package shop; public class Order { public enum Status { OPEN } public enum Step { GO } }",
                "shop.Orders", """
                    package shop;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    @GraphPeers(stock.Stocks.class)
                    public interface Orders extends Machine<Order, Order.Status, Order.Step> {}
                    """,
                "stock.Move", "package stock; public enum Move { SELL }",
                "stock.Stock", "package stock; public class Stock {}",
                "stock.StockState", "package stock; enum StockState { IN_STOCK, SOLD_OUT }",
                "stock.Stocks", """
                    package stock;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Stocks extends Machine<Stock, StockState, Move> {}
                    """));

        CodegenException failure = assertThrows(CodegenException.class, () -> Bootstrap.inspect(
                new GenerationRequest(directory, List.of(sources), SourceFiles.classpath(),
                        directory.resolve("out/sources"), directory.resolve("out/processor"),
                        directory.resolve("out/classes"), directory.resolve("out/work"), "shop:main")));

        assertEquals(DiagnosticCode.SG608.name(), failure.code());
        assertEquals("the peer machine stock.Stocks declared by shop.Orders cannot be named from package shop,"
                + " where its pair vocabulary is generated: stock.StockState is not public and belongs to package"
                + " stock", failure.getMessage());
        assertEquals("src/shop/Orders.java", failure.location().orElseThrow().path());
    }

    /**
     * Inside the vocabulary, a type of its package named like the first segment of a qualified name obscures the
     * package of that name (JLS 6.4.2), so the name would mean a member of that type. That is refused at the
     * facade, naming the type, for each package the vocabulary names: StateGraph's {@code io} package, the JDK's
     * {@code java} package, the package of an enum, and the facade's own package.
     */
    @Test
    void refusesAMachineWhosePackageObscuresAPackageItsVocabularyNames() {
        List<String[]> cases = List.of(
                new String[] {"fx.io", "package fx; public class io {}", "", "io.github.graphmaquina"},
                new String[] {"fx.java", "package fx; public class java {}", "", "java.lang"},
                new String[] {"fx.shop", "package fx; public class shop {}", "import shop.Status;\n", "shop.Status"},
                new String[] {"fx.fx", "package fx; public class fx {}", "", "fx.Docs"});
        for (String[] obscured : cases) {
            Path project = directory.resolve(obscured[0]);
            List<Path> files = SourceFiles.write(project, Map.of(
                    obscured[0], obscured[1],
                    "shop.Status", "package shop; public enum Status { DRAFT, LIVE }",
                    "fx.Doc", "package fx; public class Doc { public enum Status { DRAFT } public enum Step { GO } }",
                    "fx.Docs", "package fx;\n" + obscured[2] + """
                        import io.github.graphmaquina.annotation.GenerateGraphApi;
                        import io.github.graphmaquina.api.Machine;
                        @GenerateGraphApi
                        public interface Docs extends Machine<Doc, %s, Doc.Step> {}
                        """.formatted(obscured[2].isEmpty() ? "Doc.Status" : "Status")));
            List<SourceUnit> units = SourceDeclarations.parse(project, files);
            try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
                CodegenException failure =
                        assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Docs"));
                assertEquals(DiagnosticCode.SG603.name(), failure.code(), obscured[0]);
                assertEquals("the vocabulary generated for the machine fx.Docs in package fx cannot name "
                        + obscured[3] + ": the type " + obscured[0] + " obscures the package "
                        + obscured[3].substring(0, obscured[3].indexOf('.')) + " there", failure.getMessage());
                assertEquals("fx/Docs.java", failure.location().orElseThrow().path());
            }
        }
    }

    /**
     * Every compilation unit imports the public types of {@code java.lang} on demand, so inside the vocabulary a
     * package named like one of them is obscured by it too (JLS 6.4.2): an enum of package {@code String} is
     * refused at the facade, naming {@code java.lang.String}.
     */
    @Test
    void refusesAMachineWhoseEnumPackageIsNamedLikeAJavaLangType() {
        List<Path> files = SourceFiles.write(directory, Map.of(
                "String.Doc",
                    "package String; public class Doc { public enum Status { DRAFT } public enum Step { GO } }",
                "fx.Docs", """
                    package fx;
                    import String.Doc;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Docs extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """));
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Docs"));
            assertEquals(DiagnosticCode.SG603.name(), failure.code());
            assertEquals("the vocabulary generated for the machine fx.Docs in package fx cannot name String.Doc.Status:"
                    + " the type java.lang.String obscures the package String there", failure.getMessage());
            assertEquals("fx/Docs.java", failure.location().orElseThrow().path());
        }
    }

    /**
     * The vocabulary of a pair is generated in the owner's package and names the peer machine and its state
     * enum there, so a type of the owner's package named like the peer's package is refused at the owner, naming it.
     */
    @Test
    void refusesAPeerWhosePackageTheOwnersPackageObscures() {
        Path sources = directory.resolve("src");
        SourceFiles.write(sources, Map.of(
                "shop.Order",
                    "package shop; public class Order { public enum Status { OPEN } public enum Step { GO } }",
                "shop.Orders", """
                    package shop;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Order.Status, Order.Step> {}
                    """,
                "fx.shop", "package fx; public class shop {}",
                "fx.Doc", "package fx; public class Doc { public enum Status { DRAFT } public enum Step { GO } }",
                "fx.Docs", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    import io.github.graphmaquina.api.Machine;
                    import shop.Orders;
                    @GenerateGraphApi
                    @GraphPeers(Orders.class)
                    public interface Docs extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """));

        CodegenException failure = assertThrows(CodegenException.class, () -> Bootstrap.inspect(
                new GenerationRequest(directory, List.of(sources), SourceFiles.classpath(),
                        directory.resolve("out/sources"), directory.resolve("out/processor"),
                        directory.resolve("out/classes"), directory.resolve("out/work"), "fx:main")));

        assertEquals(DiagnosticCode.SG603.name(), failure.code());
        assertEquals("the pair vocabulary generated for the machine fx.Docs over shop.Orders in package fx cannot"
                + " name shop.Orders: the type fx.shop obscures the package shop there", failure.getMessage());
        assertEquals("src/fx/Docs.java", failure.location().orElseThrow().path());
    }

    @Test
    void reportsAFacadeThatDoesNotSpecializeMachine() {
        Map<String, String> sources = Map.of(
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            CodegenException failure =
                    assertThrows(CodegenException.class, () -> MachineHeaders.resolve(index, "fx.Orders"));
            assertEquals(DiagnosticCode.SG601.name(), failure.code());
            assertEquals("fx.Orders does not extend io.github.graphmaquina.api.Machine", failure.getMessage());
        }
    }

    @Test
    void agreesWithJavacDirectSupertypesOnTheSameCompilableFixture() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { NEW, DONE }",
                "fx.DocTransition", TRANSITION,
                "fx.Order", "package fx; public class Order {}",
                "fx.Base", BASE,
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    @GenerateGraphApi
                    public interface Orders extends Base<Order, Status, DocTransition> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            GraphDescriptor graph = MachineHeaders.resolve(index, "fx.Orders");
            assertEquals(machineArgumentsAccordingToJavac(files, "fx.Orders"),
                    List.of(graph.entityType(), graph.stateType(), graph.transitionType()));
        }
    }

    /**
     * A header is spelled in the scope that encloses its declaration (JLS 6.3): the member types the declaration
     * declares or inherits are not visible in its extends clause, so neither shadows the package's own enum.
     */
    @Test
    void resolvesTheSupertypesOfAFacadeWithoutTheMemberTypesItDeclaresOrInherits() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { DRAFT, LIVE }",
                "fx.Step", "package fx; public enum Step { PUBLISH }",
                "fx.Doc", "package fx; public class Doc {}",
                "fx.Base", "package fx; public interface Base { enum Status { INHERITED } }",
                "fx.Docs", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Docs extends Machine<Doc, Status, Step> {
                        enum Status { ARCHIVED }
                    }
                    """,
                "fx.Pages", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Pages extends Base, Machine<Doc, Status, Step> {}
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            for (String facade : List.of("fx.Docs", "fx.Pages")) {
                GraphDescriptor graph = MachineHeaders.resolve(index, facade);
                assertEquals(List.of("fx.Doc", "fx.Status", "fx.Step"),
                        List.of(graph.entityType(), graph.stateType(), graph.transitionType()), facade);
                assertEquals(List.of("DRAFT", "LIVE"), graph.states(), facade);
                assertEquals(machineArgumentsAccordingToJavac(files, facade),
                        List.of(graph.entityType(), graph.stateType(), graph.transitionType()), facade);
            }
        }
    }

    /** The peers of a facade are named by its own annotation, which javac also resolves in the enclosing scope. */
    @Test
    void resolvesThePeersOfAFacadeWithoutTheMemberTypesItDeclares() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { DRAFT, LIVE }",
                "fx.Step", "package fx; public enum Step { PUBLISH }",
                "fx.Doc", "package fx; public class Doc {}",
                "fx.Pages", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Pages extends Machine<Doc, Status, Step> {}
                    """,
                "fx.Docs", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    @GraphPeers(Pages.class)
                    public interface Docs extends Machine<Doc, Status, Step> {
                        interface Pages {}
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            assertEquals(List.of("fx.Pages"), MachineHeaders.resolve(index, "fx.Docs").peers());
        }
    }

    private static TypeHeader.Member member(TypeHeader header, String name) {
        return header.members().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no member " + name + " on " + header.canonicalName()));
    }

    /** Compiles the same fixture for real and reads the {@code Machine} specialization javac itself computes. */
    private List<String> machineArgumentsAccordingToJavac(List<Path> files, String facade) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, SourceFiles.classpath());
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,
                    List.of(Files.createDirectories(directory.resolve("oracle-classes"))));
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            task.analyze();
            assertEquals(List.of(), diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(Object::toString)
                    .toList());
            Elements elements = task.getElements();
            Types types = task.getTypes();
            return machineArguments(types, elements.getTypeElement(facade).asType());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The type arguments of the {@code Machine} among the supertypes of {@code facade}, breadth first. */
    private static List<String> machineArguments(Types types, TypeMirror facade) {
        Deque<TypeMirror> pending = new ArrayDeque<>();
        pending.add(facade);
        while (!pending.isEmpty()) {
            TypeMirror current = pending.removeFirst();
            if (current instanceof DeclaredType declared
                    && ((TypeElement) declared.asElement()).getQualifiedName()
                            .contentEquals("io.github.graphmaquina.api.Machine")) {
                List<String> arguments = new ArrayList<>();
                for (TypeMirror argument : declared.getTypeArguments()) {
                    arguments.add(argument.toString());
                }
                return List.copyOf(arguments);
            }
            pending.addAll(types.directSupertypes(current));
        }
        throw new IllegalStateException("javac found no Machine supertype");
    }
}
