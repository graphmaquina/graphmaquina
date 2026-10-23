package io.github.graphmaquina.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.source.Bootstrap;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the sub-machine annotations the vocabulary of an ordered pair declares: their exact shape, with the
 * owner's states in {@code state}, the peer's states in {@code Exit.on} and the owner's identifiers in
 * {@code Exit.transition}; a repeated {@code Submachine} gathered into its container; and a constant of the wrong
 * side, or an {@code Exit} written outside a {@code Submachine}, refused by javac itself.
 */
class SubmachineVocabularyTest {

    @TempDir
    Path directory;

    private static final Map<String, String> ORIGINALS = originals();

    private static Map<String, String> originals() {
        Map<String, String> sources = new LinkedHashMap<>();
        putOrderAndPaymentEnums(sources);
        putOrderAndPaymentEntities(sources);
        putLookupsAndMachines(sources);
        return Map.copyOf(sources);
    }

    private static void putOrderAndPaymentEnums(Map<String, String> sources) {
        sources.put("fx.OrderStatus", """
            package fx;
            /** Fixture type for the sub-machine vocabulary compilation. */
            public enum OrderStatus { PLACED, PAYING, PAID, CANCELLED }
            """);
        sources.put("fx.OrderTransition", """
            package fx;
            /** Fixture type for the sub-machine vocabulary compilation. */
            public enum OrderTransition { PAY, PAID, PAYMENT_DECLINED, CANCEL }
            """);
        sources.put("fx.PaymentStatus", """
            package fx;
            /** Fixture type for the sub-machine vocabulary compilation. */
            public enum PaymentStatus { AUTHORIZING, CAPTURED, DECLINED }
            """);
        sources.put("fx.PaymentTransition", """
            package fx;
            /** Fixture type for the sub-machine vocabulary compilation. */
            public enum PaymentTransition { CAPTURE, DECLINE }
            """);
    }

    private static void putOrderAndPaymentEntities(Map<String, String> sources) {
        sources.put("fx.Order", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            import java.util.Optional;
            /** Fixture type for the sub-machine vocabulary compilation. */
            public class Order {
                @GraphProperty
                private OrderStatus status;
                @GraphProperty
                private Optional<Payment> payment = Optional.empty();
                public OrderStatus status() { return status; }
                public Optional<Payment> payment() { return payment; }
            }
            """);
        sources.put("fx.Payment", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            import java.util.Optional;
            /** Fixture type for the sub-machine vocabulary compilation. */
            public class Payment {
                @GraphProperty
                private Optional<Order> order = Optional.empty();
                public Optional<Order> order() { return order; }
            }
            """);
    }

    private static void putLookupsAndMachines(Map<String, String> sources) {
        sources.put("fx.OrderLookups", """
            package fx;
            import io.github.graphmaquina.annotation.GraphLookup;
            import java.util.Optional;
            /** Fixture type for the sub-machine vocabulary compilation. */
            public class OrderLookups {
                @GraphLookup
                public static Optional<Order> orderPaying(Payment payment) { return Optional.empty(); }
            }
            """);
        sources.put("fx.Orders", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.annotation.GraphPeers;
            import io.github.graphmaquina.api.Machine;
            /** Fixture type for the sub-machine vocabulary compilation. */
            @GenerateGraphApi
            @GraphPeers(Payments.class)
            public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {}
            """);
        sources.put("fx.Payments", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            /** Fixture type for the sub-machine vocabulary compilation. */
            @GenerateGraphApi
            public interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {}
            """);
    }

