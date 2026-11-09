package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every machine's companion declares {@code structure()}: the definition {@code graph(...)} builds, with no
 * collaborator bound, which draws as that one does and whose callables that call an instance member of a collaborator
 * throw when they run.
 */
class StructureGenerationTest {

    @Test
    void structureDrawsTheGraphTheCollaboratorsDraw() throws Exception {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.minimal());

        assertTrue(result.success(), result.describe());
        Class<?> host = result.load("fx.DocActions");
        Constructor<?> constructor = host.getDeclaredConstructor();
        GraphDefinition<?, ?, ?> bound = (GraphDefinition<?, ?, ?>) result.load("fx.DocsRuntime")
                .getMethod("graph", host).invoke(null, constructor.newInstance());
        assertEquals(GraphvizExporter.render(GraphView.of(bound)),
                GraphvizExporter.render(GraphView.of(structure(result, "fx.DocsRuntime"))));
    }

    @Test
    void structureIsAPublicStaticMethodWithItsOwnDocumentation() throws Exception {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.minimal());

        assertTrue(result.success(), result.describe());
        int modifiers = result.load("fx.DocsRuntime").getMethod("structure").getModifiers();
        assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers));
        String companion = result.generated("fx.DocsRuntime");
        String comment = "\n" + " ".repeat(5) + "* ";
        assertTrue(companion.contains("/**" + comment + "The definition as the annotations of {@link Docs} declare it,"
                + " with no collaborator bound, for" + comment + "inspection, structural tests and diagrams."),
                companion);
        assertTrue(companion.contains("@return the definition, with no collaborator bound" + "\n"
                + " ".repeat(5) + "*/\n" + " ".repeat(4)
                + "public static GraphDefinition<Doc, DocStatus, DocTransition> structure() {\n"), companion);
    }

    @Test
    void anInstanceActionOfStructureThrowsNamingTheCollaboratorAndTheCompanion() throws Exception {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.minimal());

        assertTrue(result.success(), result.describe());
        TransitionSpec<?, ?, ?> publish = structure(result, "fx.DocsRuntime").transitions().get(0);
        ActionRef.Sync<?, ?, ?> action = (ActionRef.Sync<?, ?, ?>) publish.action().orElseThrow();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> action.action().process(null));
        assertEquals("fx.DocsRuntime.structure() binds no fx.DocActions: build a machine from"
                + " fx.DocsRuntime.graph(...)", thrown.getMessage());
    }

    /**
     * A parent reads the definition of each child once, from the child's own {@code structure()}, keeps the static
     * lookup as {@code graph(...)} writes it, and draws as the graph bound to its collaborators does.
     */
    @Test
    void aParentReadsEachChildOnceFromItsStructureAndDrawsAsItsGraph() throws Exception {
        Compilation.Result result = Compilation.compileTyped(SubmachineGenerationTest.billing());

        assertTrue(result.success(), result.describe());
        String bookings = result.generated("fx.BookingsRuntime");
        String structure = bookings.substring(bookings.indexOf(" structure() {"));
        assertTrue(structure.contains("{\n" + " ".repeat(8) + "GraphDefinition<Payment, PaymentStatus,"
                + " PaymentTransition> paymentsGraph = PaymentsRuntime.structure();\n"), structure);
        assertTrue(structure.contains(".submachine(BookingStatus.DEPOSIT, paymentsGraph, (Booking booking) ->"
                + " booking.deposit(), (Payment payment) -> BookingLookups.depositOf(payment))"), structure);
        assertTrue(structure.contains(".submachine(BookingStatus.BALANCE, paymentsGraph, (Booking booking) ->"
                + " booking.balance(), (Payment payment) -> UnboundCollaborator.<Ledger>of(\"fx.Ledger\","
                + " \"fx.BookingsRuntime\").balanceOf(payment))"), structure);
        Class<?> ledger = result.load("fx.Ledger");
        GraphDefinition<?, ?, ?> payments =
                (GraphDefinition<?, ?, ?>) result.load("fx.PaymentsRuntime").getMethod("graph").invoke(null);
        GraphDefinition<?, ?, ?> bound = (GraphDefinition<?, ?, ?>) result.load("fx.BookingsRuntime")
                .getMethod("graph", GraphDefinition.class, ledger)
                .invoke(null, payments, ledger.getDeclaredConstructor().newInstance());
        assertEquals(GraphvizExporter.render(GraphView.of(bound)),
                GraphvizExporter.render(GraphView.of(structure(result, "fx.BookingsRuntime"))));
    }

    /**
     * A parent of more declarations than one method makes hands the definition of its child, read once, to each of the
     * methods that declare part of its structure, which keep clear of a method of the facade named like one of them.
     */
    @Test
    void aParentOfMoreThanAHundredDeclarationsHandsItsChildToEachPartOfItsStructure() throws Exception {
        Map<String, String> sources = SubmachineGenerationTest.billing();
        StringBuilder ids = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        for (int t = 0; t < 100; t++) {
            ids.append(", T").append(t);
            edges.append("@BookingsGraph.Transition(id = T").append(t).append(", from = REQUESTED, to = CANCELLED)\n");
        }
        sources.put("fx.BookingTransition",
                sources.get("fx.BookingTransition").replace("CANCEL }", "CANCEL" + ids + " }"));
        sources.put("fx.Bookings", sources.get("fx.Bookings")
                .replace("public interface Bookings", edges + "public interface Bookings")
                .replace("book(Booking booking);\n", "book(Booking booking);\n"
                        + "    default String structurePart1() { return \"bookings\"; }\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        String bookings = result.generated("fx.BookingsRuntime");
        String eight = " ".repeat(8);
        assertTrue(bookings.contains(eight + "graph = structurePart_1(graph, paymentsGraph);\n" + eight
                + "graph = structurePart_2(graph, paymentsGraph);\n" + eight + "return graph.build();\n"), bookings);
        assertTrue(bookings.contains("private static GraphBuilder<Booking, BookingStatus, BookingTransition>"
                + " structurePart_2(GraphBuilder<Booking, BookingStatus, BookingTransition> graph,"
                + " GraphDefinition<Payment, PaymentStatus, PaymentTransition> paymentsGraph)"), bookings);
        Class<?> ledger = result.load("fx.Ledger");
        GraphDefinition<?, ?, ?> payments =
                (GraphDefinition<?, ?, ?>) result.load("fx.PaymentsRuntime").getMethod("graph").invoke(null);
        GraphDefinition<?, ?, ?> bound = (GraphDefinition<?, ?, ?>) result.load("fx.BookingsRuntime")
                .getMethod("graph", GraphDefinition.class, ledger)
                .invoke(null, payments, ledger.getDeclaredConstructor().newInstance());
        assertEquals(GraphvizExporter.render(GraphView.of(bound)),
                GraphvizExporter.render(GraphView.of(structure(result, "fx.BookingsRuntime"))));
    }

    /** The lookup of an instance host throws when it runs; the one of a static host runs as in {@code graph(...)}. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anInstanceLookupOfStructureThrowsAndAStaticOneRuns() throws Exception {
        Compilation.Result result = Compilation.compileTyped(SubmachineGenerationTest.billing());

        assertTrue(result.success(), result.describe());
        GraphDefinition definition = structure(result, "fx.BookingsRuntime");
        Class<? extends Enum> states = (Class<? extends Enum>) result.load("fx.BookingStatus");
        Object payment = result.load("fx.Payment").getConstructor(String.class).newInstance("payment");
        SubmachineSpec deposit = (SubmachineSpec) definition.submachine(Enum.valueOf(states, "DEPOSIT")).orElseThrow();
        SubmachineSpec balance = (SubmachineSpec) definition.submachine(Enum.valueOf(states, "BALANCE")).orElseThrow();
        assertEquals(Optional.empty(), deposit.parentOf().apply(payment));
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> balance.parentOf().apply(payment));
        assertEquals("fx.BookingsRuntime.structure() binds no fx.Ledger: build a machine from"
                + " fx.BookingsRuntime.graph(...)", thrown.getMessage());
    }

    /** The definition {@code companion}'s {@code structure()} returns. */
    static GraphDefinition<?, ?, ?> structure(Compilation.Result result, String companion) throws Exception {
        return (GraphDefinition<?, ?, ?>) result.load(companion).getMethod("structure").invoke(null);
    }
}
