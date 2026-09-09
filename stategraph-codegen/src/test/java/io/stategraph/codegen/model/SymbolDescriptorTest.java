package io.stategraph.codegen.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.stategraph.annotation.internal.SymbolKind;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies what a symbol descriptor normalizes when it is built, so two runs publish the same value. */
class SymbolDescriptorTest {

    @Test
    void rolesIterateInDeclarationOrderWhateverOrderTheyWereGivenIn() {
        Set<SymbolKind> given = new LinkedHashSet<>();
        given.add(SymbolKind.HOOK);
        given.add(SymbolKind.GUARD);

        SymbolDescriptor symbol = descriptor(given);

        assertEquals(List.of(SymbolKind.GUARD, SymbolKind.HOOK), List.copyOf(symbol.kinds()));
        assertEquals(List.of(SymbolKind.GUARD, SymbolKind.HOOK),
                List.copyOf(descriptor(Set.of(SymbolKind.HOOK, SymbolKind.GUARD)).kinds()));
        assertEquals(Set.of(SymbolKind.GUARD, SymbolKind.HOOK), symbol.kinds());
    }

    @Test
    void aDescriptorThatCarriesNoRoleIsStillBuilt() {
        assertEquals(Set.of(), descriptor(Set.of()).kinds());
    }

    private static SymbolDescriptor descriptor(Set<SymbolKind> kinds) {
        return new SymbolDescriptor("fx.OrderRefs.Check", "fx.Order", "check", kinds, List.of(),
                Optional.empty(), new SourceLocation("fx/Order.java", 1L, 1L));
    }
}
