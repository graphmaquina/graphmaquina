package io.stategraph.codegen.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SymbolNamesTest {

    @Test
    void symbolSpellingIsInjectiveAndIndependentOfOtherMembers() {
        assertEquals("Authorize", SymbolNames.typeName("PaymentActions", "authorize"));
        assertEquals("_Authorize", SymbolNames.typeName("PaymentActions", "Authorize"));
        assertEquals("__authorize", SymbolNames.typeName("PaymentActions", "_authorize"));
        assertEquals("_sg_PaymentActionsRefs",
            SymbolNames.typeName("PaymentActions", "paymentActionsRefs"));
    }
}
