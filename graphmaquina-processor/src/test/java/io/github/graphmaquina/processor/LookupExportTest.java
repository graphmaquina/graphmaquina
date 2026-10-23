package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A method marked {@code @GraphLookup} is exported like every other marked method: the processor claims the
 * marker, checks each declaration where it is written, whether or not a graph uses it, and publishes its symbol
 * in the index of the compilation. A lookup is public, static or instance, takes one parameter and answers with an
 * {@code Optional}.
 */
class LookupExportTest {

    private static final String PUBLISHED = "META-INF/graphmaquina/exports.json";

    @Test
    void publishesAStaticAndAnInstanceLookupWithTheLookupRole() throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(lookups("""
                    @GraphLookup public static Optional<Order> orderPaying(Payment payment) { return Optional.empty(); }
                    @GraphLookup public Optional<Order> orderRefunding(Payment payment) { return Optional.empty(); }
                """));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertTrue(result.success(), result.describe());
        ExportIndex index = ManifestCodec.readExports(prepared.request().classesOutput().resolve(PUBLISHED));
        Map<String, Set<SymbolKind>> kinds = new LinkedHashMap<>();
        for (SymbolDescriptor symbol : index.model().symbols()) {
            kinds.put(symbol.qualifiedName(), symbol.kinds());
        }
        assertEquals(Map.of("fx.OrderLookupsRefs.OrderPaying", Set.of(SymbolKind.LOOKUP),
                "fx.OrderLookupsRefs.OrderRefunding", Set.of(SymbolKind.LOOKUP)), kinds);
    }

    @Test
    void refusesALookupTheGeneratedCodeCouldNotCall() {
        Compilation.Result result = Compilation.compileTyped(lookups("""
                    @GraphLookup Optional<Order> orderPaying(Payment payment) { return Optional.empty(); }
                """));

        assertEquals(List.of("[SG604] fx.OrderLookups.orderPaying is exported as a lookup, so it must be public:"
                + " generated code calls it from another package"), result.errors(), result.describe());
    }

    @Test
    void refusesALookupThatIsNotShapedLikeOne() {
        Compilation.Result result = Compilation.compileTyped(lookups("""
                    @GraphLookup public Order bare(Payment payment) { return new Order(); }
                    @GraphLookup public Optional<Order> noChild() { return Optional.empty(); }
                    @GraphLookup public Optional<Order> twoChildren(Payment first, Payment second) {
                        return Optional.empty();
                    }
                """));

        String expected = " does not have the signature of a lookup: expected Optional of the parent entity from"
                + " the child entity alone";
        assertEquals(List.of("[SG604] fx.OrderLookups.bare" + expected, "[SG604] fx.OrderLookups.noChild" + expected,
                "[SG604] fx.OrderLookups.twoChildren" + expected), result.errors(), result.describe());
    }

    @Test
    void refusesAnOverrideThatContradictsTheRoleOfALookup() {
        Map<String, String> sources = lookups("""
                    @GraphLookup public Optional<Order> orderOf(Payment payment) { return Optional.empty(); }
                """);
        sources.put("fx.PaymentConditions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphCondition;
                import java.util.Optional;
                public final class PaymentConditions extends OrderLookups {
                    @Override @GraphCondition public Optional<Order> orderOf(Payment payment) { \
                return Optional.empty(); }
                }
                """);
        sources.put("fx.OrderLookups", sources.get("fx.OrderLookups").replace("public final class", "public class"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.errors().contains("[SG604] fx.PaymentConditions.orderOf is exported as a condition, but it"
                + " overrides fx.OrderLookups.orderOf, whose contract is a lookup"), result.describe());
    }

    /** An order, a payment and the host {@code fx.OrderLookups} declaring {@code members}. */
    private static Map<String, String> lookups(String members) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.Order", """
                package fx;
                public final class Order { }
                """);
        sources.put("fx.Payment", """
                package fx;
                public final class Payment { }
                """);
        sources.put("fx.OrderLookups", """
                package fx;
                import io.github.graphmaquina.annotation.GraphLookup;
                import java.util.Optional;
                public final class OrderLookups {
                %s}
                """.formatted(members));
        return sources;
    }
}
