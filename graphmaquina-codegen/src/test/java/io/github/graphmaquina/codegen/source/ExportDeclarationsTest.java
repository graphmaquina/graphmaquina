package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies which declarations become exported symbols, that every candidate keeps the declaring type, role and
 * origin it was written with, and that neither file order nor an override decision changes the result.
 */
class ExportDeclarationsTest {

    @TempDir
    Path directory;

    private static final String ORDER = "package fx; public class Order {}";
    private static final String STATUS = "package fx; public enum Status { NEW, DONE }";
    private static final String TRANSITION =
            "package fx; public enum DocTransition { SUBMIT, PUBLISH, ARCHIVE }";

    @Test
    void exportsInheritedRolesUnderTheConcreteHostAndUnionsOverloadRoles() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Order", ORDER,
                "fx.Status", STATUS,
                "fx.BaseActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public abstract class BaseActions<E, S> {
                        @GraphAction
                        public void authorize(E entity) {}
                    }
                    """,
                "fx.ConcreteActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphBindings;
                    import io.github.graphmaquina.annotation.GraphGuard;
                    import io.github.graphmaquina.annotation.GraphHook;
                    @GraphBindings
                    public class ConcreteActions extends BaseActions<Order, Status> {
                        @Override
                        public void authorize(Order order) {}
                        @GraphGuard
                        public boolean check(Order order) { return true; }
                        @GraphHook
                        public void check(Order order, String reason) {}
                    }
                    """));

        assertEquals(List.of("fx.BaseActionsRefs.Authorize", "fx.ConcreteActionsRefs.Authorize",
                        "fx.ConcreteActionsRefs.Check"),
                symbols.stream().map(SymbolDescriptor::qualifiedName).toList());

        SymbolDescriptor authorize = symbol(symbols, "fx.ConcreteActionsRefs.Authorize");
        assertEquals("fx.ConcreteActions", authorize.host());
        assertEquals("authorize", authorize.member());
        assertEquals(Set.of(SymbolKind.ACTION), authorize.kinds());
        assertEquals(Optional.empty(), authorize.property());
        assertEquals(1, authorize.candidates().size());

        MemberDescriptor inherited = authorize.candidates().get(0);
        assertEquals("fx.BaseActions", inherited.declaringType());
        assertEquals("authorize", inherited.name());
        assertEquals(List.of("E"), inherited.parameterTypes());
        assertEquals("void", inherited.returnType());
        assertEquals(SymbolKind.ACTION, inherited.role());
        assertFalse(inherited.staticMember());
        assertEquals(Path.of("fx", "BaseActions.java").toString(), inherited.origin().path());
        assertEquals(4L, inherited.origin().line());

        SymbolDescriptor check = symbol(symbols, "fx.ConcreteActionsRefs.Check");
        assertEquals(Set.of(SymbolKind.GUARD, SymbolKind.HOOK), check.kinds());
        assertEquals(List.of(SymbolKind.GUARD, SymbolKind.HOOK),
                check.candidates().stream().map(MemberDescriptor::role).toList());
        assertEquals(List.of(List.of("fx.Order"), List.of("fx.Order", "java.lang.String")),
                check.candidates().stream().map(MemberDescriptor::parameterTypes).toList());
        assertEquals(List.of("fx.ConcreteActions", "fx.ConcreteActions"),
                check.candidates().stream().map(MemberDescriptor::declaringType).toList());
    }

    @Test
    void keepsEveryMarkedDeclarationWithoutDecidingOverrides() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Order", ORDER,
                "fx.Status", STATUS,
                "fx.BaseActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    import io.github.graphmaquina.annotation.GraphGuard;
                    public abstract class BaseActions<E, S> {
                        @GraphAction
                        public void authorize(E entity) {}
                        @GraphGuard
                        public boolean risky(E entity) { return true; }
                    }
                    """,
                "fx.ConcreteActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphBindings;
                    import io.github.graphmaquina.annotation.GraphHook;
                    @GraphBindings
                    public class ConcreteActions extends BaseActions<Order, Status> {
                        @Override
                        public void authorize(Order order) {}
                        public void authorize(Status status) {}
                        @Override
                        @GraphHook
                        public boolean risky(Order order) { return false; }
                    }
                    """));

        SymbolDescriptor authorize = symbol(symbols, "fx.ConcreteActionsRefs.Authorize");
        assertEquals(Set.of(SymbolKind.ACTION), authorize.kinds());
        assertEquals(List.of("fx.BaseActions"),
                authorize.candidates().stream().map(MemberDescriptor::declaringType).toList());
        assertEquals(List.of(List.of("E")),
                authorize.candidates().stream().map(MemberDescriptor::parameterTypes).toList());

        SymbolDescriptor risky = symbol(symbols, "fx.ConcreteActionsRefs.Risky");
        assertEquals(Set.of(SymbolKind.GUARD, SymbolKind.HOOK), risky.kinds());
        assertEquals(List.of("fx.ConcreteActions", "fx.BaseActions"),
                risky.candidates().stream().map(MemberDescriptor::declaringType).toList());
        assertEquals(List.of(SymbolKind.HOOK, SymbolKind.GUARD),
                risky.candidates().stream().map(MemberDescriptor::role).toList());
        assertEquals(Path.of("fx", "BaseActions.java").toString(),
                risky.candidates().get(1).origin().path());
    }

    @Test
    void exportsMarkedMembersInheritedFromABinaryBase() {
        Path jar = SourceFiles.compileToJar(directory.resolve("build"),
                Map.of("fx.BaseActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public abstract class BaseActions<E> {
                        @GraphAction
                        public void authorize(E entity) {}
                    }
                    """),
                directory.resolve("build").resolve("base.jar"));
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.add(jar);

        List<Path> files = SourceFiles.write(directory.resolve("app"), Map.of(
                "fx.Order", ORDER,
                "fx.ConcreteActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphBindings;
                    @GraphBindings
                    public class ConcreteActions extends BaseActions<Order> {}
                    """));
        List<SourceUnit> units = SourceDeclarations.parse(directory.resolve("app"), files);
        try (DeclarationIndex index = DeclarationIndex.open(units, classpath)) {
            List<SymbolDescriptor> symbols = ExportDeclarations.collect(index);

            assertEquals(List.of("fx.ConcreteActionsRefs.Authorize"),
                    symbols.stream().map(SymbolDescriptor::qualifiedName).toList());
            SymbolDescriptor authorize = symbols.get(0);
            assertEquals("fx.ConcreteActions", authorize.host());
            assertEquals(Set.of(SymbolKind.ACTION), authorize.kinds());
            assertEquals(1, authorize.candidates().size());

            MemberDescriptor inherited = authorize.candidates().get(0);
            assertEquals("fx.BaseActions", inherited.declaringType());
            assertEquals(SymbolKind.ACTION, inherited.role());
            assertEquals(List.of("E"), inherited.parameterTypes());
            assertEquals("void", inherited.returnType());
            assertEquals(Path.of("fx", "ConcreteActions.java").toString(), inherited.origin().path());
            assertEquals(3L, inherited.origin().line());
        }
    }

    @Test
    void reportsAMethodThatDeclaresTwoRoles() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Order", ORDER,
                "fx.PaymentActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    import io.github.graphmaquina.annotation.GraphGuard;
                    public class PaymentActions {
                        @GraphAction
                        @GraphGuard
                        public void authorize(Order order) {}
                    }
                    """)));

        assertEquals("SG604", failure.code());
        assertEquals("the member authorize of fx.PaymentActions declares more than one graph role: "
                + "ACTION and GUARD", failure.getMessage());
        assertEquals(Path.of("fx", "PaymentActions.java").toString(),
                failure.location().orElseThrow().path());
        assertEquals(5L, failure.location().orElseThrow().line());
    }

    @Test
    void exportsAPrivateFieldAsPropertyMetadataOnly() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Order", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Order {
                        @GraphProperty
                        private Status status;
                    }
                    """,
                "fx.Status", STATUS));

        assertEquals(List.of("fx.OrderRefs.Status"),
                symbols.stream().map(SymbolDescriptor::qualifiedName).toList());
        SymbolDescriptor status = symbols.get(0);
        assertEquals("fx.Order", status.host());
        assertEquals("status", status.member());
        assertEquals(Set.of(SymbolKind.PROPERTY), status.kinds());
        assertEquals(List.of(), status.candidates());

        PropertyDescriptor property = status.property().orElseThrow();
        assertEquals("fx.Order", property.declaringType());
        assertEquals("status", property.logicalName());
        assertEquals("status", property.declarationName());
        assertFalse(property.directGetter());
        assertEquals(Path.of("fx", "Order.java").toString(), property.origin().path());
        assertEquals(4L, property.origin().line());
    }

    @Test
    void exportsADirectGetterAsTheReaderItMarks() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Order", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Order {
                        @GraphProperty
                        public String getURL() { return null; }
                    }
                    """));

        assertEquals(List.of("fx.OrderRefs.URL"),
                symbols.stream().map(SymbolDescriptor::qualifiedName).toList());
        SymbolDescriptor url = symbols.get(0);
        assertEquals("URL", url.member());
        assertEquals(Set.of(SymbolKind.PROPERTY), url.kinds());
        assertEquals(List.of(), url.candidates());
        assertEquals(new PropertyDescriptor("fx.Order", "URL", "getURL", true, url.origin()),
                url.property().orElseThrow());
    }

    @Test
    void reportsAPropertyDeclaredByBothAFieldAndAGetter() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Order", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Order {
                        @GraphProperty
                        private String status;
                        @GraphProperty
                        public String getStatus() { return status; }
                    }
                    """)));

        assertEquals("SG603", failure.code());
        assertEquals("the property status of fx.Order is declared twice, by the field status and by the "
                + "method getStatus", failure.getMessage());
        assertEquals(6L, failure.location().orElseThrow().line());
    }

    @Test
    void reportsAPropertyDeclaredByBothAFieldAndAFluentGetterOfTheSameName() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Order", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Order {
                        @GraphProperty
                        private String status;
                        @GraphProperty
                        public String status() { return status; }
                    }
                    """)));

        assertEquals("SG603", failure.code());
        assertEquals("the property status of fx.Order is declared twice, by the field status and by the "
                + "method status", failure.getMessage());
        assertEquals(6L, failure.location().orElseThrow().line());
    }

    @Test
    void reportsANoArgumentMethodThatSharesTheSymbolNameOfAField() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Order", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Order {
                        @GraphProperty
                        private String status;
                        @GraphAction
                        public void status() {}
                    }
                    """)));

        assertEquals("SG603", failure.code());
        assertEquals("the symbol status of fx.Order is exported as both a method and a property",
                failure.getMessage());
    }

    @Test
    void exportsAFieldAndAGetterThatSpellTheSameDeclarationName() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Order", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class Order {
                        @GraphProperty
                        private String getStatus;
                        @GraphProperty
                        public String getStatus() { return getStatus; }
                    }
                    """));

        assertEquals(List.of("fx.OrderRefs.GetStatus", "fx.OrderRefs.Status"),
                symbols.stream().map(SymbolDescriptor::qualifiedName).toList());
        assertEquals(new PropertyDescriptor("fx.Order", "getStatus", "getStatus", false,
                        symbols.get(0).origin()),
                symbols.get(0).property().orElseThrow());
        assertEquals(new PropertyDescriptor("fx.Order", "status", "getStatus", true,
                        symbols.get(1).origin()),
                symbols.get(1).property().orElseThrow());
        assertEquals(4L, symbols.get(0).origin().line());
        assertEquals(6L, symbols.get(1).origin().line());
    }

    @Test
    void reportsASymbolNameSharedByAMethodAndAProperty() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Order", ORDER,
                "fx.PaymentActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    public class PaymentActions {
                        @GraphProperty
                        private String status;
                        @GraphAction
                        public void status(Order order) {}
                    }
                    """)));

        assertEquals("SG603", failure.code());
        assertEquals("the symbol status of fx.PaymentActions is exported as both a method and a property",
                failure.getMessage());
    }

    @Test
    void reportsTwoHostsThatWouldGenerateTheSameContainer() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Outer", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Outer {
                        public static class Inner {
                            @GraphAction
                            public void run() {}
                        }
                    }
                    """,
                "fx.Outer_Inner", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Outer_Inner {
                        @GraphAction
                        public void run() {}
                    }
                    """)));

        assertEquals("SG603", failure.code());
        assertEquals("the references container fx.OuterInnerRefs is generated for both fx.Outer.Inner at "
                + Path.of("fx", "Outer.java") + ":4 and fx.Outer_Inner at "
                + Path.of("fx", "Outer_Inner.java") + ":3", failure.getMessage());
        assertEquals(Path.of("fx", "Outer.java").toString(), failure.location().orElseThrow().path());
    }

    @Test
    void rejectsAFlatHostThatNormalizesToTheSameNameAsANestedHost() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
            "fx.Outer", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class Outer {
                    public static class Inner { @GraphAction public void run() {} }
                }
                """,
            "fx.OuterInner", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class OuterInner { @GraphAction public void run() {} }
                """)));
        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("fx.Outer.Inner"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fx.OuterInner"), failure.getMessage());
        assertEquals("fx/Outer.java", failure.location().orElseThrow().path());
    }

    @Test
    void rejectsNormalizedMemberCollisionsAtTheirDeclarations() {
        for (String[] names : List.of(new String[] {"id", "_id"},
                new String[] {"charge", "Charge"})) {
            CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Host", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Host {
                        @GraphAction public void %s() {}
                        @GraphAction public void %s() {}
                    }
                    """.formatted(names[0], names[1]))));
            assertEquals("SG603", failure.code());
            assertTrue(failure.getMessage().contains(names[0]), failure.getMessage());
            assertTrue(failure.getMessage().contains(names[1]), failure.getMessage());
            assertTrue(failure.getMessage().contains("Host.java:4"), failure.getMessage());
            assertTrue(failure.getMessage().contains("Host.java:5"), failure.getMessage());
            assertEquals(5L, failure.location().orElseThrow().line());
        }
    }

    @Test
    void rejectsAMemberThatNormalizesToItsOwnContainer() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
            "fx.Host", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class Host {
                    @GraphAction public void host_refs() {}
                }
                """)));
        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("HostRefs"), failure.getMessage());
        assertTrue(failure.getMessage().contains("host_refs"), failure.getMessage());
        assertEquals(4L, failure.location().orElseThrow().line());
    }

    @Test
    void doesNotNormalizeTypesThatExportNothing() {
        assertEquals(List.of(), collect(Map.of("fx.___", "package fx; public class ___ {}")));
    }

    @Test
    void rejectsInvalidNormalizedMembersAndHostsWithSourceDiagnostics() {
        for (String member : List.of("___", "_1id")) {
            CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Host", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Host {
                        @GraphAction public void %s() {}
                    }
                    """.formatted(member))));
            assertEquals("SG603", failure.code());
            assertEquals(4L, failure.location().orElseThrow().line());
            assertTrue(failure.getMessage().contains(member), failure.getMessage());
        }
        CodegenException hostFailure = assertThrows(CodegenException.class, () -> collect(Map.of(
            "fx.___", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class ___ { @GraphAction public void run() {} }
                """)));
        assertEquals("SG603", hostFailure.code());
        assertEquals(3L, hostFailure.location().orElseThrow().line());
    }

    @Test
    void producesTheSameSymbolsWhateverTheFileOrder() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Order", ORDER);
        sources.put("fx.Status", STATUS);
        sources.put("fx.BaseActions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphProperty;
            public abstract class BaseActions<E, S> {
                @GraphProperty
                protected String reason;
                @GraphAction
                public void authorize(E entity) {}
            }
            """);
        sources.put("fx.ConcreteActions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphBindings;
            import io.github.graphmaquina.annotation.GraphGuard;
            @GraphBindings
            public class ConcreteActions extends BaseActions<Order, Status> {
                @GraphGuard
                public boolean risky(Order order) { return true; }
            }
            """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<Path> reversed = new ArrayList<>(files);
        Collections.reverse(reversed);

        List<SymbolDescriptor> forward = collect(files);
        List<SymbolDescriptor> backward = collect(reversed);

        assertEquals(List.of("fx.BaseActionsRefs.Authorize", "fx.BaseActionsRefs.Reason",
                        "fx.ConcreteActionsRefs.Authorize", "fx.ConcreteActionsRefs.Reason",
                        "fx.ConcreteActionsRefs.Risky"),
                forward.stream().map(SymbolDescriptor::qualifiedName).toList());
        assertEquals(forward, backward);
    }

    @Test
    void inspectsSourceDirectoriesIntoGraphsAndSymbols() {
        Path sources = directory.resolve("src");
        SourceFiles.write(sources, Map.of(
                "fx.Order", ORDER,
                "fx.Status", STATUS,
                "fx.DocTransition", TRANSITION,
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status, DocTransition> {}
                    """,
                "fx.PaymentActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class PaymentActions {
                        @GraphAction
                        public void authorize(Order order) {}
                    }
                    """));
        GenerationRequest request = new GenerationRequest(directory, List.of(sources),
                SourceFiles.classpath(), directory.resolve("out/sources"),
                directory.resolve("out/processor"), directory.resolve("out/classes"),
                directory.resolve("out/work"), "fx:demo:main");

        GenerationModel model = Bootstrap.inspect(request);

        assertEquals(List.of("fx.Orders"), model.graphs().stream()
                .map(GraphDescriptor::facade).toList());
        assertEquals(List.of("NEW", "DONE"), model.graphs().get(0).states());
        assertEquals("fx.DocTransition", model.graphs().get(0).transitionType());
        assertEquals(List.of("SUBMIT", "PUBLISH", "ARCHIVE"), model.graphs().get(0).transitions());
        assertEquals(List.of("fx.PaymentActionsRefs.Authorize"), model.symbols().stream()
                .map(SymbolDescriptor::qualifiedName).toList());
        assertEquals(Path.of("src", "fx", "PaymentActions.java").toString(),
                model.symbols().get(0).origin().path());
    }

    private List<SymbolDescriptor> collect(Map<String, String> sources) {
        return collect(SourceFiles.write(directory, sources));
    }

    private List<SymbolDescriptor> collect(List<Path> files) {
        List<SourceUnit> units = SourceDeclarations.parse(directory, files);
        try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
            return ExportDeclarations.collect(index);
        }
    }

    private static SymbolDescriptor symbol(List<SymbolDescriptor> symbols, String qualifiedName) {
        return symbols.stream()
                .filter(symbol -> symbol.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no symbol " + qualifiedName));
    }
}