    /** The three sub-machine annotations, exactly as the vocabulary of the pair declares them after its watch. */
    private static final String SUBMACHINE_ANNOTATIONS = """

                /**
                 * One sub-machine of {@link fx.Orders}: the state of {@link fx.Orders} that holds a {@link fx.Payments},
                 * how to reach the child from the parent and back, and the transition of {@link fx.Orders}
                 * each terminal state of the child selects.
                 */
                @io.github.graphmaquina.annotation.internal.GeneratedVocabulary(machine = fx.Orders.class, \
            peer = fx.Payments.class, value = io.github.graphmaquina.annotation.internal.VocabularyKind.SUBMACHINE)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                @java.lang.annotation.Repeatable(OrdersPayments.Submachines.class)
                public @interface Submachine {
                    /** {@return the state of the parent that holds the child} */
                    fx.OrderStatus state();
                    /** {@return the property of the parent that reads the child, at most one; none by default} */
                    java.lang.Class<? extends io.github.graphmaquina.symbol.PropertySymbol>[] child() default {};
                    /** {@return the property of the child that reads the parent, at most one; none by default} */
                    java.lang.Class<? extends io.github.graphmaquina.symbol.PropertySymbol>[] parent() default {};
                    /** {@return the lookup that finds the parent of a child, at most one; none by default} */
                    java.lang.Class<? extends io.github.graphmaquina.symbol.LookupSymbol>[] parentLookup() default {};
                    /** {@return the exits, one per terminal state of the child} */
                    OrdersPayments.Exit[] exits();
                }

                /**
                 * Holds the repeated {@link OrdersPayments.Submachine} declarations of {@link fx.Orders} over {@link fx.Payments}.
                 */
                @io.github.graphmaquina.annotation.internal.GeneratedVocabulary(machine = fx.Orders.class, \
            peer = fx.Payments.class, value = io.github.graphmaquina.annotation.internal.VocabularyKind.SUBMACHINES)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                public @interface Submachines {
                    /** {@return the repeated declarations} */
                    OrdersPayments.Submachine[] value();
                }

                /**
                 * One exit in the {@code exits} of a {@link OrdersPayments.Submachine}: a terminal state of {@link fx.Payments}
                 * and the transition of {@link fx.Orders} it selects.
                 */
                @io.github.graphmaquina.annotation.internal.GeneratedVocabulary(machine = fx.Orders.class, \
            peer = fx.Payments.class, value = io.github.graphmaquina.annotation.internal.VocabularyKind.EXIT)
                @java.lang.annotation.Target({})
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                public @interface Exit {
                    /** {@return the terminal state of the child} */
                    fx.PaymentStatus on();
                    /** {@return the transition of the parent that state selects} */
                    fx.OrderTransition transition();
                }
            }
            """;

    /**
     * Repeats {@code Submachine} on one type, as two bound states would, and fills every optional element in one
     * declaration or the other.
     */
    private static final Map<String, String> USAGE = Map.of("use.SubmachineUsage", """
        package use;
        /** Fixture type for the sub-machine vocabulary compilation. */
        @fx.OrdersPayments.Submachine(state = fx.OrderStatus.PAYING,
                child = fx.OrderRefs.Payment.class,
                parentLookup = fx.OrderLookupsRefs.OrderPaying.class,
                exits = {
                    @fx.OrdersPayments.Exit(on = fx.PaymentStatus.CAPTURED, transition = fx.OrderTransition.PAID),
                    @fx.OrdersPayments.Exit(on = fx.PaymentStatus.DECLINED,
                            transition = fx.OrderTransition.PAYMENT_DECLINED)
                })
        @fx.OrdersPayments.Submachine(state = fx.OrderStatus.PLACED,
                child = fx.OrderRefs.Payment.class,
                parent = fx.PaymentRefs.Order.class,
                exits = @fx.OrdersPayments.Exit(on = fx.PaymentStatus.CAPTURED, transition = fx.OrderTransition.PAY))
        public interface SubmachineUsage {}
        """);

    @Test
    void emitsTheSubmachineAnnotationsOfThePairAndTheyCompile() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");

        String pair = content(emitted, "fx.OrdersPayments");
        assertTrue(pair.endsWith(SUBMACHINE_ANNOTATIONS), pair);
        assertTrue(pair.indexOf("public @interface When {") < pair.indexOf("public @interface Submachine {"), pair);

