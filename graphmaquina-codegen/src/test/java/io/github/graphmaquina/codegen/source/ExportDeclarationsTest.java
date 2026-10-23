package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.util.JavacTask;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
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

        assertInheritedAuthorizeIsExportedUnderTheConcreteHost(symbols);
        assertOverloadsOfCheckUnionTheirRoles(symbols);
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
        assertEquals("fx/BaseActions.java",
                risky.candidates().get(1).origin().path());
    }

    /**
     * A host marked {@code @GraphBindings} exports the marked static methods it inherits and no other, since
     * generated code calls a static binding through the host: never a static method of an interface, which no type
     * inherits, nor one that the host or a superclass between hides with a method of the same name and parameter
     * types, raw or parameterized, which is exported in its place when it is marked itself.
     */
    @Test
    void exportsOnlyTheStaticMethodsTheHostInherits() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Order", ORDER,
                "fx.Hooks", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphHook;
                    public interface Hooks {
                        @GraphHook
                        static void announced(Order order) {}
                    }
                    """,
                "fx.BaseActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    import java.util.List;
                    public class BaseActions {
                        @GraphAction
                        public static void archive(Order order) {}
                        @GraphAction
                        public static void approve(Order order) {}
                        @GraphAction
                        public static void sort(List<Order> orders) {}
                        @GraphAction
                        public static void keep(Order order) {}
                    }
                    """,
                "fx.MiddleActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class MiddleActions extends BaseActions {
                        @GraphAction
                        public static void approve(Order order) {}
                    }
                    """,
                "fx.Host", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphBindings;
                    import java.util.List;
                    @GraphBindings
                    public class Host extends MiddleActions implements Hooks {
                        public static void archive(Order order) {}
                        @SuppressWarnings("rawtypes")
                        public static void sort(List orders) {}
                    }
                    """));

        assertOnlyTheStaticsTheHostInheritsAreExported(symbols);
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
            assertEquals("fx/ConcreteActions.java", inherited.origin().path());
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
        assertEquals("fx/PaymentActions.java",
                failure.location().orElseThrow().path());
        assertEquals(5L, failure.location().orElseThrow().line());
    }

    /**
     * A parent lookup is exported with its own role, static or instance alike, and each candidate keeps the
     * signature its declaration spells: the child entity in, an {@code Optional} of the parent out.
     */
    @Test
    void exportsStaticAndInstanceLookupsWithTheLookupRole() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Order", ORDER,
                "fx.Payment", "package fx; public class Payment {}",
                "fx.OrderLookups", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphLookup;
                    import java.util.Optional;
                    public class OrderLookups {
                        @GraphLookup
                        public static Optional<Order> orderPaying(Payment payment) { return Optional.empty(); }
                        @GraphLookup
                        public Optional<Order> orderRefunding(Payment payment) { return Optional.empty(); }
                    }
                    """));

        assertEquals(List.of("fx.OrderLookupsRefs.OrderPaying", "fx.OrderLookupsRefs.OrderRefunding"),
                symbols.stream().map(SymbolDescriptor::qualifiedName).toList());
        SymbolDescriptor paying = symbol(symbols, "fx.OrderLookupsRefs.OrderPaying");
        assertEquals(Set.of(SymbolKind.LOOKUP), paying.kinds());
        assertEquals(Optional.empty(), paying.property());
        MemberDescriptor candidate = paying.candidates().get(0);
        assertEquals(List.of("fx.Payment"), candidate.parameterTypes());
        assertEquals("java.util.Optional<fx.Order>", candidate.returnType());
        assertEquals(SymbolKind.LOOKUP, candidate.role());
        assertTrue(candidate.staticMember());
        SymbolDescriptor refunding = symbol(symbols, "fx.OrderLookupsRefs.OrderRefunding");
        assertEquals(Set.of(SymbolKind.LOOKUP), refunding.kinds());
        assertFalse(refunding.candidates().get(0).staticMember());
    }

    @Test
    void reportsALookupThatDeclaresASecondRole() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "fx.Order", ORDER,
                "fx.OrderLookups", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphCondition;
                    import io.github.graphmaquina.annotation.GraphLookup;
                    import java.util.Optional;
                    public class OrderLookups {
                        @GraphLookup
                        @GraphCondition
                        public Optional<Order> orderOf(Order order) { return Optional.of(order); }
                    }
                    """)));

        assertEquals("SG604", failure.code());
        assertEquals("the member orderOf of fx.OrderLookups declares more than one graph role: "
                + "CONDITION and LOOKUP", failure.getMessage());
        assertEquals(6L, failure.location().orElseThrow().line());
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
        assertEquals("fx/Order.java", property.origin().path());
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
                + "fx/Outer.java:4 and fx.Outer_Inner at fx/Outer_Inner.java:3", failure.getMessage());
        assertEquals("fx/Outer.java", failure.location().orElseThrow().path());
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

    /**
     * An inner class that extends a sibling inner class names other inner types of the same owner by their
     * simple names. Finding the owner of such a name walks the supertypes of the declaration that spells it,
     * which spell that very name again; the walk must end and render the use as javac does.
     */
    @Test
    void exportsAMemberOfAnInnerClassThatExtendsASiblingInnerClass() {
        List<SymbolDescriptor> symbols = collect(Map.of("fx.Desk", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class Desk {
                    public class Ticket {}
                    public class Note {}
                    public class UrgentTicket extends Ticket {
                        @GraphAction
                        public void escalate(Ticket previous, Note note) {}
                    }
                }
                """));

        SymbolDescriptor escalate = symbol(symbols, "fx.DeskUrgentTicketRefs.Escalate");
        assertEquals(List.of(List.of("fx.Desk.Ticket", "fx.Desk.Note")),
                escalate.candidates().stream().map(MemberDescriptor::parameterTypes).toList());
    }

    /** The same shape under a generic owner: the inner types keep the owner's own type variable. */
    @Test
    void keepsTheGenericOwnerOfASiblingInnerTypeNamedByAnInnerSubclass() {
        List<SymbolDescriptor> symbols = collect(Map.of("fx.Desk", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class Desk<T> {
                    public class Ticket {}
                    public class UrgentTicket extends Ticket {
                        @GraphAction
                        public Ticket escalate(Ticket previous) { return previous; }
                    }
                }
                """));

        MemberDescriptor escalate = symbol(symbols, "fx.DeskUrgentTicketRefs.Escalate").candidates().get(0);
        assertEquals(List.of("fx.Desk<T>.Ticket"), escalate.parameterTypes());
        assertEquals("fx.Desk<T>.Ticket", escalate.returnType());
    }

    /**
     * A class that extends a member class of its own is an inheritance cycle javac rejects, but the generator reads
     * the sources before javac does. Finding the owner of an inner type spelled inside such a hierarchy leads back
     * to the owner being computed; the walk takes it raw there instead of recursing until the stack overflows, so
     * the build reaches javac and its own report of the cycle.
     */
    @Test
    void endsTheOwnerWalkOfAnInheritanceCycleThroughAnEnclosingClass() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Base", "package fx; public class Base { public class N {} }",
                "fx.G", """
                    package fx;
                    import fx.Base.N;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class G extends G.E {
                        public class E extends N {
                            @GraphAction
                            public void act(N n) {}
                        }
                    }
                    """));

        assertEquals(List.of(List.of("fx.Base.N")), symbol(symbols, "fx.GERefs.Act").candidates().stream()
                .map(MemberDescriptor::parameterTypes).toList());
    }

    /**
     * A class declared in an interface is implicitly static (JLS 9.5), so a use of it names no enclosing
     * instance: the processor renders javac's type as {@code fx.Ops.Ctx}, never with the interface's arguments.
     */
    @Test
    void rendersAMemberClassOfAGenericInterfaceWithoutTheInterfaceAsItsOwner() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Ops", """
                    package fx;
                    public interface Ops<T> {
                        class Ctx {}
                    }
                    """,
                "fx.Doc", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Doc implements Ops<String> {
                        @GraphAction
                        public void audit(Ctx context) {}
                    }
                    """));

        assertEquals(List.of(List.of("fx.Ops.Ctx")), symbol(symbols, "fx.DocRefs.Audit").candidates().stream()
                .map(MemberDescriptor::parameterTypes).toList());
    }

    /**
     * The supertype a binding host spells is resolved where javac resolves it, outside the host's body: a member
     * type of the host that shares the supertype's name does not hide the members the host inherits.
     */
    @Test
    void exportsTheBindingsInheritedFromASupertypeThatAMemberTypeOfTheHostShadowsInItsBody() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "fx.Base", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Base {
                        @GraphAction
                        public void archive(Object doc) {}
                    }
                    """,
                "fx.Handlers", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphBindings;
                    @GraphBindings
                    public class Handlers extends Base {
                        public static class Base {}
                    }
                    """));

        assertEquals(List.of("fx.BaseRefs.Archive", "fx.HandlersRefs.Archive"),
                symbols.stream().map(SymbolDescriptor::qualifiedName).toList());
        assertEquals("fx.Base", symbol(symbols, "fx.HandlersRefs.Archive").candidates().get(0).declaringType());
    }

    /**
     * An inner type inherited through a supertype is found, and specialized, through the supertype the header
     * spells, even when a member type of the subclass hides that supertype's name inside the body.
     */
    @Test
    void rendersAnInheritedInnerTypeThroughASupertypeThatAMemberTypeShadowsInTheBody() {
        Map<String, String> sources = Map.of(
                "fx.Box", """
                    package fx;
                    public class Box<T> {
                        public class Item {}
                    }
                    """,
                "fx.Shelf", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Shelf extends Box<String> {
                        public static class Box<X> {}
                        @GraphAction
                        public void stock(Item item) {}
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);

        MemberDescriptor stock = symbol(collect(files), "fx.ShelfRefs.Stock").candidates().get(0);
        assertEquals(List.of("fx.Box<java.lang.String>.Item"), stock.parameterTypes());
        assertEquals(parameterTypesAccordingToJavac(files, "fx.Shelf", "stock"), stock.parameterTypes());
    }

    /**
     * A private member type is not inherited, and one with package access is inherited only inside its own
     * package (JLS 8.2): neither hides the package's own type of the same name from a subclass.
     */
    @Test
    void doesNotInheritAMemberTypeTheSubclassCannotAccess() {
        Map<String, String> sources = Map.of(
                "fx.Status", "package fx; public enum Status { DRAFT, LIVE }",
                "fx.Base", "package fx; public class Base { private enum Status { INTERNAL } }",
                "fx.Handlers", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphGuard;
                    public class Handlers extends Base {
                        @GraphGuard
                        public boolean ready(Status status) { return true; }
                    }
                    """,
                "other.Base", "package other; public class Base { enum Status { ELSEWHERE } }",
                "fx.Remote", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphGuard;
                    public class Remote extends other.Base {
                        @GraphGuard
                        public boolean ready(Status status) { return true; }
                    }
                    """);
        List<Path> files = SourceFiles.write(directory, sources);
        List<SymbolDescriptor> symbols = collect(files);

        for (String host : List.of("fx.Handlers", "fx.Remote")) {
            List<String> parameterTypes = symbol(symbols, host + "Refs.Ready").candidates().get(0).parameterTypes();
            assertEquals(List.of("fx.Status"), parameterTypes, host);
            assertEquals(parameterTypesAccordingToJavac(files, host, "ready"), parameterTypes, host);
        }
    }

    /** A member type with package access read from a binary is still inherited inside its own package. */
    @Test
    void inheritsAPackageAccessMemberTypeOfABinaryBaseInTheSamePackage() {
        Path jar = SourceFiles.compileToJar(directory.resolve("build"),
                Map.of("fx.Base", "package fx; public class Base { enum Status { INTERNAL } }"),
                directory.resolve("build").resolve("base.jar"));
        List<Path> classpath = new ArrayList<>(SourceFiles.classpath());
        classpath.add(jar);
        List<Path> files = SourceFiles.write(directory.resolve("app"), Map.of(
                "fx.Status", "package fx; public enum Status { DRAFT, LIVE }",
                "fx.Handlers", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphGuard;
                    public class Handlers extends Base {
                        @GraphGuard
                        public boolean ready(Status status) { return true; }
                    }
                    """));
        List<SourceUnit> units = SourceDeclarations.parse(directory.resolve("app"), files);
        try (DeclarationIndex index = DeclarationIndex.open(units, classpath)) {
            assertEquals(List.of("fx.Base.Status"), symbol(ExportDeclarations.collect(index), "fx.HandlersRefs.Ready")
                    .candidates().get(0).parameterTypes());
        }
    }

    /** A binding host of the unnamed package inherits from a sibling base, which its simple name resolves to. */
    @Test
    void exportsTheBindingsAHostOfTheUnnamedPackageInheritsFromASibling() {
        List<SymbolDescriptor> symbols = collect(Map.of(
                "BaseHandlers", """
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class BaseHandlers {
                        @GraphAction
                        public void archive(Object doc) {}
                    }
                    """,
                "Handlers", """
                    import io.github.graphmaquina.annotation.GraphAction;
                    import io.github.graphmaquina.annotation.GraphBindings;
                    @GraphBindings
                    public class Handlers extends BaseHandlers {
                        @GraphAction
                        public void publish(Object doc) {}
                    }
                    """));

        assertEquals(List.of("BaseHandlersRefs.Archive", "HandlersRefs.Archive", "HandlersRefs.Publish"),
                symbols.stream().map(SymbolDescriptor::qualifiedName).toList());
        assertEquals("BaseHandlers", symbol(symbols, "HandlersRefs.Archive").candidates().get(0).declaringType());
    }

    /**
     * A local or anonymous class has no canonical name, so no references container can name it as a host: a marker
     * written in one, on one of its members or on the class itself, is refused where it is written, as one in a
     * private class is, wherever the class is declared, and even in a class that nothing marked encloses.
     */
    @Test
    void refusesAMarkerWrittenInALocalOrAnonymousClass() {
        Map<String, Long> lines = new LinkedHashMap<>();
        Map<String, String> shops = new LinkedHashMap<>();
        putShopsWithAMarkerInALocalOrAnonymousClass(shops, lines);
        for (Map.Entry<String, String> shop : shops.entrySet()) {
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> collect(Map.of("fx.Order", ORDER, "fx.Shop", shop.getValue())), shop.getKey());

            assertEquals("SG604", failure.code());
            assertEquals("the marker @" + shop.getKey() + " is written in a local or anonymous class of fx.Shop,"
                    + " which no references container can name, so it exports nothing: mark a member of a top-level"
                    + " or member class instead", failure.getMessage());
            assertEquals("fx/Shop.java", failure.location().orElseThrow().path());
            assertEquals(lines.get(shop.getKey()), failure.location().orElseThrow().line());
        }

        assertEquals(List.of(), collect(Map.of("fx.Order", ORDER, "fx.Shop", """
                package fx;
                public class Shop {
                    public static Object handlers() {
                        class Handlers {
                            @Deprecated
                            public void archive(Order order) {}
                        }
                        return new Handlers();
                    }
                }
                """)));
    }

    /**
     * In the body of a local or anonymous class, javac reads a name as a member type of that class first, declared
     * or inherited, then of each class enclosing it there, and only then as the file's imports read it. A marker
     * spelled like a StateGraph marker that such a member type takes over is another annotation, so nothing is
     * refused and the file still exports its real marker. A supertype the generator cannot resolve, such as one of a
     * library missing from its classpath or a local class, may declare such a member type too, so a marker below it
     * is let through. A marker on a local class itself is read outside its body, where its own supertypes declare
     * nothing in scope, and is still refused, as is one in the class body of a constant of a local enum, whose
     * supertype is that enum.
     */
    @Test
    void readsAMarkerNameInALocalOrAnonymousClassAsAMemberTypeItTakesOver() {
        String lib = """
                package fx;
                public class Lib {
                    public @interface GraphAction {}
                    public @interface GraphBindings {}
                    public static class Holder {
                        public @interface GraphGuard {}
                    }
                    public class Part extends Holder {}
                }
                """;
        String shop = """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphBindings;
                import io.github.graphmaquina.annotation.GraphGuard;
                public class Shop {
                    @GraphAction
                    public void archive(Order order) {}
                    public static Object handlers() {
                %s
                    }
                }
                """;
        Map<String, String> bodies = bodiesWritingAMemberTypeOfLibAsAMarker();
        bodies.putAll(bodiesWritingAMemberTypeOfLibHolderAsAMarker());
        assertEachBodyExportsOnlyTheMarkerOfShop(lib, shop, bodies);

        assertEquals(List.of("fx.ShopRefs.Archive"), collect(Map.of("fx.Order", ORDER, "fx.Shop", shop.formatted("""
                        return new Generated() {
                            @GraphAction
                            void run() {}
                        };
                """))).stream().map(SymbolDescriptor::qualifiedName).toList());

        assertEachMarkerOnALocalClassOrEnumConstantIsRefused(lib, shop);
    }

    /**
     * The references container is a top-level type of the host's package and names its host by a class
     * literal, so a host that is private, or nested in a private type, is refused at the host. A host that is
     * only package-private, or a member of an interface, is still accessible there.
     */
    @Test
    void refusesAHostTheReferencesContainerCannotName() {
        Map<String, String> hosts = Map.of(
                "fx.Shop.Handlers", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Shop {
                        private static final class Handlers {
                            @GraphAction
                            public void archive(Order order) {}
                        }
                    }
                    """,
                "fx.Shop.Inner", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Shop {
                        private static class Inner {
                            public static class Handlers {
                                @GraphAction
                                public void archive(Order order) {}
                            }
                        }
                    }
                    """);
        assertEachPrivateHostIsRefusedAtTheHost(hosts);

        List<SymbolDescriptor> accepted = collect(Map.of(
                "fx.Order", ORDER,
                "fx.Shop", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Shop {
                        static final class Handlers {
                            @GraphAction
                            public void archive(Order order) {}
                        }
                        interface Api {
                            class Hooks {
                                @GraphAction
                                public void alert(Order order) {}
                            }
                        }
                    }
                    """));
        assertEquals(List.of("fx.ShopApiHooksRefs.Alert", "fx.ShopHandlersRefs.Archive"),
                accepted.stream().map(SymbolDescriptor::qualifiedName).toList());
    }

    /**
     * The references container names StateGraph's {@code io} package, {@code java.lang.SuppressWarnings} and its host
     * qualified, so a type of the host's package named like the first segment of any of them obscures it there
     * (JLS 6.4.2). That is refused at the host, naming the type.
     */
    @Test
    void refusesAHostWhosePackageObscuresAPackageItsReferencesContainerNames() {
        Map<String, String> obscurers = new LinkedHashMap<>();
        obscurers.put("fx.io", "io.github.graphmaquina");
        obscurers.put("fx.java", "java.lang.SuppressWarnings");
        obscurers.put("fx.fx", "fx.Shop");
        for (Map.Entry<String, String> obscurer : obscurers.entrySet()) {
            Path project = directory.resolve(obscurer.getKey());
            List<Path> files = SourceFiles.write(project, Map.of(
                    obscurer.getKey(), "package fx; public class " + obscurer.getKey().substring(3) + " {}",
                    "fx.Order", ORDER,
                    "fx.Shop", """
                        package fx;
                        import io.github.graphmaquina.annotation.GraphAction;
                        public class Shop {
                            @GraphAction
                            public void archive(Order order) {}
                        }
                        """));
            List<SourceUnit> units = SourceDeclarations.parse(project, files);
            try (DeclarationIndex index = DeclarationIndex.open(units, SourceFiles.classpath())) {
                CodegenException failure =
                        assertThrows(CodegenException.class, () -> ExportDeclarations.collect(index));
                assertEquals("SG603", failure.code(), obscurer.getKey());
                assertEquals("the references container generated for the host fx.Shop in package fx cannot name "
                        + obscurer.getValue() + ": the type " + obscurer.getKey() + " obscures the package "
                        + obscurer.getValue().substring(0, obscurer.getValue().indexOf('.')) + " there",
                        failure.getMessage());
                assertEquals("fx/Shop.java", failure.location().orElseThrow().path());
            }
        }
    }

    /**
     * Every compilation unit imports the public types of {@code java.lang} on demand, so inside the references
     * container a host package named like one of them is obscured by it (JLS 6.4.2): a host of package
     * {@code String} is refused, naming {@code java.lang.String}.
     */
    @Test
    void refusesAHostWhosePackageIsNamedLikeAJavaLangType() {
        CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of(
                "String.Shop", """
                    package String;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Shop {
                        @GraphAction
                        public void archive(Object order) {}
                    }
                    """)));

        assertEquals("SG603", failure.code());
        assertEquals("the references container generated for the host String.Shop in package String cannot name"
                + " String.Shop: the type java.lang.String obscures the package String there", failure.getMessage());
        assertEquals("String/Shop.java", failure.location().orElseThrow().path());
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
        assertEquals("src/fx/PaymentActions.java",
                model.symbols().get(0).origin().path());
    }

    /**
     * A build compiles the sources it reaches through a symbolic link, whether the link is a package directory
     * or the source root itself, so the generator reads them too, at the path the build reaches them by.
     */
    @Test
    void inspectsSourcesReachedThroughSymbolicLinks() throws IOException {
        Path shared = directory.resolve("shared");
        SourceFiles.write(shared, Map.of(
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
        Path linkedPackage = Files.createDirectories(directory.resolve("src"));
        SymbolicLinks.create(linkedPackage.resolve("fx"), shared.resolve("fx"));
        Path linkedRoot = SymbolicLinks.create(directory.resolve("root"), shared);

        for (Path sources : List.of(linkedPackage, linkedRoot)) {
            GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(sources),
                    SourceFiles.classpath(), directory.resolve("out/sources"), directory.resolve("out/processor"),
                    directory.resolve("out/classes"), directory.resolve("out/work"), "fx:demo:main"));

            assertEquals(List.of("fx.Orders"), model.graphs().stream().map(GraphDescriptor::facade).toList());
            assertEquals(List.of("fx.PaymentActionsRefs.Authorize"),
                    model.symbols().stream().map(SymbolDescriptor::qualifiedName).toList());
            assertEquals(directory.relativize(sources).toString() + "/fx/PaymentActions.java",
                    model.symbols().get(0).origin().path());
        }
    }

    /**
     * A link back to a directory that contains it would make the walk endless. Gradle's own walk of a source set
     * skips such a link, so the generator skips it too, and reads every source once.
     */
    @Test
    void skipsASymbolicLinkThatLoopsBackIntoTheSourceDirectory() throws IOException {
        Path sources = directory.resolve("src");
        SourceFiles.write(sources, Map.of(
                "fx.Order", ORDER,
                "fx.PaymentActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class PaymentActions {
                        @GraphAction
                        public void authorize(Order order) {}
                    }
                    """));
        SymbolicLinks.create(sources.resolve("fx/again"), sources);

        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(sources),
                SourceFiles.classpath(), directory.resolve("out/sources"), directory.resolve("out/processor"),
                directory.resolve("out/classes"), directory.resolve("out/work"), "fx:demo:main"));

        assertEquals(List.of("fx.PaymentActionsRefs.Authorize"),
                model.symbols().stream().map(SymbolDescriptor::qualifiedName).toList());
        assertEquals("src/fx/PaymentActions.java", model.symbols().get(0).origin().path());
    }

    /**
     * Gradle and maven-compiler-plugin leave out of every source directory the files the Ant-style default
     * excludes name: the AppleDouble companion {@code ._Name.java} a macOS copy leaves beside a source, an
     * editor's lock file, and everything inside the directories of a version control system. javac never sees
     * them, so the generator reads none of them either, whatever they hold.
     */
    @Test
    void skipsTheFilesABuildExcludesByDefault() throws IOException {
        Path sources = directory.resolve("src");
        SourceFiles.write(sources, Map.of(
                "fx.Order", ORDER,
                "fx.PaymentActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class PaymentActions {
                        @GraphAction
                        public void authorize(Order order) {}
                    }
                    """));
        byte[] junk = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        for (String excluded : List.of("fx/._PaymentActions.java", "fx/.#PaymentActions.java", ".git/fx/Junk.java",
                "fx/.svn/Junk.java", "fx/.hg/Junk.java", "fx/.bzr/Junk.java", "fx/CVS/Junk.java",
                "fx/SCCS/Junk.java")) {
            Path file = sources.resolve(excluded);
            Files.createDirectories(file.getParent());
            Files.write(file, junk);
        }

        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(sources),
                SourceFiles.classpath(), directory.resolve("out/sources"), directory.resolve("out/processor"),
                directory.resolve("out/classes"), directory.resolve("out/work"), "fx:demo:main"));

        assertEquals(List.of("fx.PaymentActionsRefs.Authorize"),
                model.symbols().stream().map(SymbolDescriptor::qualifiedName).toList());
    }

    /**
     * Gradle leaves out of a source directory, with everything inside it, every directory a default exclude of Ant
     * names, not only the directory of a version control system: one named like an editor's backup or lock file,
     * like an AppleDouble companion or like a file of a version control system. So does the generator's walk. A
     * directory whose name only resembles one, such as {@code #} or {@code %}, which the patterns {@code #*#} and
     * {@code %*%} need two characters to match, is walked as Gradle walks it.
     */
    @Test
    void skipsTheDirectoriesGradleExcludesByDefault() throws IOException {
        Path sources = directory.resolve("src");
        SourceFiles.write(sources, Map.of("fx.Order", ORDER));
        byte[] junk = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00, 'M', 'a', 'c', ' ', 'O', 'S', ' ', 'X',
            (byte) 0xff, (byte) 0xfe, 0x00, 0x01, '{', '}', (byte) 0x80};
        for (String excluded : List.of("~", "old~", "#old#", ".#old", "%old%", "._old", ".DS_Store", ".cvsignore",
                "vssver.scc", ".gitattributes", ".gitignore", ".gitmodules", ".hgignore", ".hgsub", ".hgsubstate",
                ".hgtags", ".bzrignore")) {
            Path file = sources.resolve("fx").resolve(excluded).resolve("Junk.java");
            Files.createDirectories(file.getParent());
            Files.write(file, junk);
        }
        for (Map.Entry<String, String> walked : Map.of("#", "PaymentActions", "%", "RefundActions").entrySet()) {
            Path file = sources.resolve("fx").resolve(walked.getKey()).resolve(walked.getValue() + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class %s {
                        @GraphAction
                        public void authorize(Order order) {}
                    }
                    """.formatted(walked.getValue()));
        }

        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(sources),
                SourceFiles.classpath(), directory.resolve("out/sources"), directory.resolve("out/processor"),
                directory.resolve("out/classes"), directory.resolve("out/work"), "fx:demo:main"));

        assertEquals(List.of("fx.PaymentActionsRefs.Authorize", "fx.RefundActionsRefs.Authorize"),
                model.symbols().stream().map(SymbolDescriptor::qualifiedName).toList());
    }

    /**
     * Without graphmaquina-core on the generator classpath no marker can be recognized; that is reported at the
     * first marker spelled like one, instead of generating nothing and leaving javac to fail later.
     */
    @Test
    void reportsAMarkerWhoseAnnotationTypeIsNotOnTheGeneratorClasspath() {
        Map<String, Map<String, String>> fixtures = new LinkedHashMap<>();
        fixtures.put("io.github.graphmaquina.annotation.GenerateGraphApi", Map.of(
                "fx.Order", ORDER,
                "fx.Status", STATUS,
                "fx.DocTransition", TRANSITION,
                "fx.Orders", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Orders extends Machine<Order, Status, DocTransition> {}
                    """));
        fixtures.put("io.github.graphmaquina.annotation.GraphAction", Map.of(
                "fx.Order", ORDER,
                "fx.PaymentActions", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class PaymentActions {
                        @GraphAction
                        public void authorize(Order order) {}
                    }
                    """));
        for (Map.Entry<String, Map<String, String>> fixture : fixtures.entrySet()) {
            Path project = directory.resolve(fixture.getKey());
            Path sources = project.resolve("src");
            SourceFiles.write(sources, fixture.getValue());

            CodegenException failure = assertThrows(CodegenException.class, () -> Bootstrap.inspect(
                    new GenerationRequest(project, List.of(sources), List.of(), project.resolve("out/sources"),
                            project.resolve("out/processor"), project.resolve("out/classes"),
                            project.resolve("out/work"), "fx:demo:main")));

            assertEquals("SG601", failure.code());
            assertTrue(failure.getMessage().endsWith(fixture.getKey() + " is not on the generator classpath"),
                    failure.getMessage());
            assertEquals(4L, failure.location().orElseThrow().line(), failure.getMessage());
        }
    }

    /**
     * javac resolves the annotations written on a type in the scope that encloses it, where the type's own member
     * types are not visible, so a member type named like a marker never hides the marker: the machine, its peer and
     * the binding host are all recognized.
     */
    @Test
    void recognizesTheMarkersOfADeclarationThatDeclaresMemberTypesOfTheSameNames() {
        Path sources = directory.resolve("src");
        SourceFiles.write(sources, Map.of(
                "fx.Doc", "package fx; public class Doc { public enum Status { DRAFT } public enum Step { GO } }",
                "fx.Pages", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    public interface Pages extends Machine<Doc, Doc.Status, Doc.Step> {}
                    """,
                "fx.Docs", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.annotation.GraphPeers;
                    import io.github.graphmaquina.api.Machine;
                    @GenerateGraphApi
                    @GraphPeers(Pages.class)
                    public interface Docs extends Machine<Doc, Doc.Status, Doc.Step> {
                        @interface GenerateGraphApi {}
                        @interface GraphPeers {}
                    }
                    """,
                "fx.Base", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphAction;
                    public class Base {
                        @GraphAction
                        public void archive(Object doc) {}
                    }
                    """,
                "fx.Handlers", """
                    package fx;
                    import io.github.graphmaquina.annotation.GraphBindings;
                    @GraphBindings
                    public class Handlers extends Base {
                        @interface GraphBindings {}
                    }
                    """));

        GenerationModel model = Bootstrap.inspect(new GenerationRequest(directory, List.of(sources),
                SourceFiles.classpath(), directory.resolve("out/sources"), directory.resolve("out/processor"),
                directory.resolve("out/classes"), directory.resolve("out/work"), "fx:demo:main"));

        assertEquals(List.of("fx.Docs", "fx.Pages"), model.graphs().stream().map(GraphDescriptor::facade).toList());
        assertEquals(List.of("fx.Pages"), model.graphs().get(0).peers());
        assertEquals(List.of("fx.BaseRefs.Archive", "fx.HandlersRefs.Archive"),
                model.symbols().stream().map(SymbolDescriptor::qualifiedName).toList());
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

    /**
     * The bodies of {@code Shop.handlers()} in which {@code GraphAction} or {@code GraphBindings} is a member type
     * of {@code Lib}.
     */
    private static Map<String, String> bodiesWritingAMemberTypeOfLibAsAMarker() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("an anonymous class of a type that declares it", """
                        return new Lib() {
                            @GraphAction
                            void run() {}
                        };
                """);
        bodies.put("an anonymous class of a type that inherits it", """
                        return new Base() {
                            @GraphBindings
                            void run() {}
                        };
                """);
        bodies.put("an anonymous class of a member class of a local class", """
                        class Handlers {
                            class Holder extends Lib {}
                            Object inner() {
                                return new Holder() {
                                    @GraphAction
                                    void run() {}
                                };
                            }
                        }
                        return new Handlers();
                """);
        bodies.put("a local class in a local class that inherits it", """
                        class Outer extends Lib {
                            Object inner() {
                                class Inner {
                                    @GraphAction
                                    void run() {}
                                }
                                return new Inner();
                            }
                        }
                        return new Outer();
                """);
        return bodies;
    }

    /** The bodies of {@code Shop.handlers()} in which {@code GraphGuard} is a member type of {@code Lib.Holder}. */
    private static Map<String, String> bodiesWritingAMemberTypeOfLibHolderAsAMarker() {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("a supertype a local class takes over", """
                        class Outer extends Lib {
                            Object inner() {
                                return new Holder() {
                                    @GraphGuard
                                    void run() {}
                                };
                            }
                        }
                        return new Outer();
                """);
        bodies.put("a local class named like a type of the package", """
                        class Holder extends Lib.Holder {}
                        return new Holder() {
                            @GraphGuard
                            void run() {}
                        };
                """);
        bodies.put("an anonymous class of a member of the type of an expression", """
                        return new Lib().new Part() {
                            @GraphGuard
                            void run() {}
                        };
                """);
        return bodies;
    }

    /**
     * Asserts that each of {@code bodies}, placed in {@code shop} beside {@code lib}, compiles and still exports the
     * one real marker of the file.
     */
    private void assertEachBodyExportsOnlyTheMarkerOfShop(String lib, String shop, Map<String, String> bodies) {
        int compiled = 0;
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            List<Path> files = SourceFiles.write(directory, Map.of("fx.Order", ORDER, "fx.Lib", lib,
                    "fx.Base", "package fx; public class Base extends Lib {}", "fx.Holder",
                    "package fx; public class Holder {}", "fx.Part", "package fx; public class Part {}",
                    "fx.Shop", shop.formatted(body.getValue())));
            SourceFiles.Compilation compilation =
                    SourceFiles.compileStrict(files, directory.resolve("classes-" + compiled++));
            assertTrue(compilation.success(), body.getKey() + ": " + compilation.messages());

            assertEquals(List.of("fx.ShopRefs.Archive"),
                    collect(files).stream().map(SymbolDescriptor::qualifiedName).toList(), body.getKey());
        }
    }

    /** Asserts that a marker on a local class itself and one in a constant of a local enum are still refused. */
    private void assertEachMarkerOnALocalClassOrEnumConstantIsRefused(String lib, String shop) {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("GraphBindings", """
                        @GraphBindings
                        class Handlers extends Lib {}
                        return new Handlers();
                """);
        refused.put("GraphAction", """
                        enum Kind {
                            OPEN {
                                @GraphAction
                                void run() {}
                            }
                        }
                        return Kind.OPEN;
                """);
        for (Map.Entry<String, String> body : refused.entrySet()) {
            CodegenException failure = assertThrows(CodegenException.class, () -> collect(Map.of("fx.Order", ORDER,
                    "fx.Lib", lib, "fx.Shop", shop.formatted(body.getValue()))), body.getKey());

            assertEquals("SG604", failure.code());
            assertEquals("the marker @" + body.getKey() + " is written in a local or anonymous class of fx.Shop, which"
                    + " no references container can name, so it exports nothing: mark a member of a top-level or"
                    + " member class instead", failure.getMessage());
            assertEquals(body.getKey().equals("GraphBindings") ? 9L : 11L, failure.location().orElseThrow().line());
        }
    }

    private static void assertInheritedAuthorizeIsExportedUnderTheConcreteHost(List<SymbolDescriptor> symbols) {
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
        assertEquals("fx/BaseActions.java", inherited.origin().path());
        assertEquals(4L, inherited.origin().line());
    }

    private static void assertOverloadsOfCheckUnionTheirRoles(List<SymbolDescriptor> symbols) {
        SymbolDescriptor check = symbol(symbols, "fx.ConcreteActionsRefs.Check");
        assertEquals(Set.of(SymbolKind.GUARD, SymbolKind.HOOK), check.kinds());
        assertEquals(List.of(SymbolKind.GUARD, SymbolKind.HOOK),
                check.candidates().stream().map(MemberDescriptor::role).toList());
        assertEquals(List.of(List.of("fx.Order"), List.of("fx.Order", "java.lang.String")),
                check.candidates().stream().map(MemberDescriptor::parameterTypes).toList());
        assertEquals(List.of("fx.ConcreteActions", "fx.ConcreteActions"),
                check.candidates().stream().map(MemberDescriptor::declaringType).toList());
    }

    private static void assertOnlyTheStaticsTheHostInheritsAreExported(List<SymbolDescriptor> symbols) {
        assertEquals(List.of("fx.HostRefs.Approve", "fx.HostRefs.Keep"), symbols.stream()
                .map(SymbolDescriptor::qualifiedName)
                .filter((String name) -> name.startsWith("fx.HostRefs.")).toList());
        assertEquals(List.of("fx.MiddleActions"), symbol(symbols, "fx.HostRefs.Approve").candidates().stream()
                .map(MemberDescriptor::declaringType).toList());
        assertEquals(List.of("fx.BaseActions"), symbol(symbols, "fx.HostRefs.Keep").candidates().stream()
                .map(MemberDescriptor::declaringType).toList());
        assertEquals(Set.of(SymbolKind.HOOK), symbol(symbols, "fx.HooksRefs.Announced").kinds());
    }

    /**
     * Puts in {@code shops}, by the marker it writes, a class {@code fx.Shop} that writes that marker in a local or
     * anonymous class, and in {@code lines} the line of the file where it writes it.
     */
    private static void putShopsWithAMarkerInALocalOrAnonymousClass(Map<String, String> shops,
            Map<String, Long> lines) {
        shops.put("GraphAction", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                public class Shop {
                    public static Object handlers() {
                        class Handlers {
                            @GraphAction
                            public void archive(Order order) {}
                        }
                        return new Handlers();
                    }
                }
                """);
        lines.put("GraphAction", 6L);
        shops.put("io.github.graphmaquina.annotation.GraphGuard", """
                package fx;
                public class Shop {
                    private final Object handlers = new Object() {
                        @io.github.graphmaquina.annotation.GraphGuard
                        public void approved(Order order) {}
                    };
                }
                """);
        lines.put("io.github.graphmaquina.annotation.GraphGuard", 4L);
        shops.put("GraphProperty", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                public enum Shop {
                    OPEN {
                        @GraphProperty
                        public String label() { return "open"; }
                    };
                }
                """);
        lines.put("GraphProperty", 5L);
        shops.put("GraphBindings", """
                package fx;
                import io.github.graphmaquina.annotation.GraphBindings;
                public class Shop {
                    static {
                        Runnable runnable = () -> {
                            @GraphBindings
                            class Handlers {}
                        };
                    }
                }
                """);
        lines.put("GraphBindings", 6L);
    }

    private void assertEachPrivateHostIsRefusedAtTheHost(Map<String, String> hosts) {
        for (Map.Entry<String, String> host : hosts.entrySet()) {
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> collect(Map.of("fx.Order", ORDER, "fx.Shop", host.getValue())));

            assertEquals("SG604", failure.code());
            assertTrue(failure.getMessage().startsWith("the host fx.Shop."), failure.getMessage());
            assertTrue(failure.getMessage().endsWith(": " + host.getKey() + " is private"), failure.getMessage());
            assertEquals("fx/Shop.java", failure.location().orElseThrow().path());
        }
    }

    /**
     * Compiles the same fixture for real and renders the parameter types javac gives {@code type.method} the way
     * the processor renders them before comparing them with a candidate: qualified, with an enclosing type only
     * for an inner class.
     */
    private List<String> parameterTypesAccordingToJavac(List<Path> files, String type, String method) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, SourceFiles.classpath());
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,
                    List.of(Files.createDirectories(directory.resolve("oracle-classes"))));
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null, fileManager.getJavaFileObjectsFromPaths(files));
            task.analyze();
            assertEquals(List.of(), diagnostics.getDiagnostics().stream()
                    .filter((Diagnostic<? extends JavaFileObject> diagnostic) ->
                            diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    .map(Object::toString)
                    .toList());
            for (ExecutableElement candidate : ElementFilter.methodsIn(
                    task.getElements().getTypeElement(type).getEnclosedElements())) {
                if (candidate.getSimpleName().contentEquals(method)) {
                    List<String> rendered = new ArrayList<>();
                    for (VariableElement parameter : candidate.getParameters()) {
                        rendered.add(render(parameter.asType()));
                    }
                    return rendered;
                }
            }
            throw new IllegalStateException("javac found no method " + method + " on " + type);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The rendering of the processor's {@code ManifestTypes}, for the declared and array types these fixtures use. */
    private static String render(TypeMirror type) {
        if (type instanceof DeclaredType declared) {
            return renderDeclared(declared);
        }
        if (type instanceof ArrayType array) {
            return render(array.getComponentType()) + "[]";
        }
        return type.toString();
    }

    private static String renderDeclared(DeclaredType declared) {
        TypeElement element = (TypeElement) declared.asElement();
        String name = declared.getEnclosingType().getKind() == TypeKind.NONE
                ? element.getQualifiedName().toString()
                : render(declared.getEnclosingType()) + '.' + element.getSimpleName();
        if (declared.getTypeArguments().isEmpty()) {
            return name;
        }
        List<String> arguments = new ArrayList<>();
        for (TypeMirror argument : declared.getTypeArguments()) {
            arguments.add(render(argument));
        }
        return name + '<' + String.join(", ", arguments) + '>';
    }
}