        SourceFiles.Compilation compilation = compile(emitted, USAGE);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of(), compilation.messages());
    }

    /**
     * {@code state} is a state of the parent, {@code on} a state of the child and {@code transition} an identifier
     * of the parent, so a constant of the other side is a javac type error in each of the three.
     */
    @Test
    void leavesAConstantOfTheWrongSideToJavac() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");
        String declaration = """
            package use;
            /** Fixture type for the sub-machine vocabulary compilation. */
            @fx.OrdersPayments.Submachine(state = %s,
                    exits = @fx.OrdersPayments.Exit(on = %s, transition = %s))
            public interface TypedUsage {}
            """;

        SourceFiles.Compilation correct = compile(emitted, Map.of("use.TypedUsage", declaration.formatted(
                "fx.OrderStatus.PAYING", "fx.PaymentStatus.CAPTURED", "fx.OrderTransition.PAID")));
        assertTrue(correct.success(), () -> String.valueOf(correct.messages()));

        for (List<String> wrong : List.of(
                List.of("fx.PaymentStatus.AUTHORIZING", "fx.PaymentStatus.CAPTURED", "fx.OrderTransition.PAID"),
                List.of("fx.OrderStatus.PAYING", "fx.OrderStatus.PAID", "fx.OrderTransition.PAID"),
                List.of("fx.OrderStatus.PAYING", "fx.PaymentStatus.CAPTURED", "fx.PaymentTransition.CAPTURE"))) {
            SourceFiles.Compilation compilation = compile(emitted, Map.of("use.TypedUsage",
                    declaration.formatted(wrong.get(0), wrong.get(1), wrong.get(2))));
            assertFalse(compilation.success(), wrong::toString);
            assertTrue(compilation.codes().stream().allMatch((String code) -> code.startsWith("compiler.err.")),
                    () -> String.valueOf(compilation.codes()));
        }
    }

    /** An exit has no target of its own: it is written inside the {@code exits} of a {@code Submachine} only. */
    @Test
    void refusesAnExitWrittenOutsideASubmachine() {
        List<GeneratedSource> emitted = ApiEmitter.emit(model(), "io.github.graphmaquina:test:main");

        SourceFiles.Compilation compilation = compile(emitted, Map.of("use.LooseExit", """
            package use;
            /** Fixture type for the sub-machine vocabulary compilation. */
            @fx.OrdersPayments.Exit(on = fx.PaymentStatus.CAPTURED, transition = fx.OrderTransition.PAID)
            public interface LooseExit {}
            """));

        assertFalse(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertEquals(List.of("compiler.err.annotation.type.not.applicable"), compilation.codes());
    }

    private GenerationModel model() {
        Path sources = directory.resolve("sources");
        SourceFiles.write(sources, ORIGINALS);
        return Bootstrap.inspect(new GenerationRequest(directory, List.of(sources), SourceFiles.classpath(),
                directory.resolve("generated"), directory.resolve("processor-generated"),
                directory.resolve("classes"), directory.resolve("work"), "io.github.graphmaquina:test:main"));
    }

    private SourceFiles.Compilation compile(List<GeneratedSource> emitted, Map<String, String> usage) {
        List<Path> files = new ArrayList<>(SourceFiles.write(directory.resolve("sources"), ORIGINALS));
        files.addAll(SourceFiles.write(directory.resolve("usage"), usage));
        files.addAll(write(directory.resolve("generated"), emitted));
        return SourceFiles.compileStrict(files, directory.resolve("classes"));
    }

    private static List<Path> write(Path root, List<GeneratedSource> emitted) {
        List<Path> written = new ArrayList<>();
        for (GeneratedSource source : emitted) {
            Path file = root.resolve(source.qualifiedName().replace('.', '/') + ".java");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.content(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            written.add(file);
        }
        return written;
    }

    private static String content(List<GeneratedSource> emitted, String qualifiedName) {
        return emitted.stream().filter((GeneratedSource source) -> source.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow().content();
    }
}
